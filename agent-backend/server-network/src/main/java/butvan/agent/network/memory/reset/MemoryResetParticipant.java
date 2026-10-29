package butvan.agent.network.memory.reset;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.reset.ResetParticipant;
import butvan.agent.agents.reset.ResetScopeException;
import butvan.agent.network.memory.client.MemoryRemoteGateway;
import butvan.agent.network.memory.repository.MemoryResetRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/** 必需的记忆重置参与方；未知结果与缺失接收方均保留目标及全局保护屏障。 */
@Slf4j
@Component
public class MemoryResetParticipant implements ResetParticipant {
    private final MemoryResetRepository repository;
    private final ResetBarrier barrier;
    private final MemoryRemoteGateway remote;
    private final TransactionTemplate transaction;
    private final String environment;

    public MemoryResetParticipant(MemoryResetRepository repository, ResetBarrier barrier, MemoryRemoteGateway remote,
                                  PlatformTransactionManager manager, @Value("${butvan.memory.environment:desktop}") String environment) {
        this.repository = repository; this.barrier = barrier; this.remote = remote;
        this.transaction = new TransactionTemplate(manager); this.environment = environment;
    }
    @Override public String id() { return "experience-memory"; }
    @Override public String scopeVersion(String owner) { return "memory-m2-v3:" + remote.recipientFingerprint(); }
    @Override public List<String> additionalDeletes(String owner) {
        return List.of("经历记忆授权、来源资格、本域纠正正文、管理与远端操作账本、过滤计数和运行保护；已登记远端目标及其派生结果（未核对完成时保持保护）");
    }
    @Override public String remoteStatus(String owner, String resetId) {
        return repository.statusForOwner(owner, environment, resetId);
    }
    @Override public void stop(String owner) throws ResetScopeException {
        requireReset(owner);
        String resetId = barrier.state().resetId();
        Boolean authorized = transaction.execute(status -> {
            String space = repository.prepare(owner, environment, resetId, configured());
            if (barrier.state().scopeVersion() == null && !repository.pending(space, resetId).isEmpty()) {
                repository.status(space, resetId, "UNKNOWN");
                return false;
            }
            return true;
        });
        if (!Boolean.TRUE.equals(authorized)) throw new ResetScopeException("旧重置未确认远端清理范围，请先核实范围；目标和保护屏障已保留。");
    }
    @Override public void clean(String owner) throws ResetScopeException {
        requireReset(owner);
        String resetId = barrier.state().resetId();
        String space = transaction.execute(status -> repository.prepare(owner, environment, resetId, configured()));
        while (true) {
            var targets = repository.pending(space, resetId);
            if (targets.isEmpty()) break;
            for (var target : targets) {
                if (!configured() || !target.recipientFingerprint().equals(remote.recipientFingerprint())) {
                    failed(space, resetId, target, "FAILED");
                    throw new ResetScopeException("远端记忆接收方不可用或已变化，请恢复原连接后继续核对；目标和保护屏障已保留。");
                }
                repository.status(space, resetId, "PROCESSING");
                MemoryRemoteGateway.DeletionResult result;
                try { result = remote.deleteAndVerify(target); }
                catch (RuntimeException exception) {
                    log.warn("记忆远端清理结果未知：errorType={}", exception.getClass().getSimpleName());
                    failed(space, resetId, target, "UNKNOWN");
                    throw new ResetScopeException("远端记忆清理调用未完成，删除证据仍未知；请核实服务状态，目标和保护屏障已保留。");
                }
                if (result != MemoryRemoteGateway.DeletionResult.VERIFIED) {
                    String state = result == MemoryRemoteGateway.DeletionResult.FAILED ? "FAILED" : "UNKNOWN";
                    failed(space, resetId, target, state);
                    // 使用既有可展示的范围异常，只输出固定提示，避免协调器误报为本地磁盘权限问题。
                    throw new ResetScopeException(state.equals("UNKNOWN")
                            ? "远端记忆删除缺少完整核对证据；重复重试或重启不能代替证据，请先核实记忆服务清理能力。目标和保护屏障已保留。"
                            : "远端记忆删除核对失败，请核实服务状态后继续原任务；目标和保护屏障已保留。");
                }
                repository.targetState(target, "VERIFIED");
            }
        }
        transaction.executeWithoutResult(status -> repository.finish(space, resetId));
    }
    private boolean configured() { return remote.recipientFingerprint() != null && !remote.recipientFingerprint().isBlank(); }
    private void requireReset(String owner) {
        if (!barrier.blocked() || barrier.state().resetId() == null || !owner.equals(barrier.state().owner()))
            throw new IllegalStateException("记忆清理必须由当前重置任务协调");
    }
    private void failed(String space, String resetId, MemoryRemoteGateway.Target target, String state) {
        transaction.executeWithoutResult(status -> {
            repository.targetState(target, state);
            repository.status(space, resetId, state);
        });
    }
}
