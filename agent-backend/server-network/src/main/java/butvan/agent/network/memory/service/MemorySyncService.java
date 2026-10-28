package butvan.agent.network.memory.service;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.reset.*;
import butvan.agent.agents.session.*;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import butvan.agent.network.memory.client.*;
import butvan.agent.network.memory.repository.MemorySyncRepository;
import butvan.agent.network.memory.repository.MemorySyncRepository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** 有界补偿和可测试 Worker；当前不注册定时器，只有显式驱动与假写入 Adapter。 */
@Slf4j
@Service
public class MemorySyncService {
    private final MemoryService memory;
    private final MemorySyncRepository repository;
    private final MemorySourceVerifier verifier;
    private final CurrentUserProvider user;
    private final ResetBarrier barrier;
    private final MemoryWriteGateway writer;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String environment;

    public MemorySyncService(MemoryService memory, MemorySyncRepository repository, TranscriptService transcript,
                             SessionCatalogService sessions, CurrentUserProvider user, ResetBarrier barrier,
                             MemoryWriteGateway writer, PlatformTransactionManager manager, Clock automationClock,
                             @Value("${butvan.memory.environment:desktop}") String environment) {
        this.memory = memory; this.repository = repository; this.verifier = new MemorySourceVerifier(transcript, sessions);
        this.user = user; this.barrier = barrier; this.writer = writer; this.transaction = new TransactionTemplate(manager);
        this.clock = automationClock; this.environment = environment;
    }

    /** 终态通知只尝试本地任务，不等待远端；实际状态始终再次核对 transcript。 */
    public void completed(String owner, String session, String turn) {
        if (!Objects.equals(owner, user.currentUserId()) || !memory.remoteAvailable()) return;
        guarded(() -> {
            var source = repository.source(owner, environment, session, turn);
            source.ifPresent(this::reconcileSource);
            return null;
        });
    }

    /** 每次最多检查 20 份已登记凭据，不枚举任何未获准会话。 */
    public int reconcile(int requestedLimit) {
        int limit = limit(requestedLimit);
        return guarded(() -> {
            var state = memory.state();
            if (!state.authorized() || !state.settings().autoSave()) return 0;
            var rows = transaction.execute(status -> repository.candidates(state.settings().spaceId(), limit));
            rows.forEach(this::reconcileSource);
            return rows.size();
        });
    }

    /** 显式的一批消费入口；关闭写入 Adapter 时不读原文、不调用任何网络。 */
    public int runOnce(int requestedLimit) {
        int limit = limit(requestedLimit);
        if (writer.recipientFingerprint() == null || writer.recipientFingerprint().isBlank()) return 0;
        return guarded(() -> {
            var state = memory.state();
            if (!state.authorized() || !state.settings().autoSave()
                    || !state.recipientFingerprint().equals(writer.recipientFingerprint())) return 0;
            reconcile(limit);
            var operations = repository.due(state.settings().spaceId(), clock.millis(), limit);
            for (var operation : operations) process(operation, state.grant());
            return operations.size();
        });
    }

    /** 管理／测试的有界任务快照，正文不在返回对象内。 */
    public List<Operation> operations() {
        return guarded(() -> repository.operations(memory.settings().spaceId()));
    }

    private void reconcileSource(SourceRow source) {
        if (!current(source)) { invalidate(source); return; }
        var turn = verifier.read(source);
        if (turn == null) { invalidate(source); return; }
        if (turn.assistant() == null) return;
        if (turn.assistant().status() != MessageStatus.COMPLETED) { invalidate(source); return; }
        transaction.executeWithoutResult(status -> {
            if (current(source)) repository.enqueue(source);
        });
    }

    private void process(Operation operation, butvan.agent.network.memory.model.MemoryModels.Grant grant) {
        // 只读查询允许接管过期 claim；旧生产者仍持发送锁，删除须等待其真实退出。
        if (List.of("SUBMITTED", "UNKNOWN", "CLAIMED").contains(operation.state())) {
            processLocked(operation, grant);
            return;
        }
        var lock = memory.sourceOperationLock(operation.sourceId());
        if (!lock.tryLock()) return;
        try { processLocked(operation, grant); }
        finally { lock.unlock(); }
    }

