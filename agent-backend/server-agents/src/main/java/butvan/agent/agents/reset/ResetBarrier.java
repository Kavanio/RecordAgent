package butvan.agent.agents.reset;

import butvan.agent.agents.storage.AgentStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import static java.nio.file.StandardOpenOption.*;

/**
 * 持久化重置屏障与操作代次。构造时先恢复屏障，早于任何渠道和启动恢复任务。
 * 租约覆盖整个写入生命周期；中断不是释放租约的依据，必须由真实 finally／completion 释放。
 */
@Component
public class ResetBarrier {
    private final Path file;
    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private State state;
    private int writers;
    private boolean runtimeUsed;
    private boolean storageFault;

    public ResetBarrier(AgentStorageProperties storage) {
        file = storage.getResetStateFile();
        root = storage.getRootDirectory();
        try {
            ResetFiles.requireSafe(storage.getRootDirectory(), file);
            state = Files.exists(file) ? mapper.readValue(Files.readAllBytes(file), State.class)
                    : new State(0, 0, null, null, null, "IDLE", List.of(), "", null);
            if (state == null || state.generation() < 0 || !List.of("IDLE", "QUIESCING", "CLEANING", "WAITING_WRITERS", "WAITING_RESTART", "FAILED", "LOCAL_COMPLETE").contains(state.status())
                    || state.completedSteps() == null) throw new IOException("invalid reset journal");
        } catch (IOException exception) {
            throw new IllegalStateException("重置状态不可读取，已停止启动以保护数据");
        }
    }

    /** 原子准入：屏障与新操作不能交错。 */
    public synchronized Lease enter() { return enter(null); }

    /** HTTP 写入携带调用方已观察的代次，旧请求不能在清理后重新获得准入。 */
    public synchronized Lease enter(Long expectedGeneration) {
        if (blocked()) throw new ResetBlockedException();
        if (expectedGeneration != null && expectedGeneration != state.generation())
            throw new ResetBlockedException();
        writers++;
        return new Lease(state.generation());
    }
    public synchronized boolean blocked() { return storageFault || !List.of("IDLE", "LOCAL_COMPLETE").contains(state.status()); }
    public synchronized State state() { return state; }
    public synchronized boolean isCurrent(long generation) { return !blocked() && generation == state.generation(); }
    /** SDK 使用后无法从公开 API 证明所有分离任务均退出，本进程必须正常重启后继续。 */
    public synchronized void markRuntimeUsed() {
        if (blocked()) throw new ResetBlockedException();
        runtimeUsed = true;
    }
    public synchronized boolean runtimeUsed() { return runtimeUsed; }

    /** 先刷盘再改变内存代次，失败时不进入清理。 */
    public synchronized void begin(String resetId, String requestId, String owner) {
        begin(resetId, requestId, owner, null);
    }
    /** 确认范围随重置日志持久化；旧日志缺失此字段时保持未授权远端的语义。 */
    public synchronized void begin(String resetId, String requestId, String owner, String scopeVersion) {
        if (blocked()) throw new ResetBlockedException();
        save(new State(state.revision(), state.generation() + 1, resetId, requestId, owner, "QUIESCING", List.of(), "", scopeVersion));
    }
    public synchronized void progress(String status, String message) {
        if (!blocked()) throw new IllegalStateException("没有待完成的重置");
        save(new State(state.revision(), state.generation(), state.resetId(), state.requestId(), state.owner(), status,
                state.completedSteps(), message, state.scopeVersion()));
    }
    public synchronized void stepDone(String step) {
        var steps = new ArrayList<>(state.completedSteps());
        if (!steps.contains(step)) steps.add(step);
        save(new State(state.revision(), state.generation(), state.resetId(), state.requestId(), state.owner(), "CLEANING", steps, "", state.scopeVersion()));
    }
    public synchronized void complete() {
        complete("本地聊天与记忆已清除，业务数据保留；Hindsight 尚未接入，未执行远端清理");
    }
    /** 协调器完成必要参与方后提供真实范围说明；旧 M1 调用仍保持原语义。 */
    public synchronized void complete(String message) {
        if (!blocked()) throw new IllegalStateException("没有待完成的重置");
        if (writers != 0) throw new IllegalStateException("旧写入者尚未退出");
        save(new State(state.revision(), state.generation(), state.resetId(), state.requestId(), state.owner(), "LOCAL_COMPLETE",
                state.completedSteps(), message, state.scopeVersion()));
    }
    public synchronized boolean awaitIdle(Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (writers > 0) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) return false;
            try { wait(Math.max(1, Math.min(100, remaining / 1_000_000))); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }
    private void save(State next) {
        next = new State(state.revision() + 1, next.generation(), next.resetId(), next.requestId(), next.owner(),
                next.status(), List.copyOf(next.completedSteps()), next.message(), next.scopeVersion());
        try {
            ResetFiles.requireSafe(root, file);
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling("state.json.tmp");
            ResetFiles.requireSafe(file.getParent(), temporary);
            byte[] bytes = mapper.writeValueAsBytes(next);
            try (var channel = FileChannel.open(temporary, CREATE, TRUNCATE_EXISTING, WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (Files.getFileStore(file.getParent()).supportsFileAttributeView("posix")) {
                try (var directory = FileChannel.open(file.getParent(), READ)) { directory.force(true); }
            }
            state = next;
            storageFault = false;
        } catch (IOException exception) {
            // 原子替换后目录刷盘仍可能失败；任何不确定结果都必须让本进程保持屏障。
            storageFault = true;
            state = new State(next.revision(), next.generation(), next.resetId(), next.requestId(), next.owner(), "FAILED",
                    next.completedSteps(), "重置进度保存失败，请检查磁盘与权限后重试", next.scopeVersion());
            throw new IllegalStateException("重置进度保存失败，保持保护状态");
        }
    }
    /** 仅保存代次、步骤和安全提示，不保存正文、凭据或真实路径。 */
    public record State(long revision, long generation, String resetId, String requestId, String owner, String status,
                        List<String> completedSteps, String message, String scopeVersion) { }
    public final class Lease implements AutoCloseable {
        private final long generation;
        private boolean closed;
        private Lease(long generation) { this.generation = generation; }
        public long generation() { return generation; }
        @Override public void close() {
            synchronized (ResetBarrier.this) {
                if (!closed) { closed = true; writers--; ResetBarrier.this.notifyAll(); }
            }
        }
    }
}
