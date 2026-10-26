package butvan.agent.feishu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.UnaryOperator;

import static java.nio.file.StandardOpenOption.*;

/**
 * 渠道私有受理与投递账本。先原子落盘再发布内存状态；文件损坏／写入失败时停止工作，禁止空状态兜底。
 * 文件锁只保证同一后端数据目录内单实例，不提供跨机器机器人租约。
 */
@Slf4j
public final class FeishuTaskStore implements AutoCloseable {
    static final int MAX_RECORDS = 5_000;
    static final int MAX_QUEUED = 100;
    /** 常规记录满前保留控制／错误反馈空间，便于停止和人工补发。 */
    static final int RESERVED_CONTROL_RECORDS = 128;
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path directory;
    private final Path path;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private State state;
    private boolean healthy = true;
    private boolean closed;

    public FeishuTaskStore(Path directory) throws IOException {
        this.directory = directory;
        this.path = directory.resolve("state.json");
        Files.createDirectories(directory);
        secure(directory, true);
        lockChannel = FileChannel.open(directory.resolve("channel.lock"), CREATE, WRITE);
        FileLock acquired;
        try {
            secure(directory.resolve("channel.lock"), false);
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("飞书渠道数据已被其他进程占用");
        } catch (IOException | RuntimeException exception) {
            lockChannel.close();
            throw new IOException("无法独占飞书渠道数据目录");
        }
        lock = acquired;
        try {
            if (Files.exists(path)) {
                if (Files.size(path) > MAX_BYTES) throw new IOException("飞书渠道账本超出上限");
                secure(path, false);
                state = mapper.readValue(Files.readAllBytes(path), State.class);
                validate(state);
            } else {
                state = new State(1, Map.of(), List.of());
            }
        } catch (IOException | RuntimeException exception) {
            lock.release(); lockChannel.close();
            // 解析异常可能包含用户正文，禁止把原异常链向上传递。
            throw new IOException("飞书渠道账本不可读取，请检查权限、容量或从备份恢复");
        }
    }

    /** 返回不可变账本快照，冻结或关闭后拒绝读取以阻止继续调度。 */
    public synchronized State snapshot() { requireHealthy(); return state; }

    /** 精确读取渠道任务，不存在时返回 null。 */
    public synchronized Task task(String id) {
        requireHealthy();
        return state.tasks().stream().filter(task -> task.id().equals(id)).findFirst().orElse(null);
    }

    /** 成功返回队列前方数量；重复受理返回 -1，持久化失败则抛出异常而不是回执成功。 */
    public synchronized int accept(Task task, int maxQueued, long now) {
        requireHealthy();
        if (task(task.id()) != null) return -1;
        List<Task> retained = state.tasks().stream().filter(existing -> !canPrune(existing, now)).toList();
        long waiting = retained.stream().filter(t -> t.state() == Execution.QUEUED).count();
        long ahead = retained.stream().filter(t -> t.conversation().equals(task.conversation()) && t.state().active()).count();
        long inQueue = retained.stream().filter(t -> t.conversation().equals(task.conversation())
                && t.state() == Execution.QUEUED).count();
        if ((task.state() == Execution.QUEUED && (waiting >= MAX_QUEUED || inQueue >= maxQueued
                || retained.size() >= MAX_RECORDS - RESERVED_CONTROL_RECORDS))
                || retained.size() >= MAX_RECORDS) {
            throw new IllegalStateException("当前待处理消息较多，请稍后重试。");
        }
        List<Task> tasks = new ArrayList<>(retained); tasks.add(task);
        commit(new State(1, state.bindings(), tasks));
        return (int) ahead;
    }