    private void processLocked(Operation operation, butvan.agent.network.memory.model.MemoryModels.Grant grant) {
        var candidate = repository.source(operation.sourceId());
        if (candidate.isEmpty()) return;
        SourceRow source = candidate.get();
        if (!current(source)) { invalidate(source); return; }
        var turn = verifier.read(source);
        if (turn == null || turn.assistant() == null || turn.assistant().status() != MessageStatus.COMPLETED) {
            invalidate(source); return;
        }
        boolean query = List.of("SUBMITTED", "UNKNOWN", "CLAIMED").contains(operation.state());
        String day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        var token = transaction.execute(status -> repository.claim(operation, source.space(), day, clock.millis(),
                query ? grant.queryLimit() : grant.writeLimit(), query));
        if (token.isEmpty()) return;
        if ((query && operation.queries() >= 8) || (!query && operation.attempts() >= 3)) {
            repository.finish(operation.id(), token.get(), query ? "UNKNOWN" : "FAILED", Long.MAX_VALUE, "ATTEMPT_LIMIT");
            return;
        }
        if (!current(source) || !source.recipient().equals(writer.recipientFingerprint())) {
            repository.finish(operation.id(), token.get(), "CANCELLED", Long.MAX_VALUE, "AUTHORIZATION_CHANGED");
            return;
        }
        var target = new MemoryRemoteGateway.Target(source.space(), source.document(), source.recipient(), operation.id());
        MemoryWriteGateway.Outcome outcome;
        try {
            // 写入前登记目标，之后即使超时／撤权也有可清理的稳定指针。
            memory.recordRemoteOperation(source.receipt(), operation.id());
            if (!current(source)) {
                repository.finish(operation.id(), token.get(), "CANCELLED", Long.MAX_VALUE, "AUTHORIZATION_CHANGED");
                return;
            }
            var latest = verifier.read(source);
            if (latest == null || latest.assistant() == null || latest.assistant().status() != MessageStatus.COMPLETED) {
                invalidate(source); return;
            }
            outcome = query ? writer.query(operation.id(), target, () -> !current(source))
                    : writer.submit(new MemoryWriteGateway.Write(operation.id(), target, latest.user().content(), source.occurredAt(), source.channel()),
                    () -> !current(source));
        } catch (RuntimeException exception) {
            log.warn("经历同步结果未知：errorType={}", exception.getClass().getSimpleName());
            outcome = MemoryWriteGateway.Outcome.UNKNOWN;
        }
        if (!current(source)) {
            repository.finish(operation.id(), token.get(), "CANCELLED", Long.MAX_VALUE, "AUTHORIZATION_CHANGED");
            return;
        }
        String state = switch (outcome == null ? MemoryWriteGateway.Outcome.UNKNOWN : outcome) {
            case SUCCEEDED -> "SUCCEEDED";
            case ACCEPTED -> "SUBMITTED";
            case FAILED -> "FAILED";
            case NOT_SUBMITTED_RETRYABLE -> query ? "UNKNOWN" : "RETRY_WAIT";
            case UNKNOWN -> "UNKNOWN";
        };
        long next = clock.millis() + Math.min(15_000, 1_000L << Math.min(4, operation.attempts() + operation.queries()));
        if ((!query && operation.attempts() + 1 >= 3 && state.equals("RETRY_WAIT"))) state = "FAILED";
        if (query && operation.queries() + 1 >= 8 && List.of("SUBMITTED", "UNKNOWN").contains(state)) {
            state = "UNKNOWN"; next = Long.MAX_VALUE;
        }
        repository.finish(operation.id(), token.get(), state, next, state.equals("UNKNOWN") ? "REMOTE_UNKNOWN" : "");
    }

    private boolean current(SourceRow source) {
        try { return memory.isCurrent(source.receipt()); }
        catch (ResetBlockedException exception) { return false; }
    }
    private void invalidate(SourceRow source) { transaction.executeWithoutResult(status -> repository.invalidate(source.id())); }
    private int limit(int value) {
        if (value < 1 || value > 20) throw new MemoryRequestException(400, "每批记忆处理数量须为 1—20");
        return value;
    }
    private <T> T guarded(Supplier<T> body) {
        try (var ignored = barrier.enter()) { return body.get(); }
    }
}
