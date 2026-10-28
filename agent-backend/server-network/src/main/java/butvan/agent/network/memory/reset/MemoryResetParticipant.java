package butvan.agent.network.memory.reset;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.reset.ResetParticipant;
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
    @Override public String scopeVersion(String owner) { return "memory-m2-v1:" + remote.recipientFingerprint(); }
    @Override public List<String> additionalDeletes(String owner) {
        return List.of("经历记忆授权、来源资格；已登记远端目标及其派生结果（未核对完成时保持保护）");
    }
    @Override public String remoteStatus(String owner, String resetId) {
        return repository.statusForOwner(owner, environment, resetId);
    }
    @Override public void stop(String owner) {
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
        if (!Boolean.TRUE.equals(authorized)) throw new IllegalStateException("旧重置未确认远端清理范围，保持保护");
    }
    @Override public void clean(String owner) {
        requireReset(owner);
        String resetId = barrier.state().resetId();
        String space = transaction.execute(status -> repository.prepare(owner, environment, resetId, configured()));
        while (true) {
            var targets = repository.pending(space, resetId);
            if (targets.isEmpty()) break;
            for (var target : targets) {
                if (!configured() || !target.recipientFingerprint().equals(remote.recipientFingerprint())) {
                    failed(space, resetId, target, "FAILED");
                    throw new IllegalStateException("远端接收方不可用，目标已保留");
                }
                repository.status(space, resetId, "PROCESSING");
                MemoryRemoteGateway.DeletionResult result;
                try { result = remote.deleteAndVerify(target); }
                catch (RuntimeException exception) {
                    log.warn("记忆远端清理结果未知：errorType={}", exception.getClass().getSimpleName());
                    failed(space, resetId, target, "UNKNOWN");
                    throw new IllegalStateException("远端清理结果未知，目标已保留");
                }
                if (result != MemoryRemoteGateway.DeletionResult.VERIFIED) {
                    String state = result == MemoryRemoteGateway.DeletionResult.FAILED ? "FAILED" : "UNKNOWN";
                    failed(space, resetId, target, state);
                    throw new IllegalStateException("远端清理未核对完成");
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