    /** 串行变更一个任务并原子提交，调用方变换不得产生外部副作用。 */
    public synchronized void update(String id, UnaryOperator<Task> change) {
        requireHealthy();
        List<Task> tasks = new ArrayList<>(state.tasks());
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(id)) {
                tasks.set(i, change.apply(tasks.get(i)));
                commit(new State(1, state.bindings(), tasks));
                return;
            }
        }
        throw new IllegalStateException("飞书任务不存在");
    }

    /** 只保存渠道到通用会话的绑定，不在本模块持久化聊天历史。 */
    public synchronized void bind(String key, String sessionId) {
        requireHealthy();
        Map<String, String> bindings = new LinkedHashMap<>(state.bindings());
        if (!bindings.containsKey(key) && bindings.size() >= MAX_RECORDS) throw new IllegalStateException("渠道会话数量已达上限");
        bindings.put(key, sessionId);
        commit(new State(1, bindings, state.tasks()));
    }

    /** 用于受理／出站的快速熔断判断，不等同于飞书网络健康。 */
    public synchronized boolean healthy() { return healthy && !closed; }

    private void commit(State next) {
        Path temporary = directory.resolve("state.json.tmp");
        try {
            validate(next);
            byte[] bytes = mapper.writeValueAsBytes(next);
            if (bytes.length > MAX_BYTES) throw new IOException("渠道账本容量不足");
            try (FileChannel output = FileChannel.open(temporary, CREATE, TRUNCATE_EXISTING, WRITE)) {
                secure(temporary, false);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            // 不支持原子替换的文件系统必须拒绝工作，不退化成可能写坏账本的覆盖操作。
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // POSIX 文件系统同时刷入目录元数据，使原子替换在进程／主机崩溃后更可靠。
            // Windows 不支持目录 FileChannel，依赖原子替换和文件系统日志，不宣称断电零丢失。
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                try (FileChannel parent = FileChannel.open(directory, READ)) { parent.force(true); }
            }
            state = next;
        } catch (IOException | RuntimeException exception) {
            healthy = false;
            log.error("飞书账本写入失败，已停止受理及投递：errorType={}", exception.getClass().getSimpleName());
            throw new IllegalStateException("飞书本地状态保存失败，请检查磁盘和数据目录权限后重启。");
        }
    }

    private static void validate(State state) throws IOException {
        if (state == null || state.version() != 1 || state.tasks().size() > MAX_RECORDS
                || state.bindings().size() > MAX_RECORDS) throw new IOException("账本格式不受支持");
        Set<String> ids = new HashSet<>();
        for (Task task : state.tasks()) {
            if (task.id() == null || !ids.add(task.id()) || task.conversation() == null
                    || task.runId() == null || task.turnId() == null || task.state() == null || task.chatId() == null) {
                throw new IOException("账本任务格式无效");
            }
            Set<String> partIds = new HashSet<>();
            for (Part part : task.parts()) {
                if (part.id() == null || !partIds.add(part.id()) || part.text() == null || part.status() == null
                        || part.attempts() < 0) throw new IOException("账本投递格式无效");
            }
        }
    }

    private static boolean canPrune(Task task, long now) {
        return !task.state().active() && task.finishedAt() > 0 && now - task.finishedAt() > RETENTION_MS
                && task.parts().stream().allMatch(part -> part.status() == Delivery.SENT);
    }

    private void requireHealthy() {
        if (!healthy || closed) throw new IllegalStateException("飞书渠道账本不可用");
    }

    private static void secure(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        lock.release(); lockChannel.close();
    }

    /** 执行终态与最终回复投递状态分离。 */
    public enum Execution {
        QUEUED, RUNNING, STOPPING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED, EXPIRED, CLEARED;
        public boolean active() { return this == QUEUED || this == RUNNING || this == STOPPING; }
    }

    public enum Delivery { PENDING, SENDING, RETRY_WAIT, SENT, FAILED, UNKNOWN }

    @Builder(toBuilder = true)
    public record Part(String id, String text, Delivery status, int attempts, long firstAttemptAt,
                       long retryAt, String messageId, String errorCode) { }

    /** 渠道元数据及待投递快照；不替代通用会话和 transcript。 */
    @Builder(toBuilder = true)
    public record Task(String id, String conversation, String chatId, String replyTo, String senderId,
                       String sessionId, String runId, String turnId, String input, Execution state,
                       long acceptedAt, long startedAt, long nextHeartbeatAt, long finishedAt,
                       String stage, List<Part> parts) {
        public Task { parts = parts == null ? List.of() : List.copyOf(parts); }
        @Override public String toString() { return "FeishuTask[id=" + id + ", state=" + state + "]"; }
    }

    public record State(int version, Map<String, String> bindings, List<Task> tasks) {
        public State {
            bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
            tasks = List.copyOf(tasks);
        }
    }

    /** 稳定 UUID 仅用于关联／平台幂等，不作为认证信息。 */
    public static String stableId(String input) {
        return UUID.nameUUIDFromBytes(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
