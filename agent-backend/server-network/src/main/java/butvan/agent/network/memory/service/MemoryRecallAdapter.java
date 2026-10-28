package butvan.agent.network.memory.service;

import butvan.agent.agents.context.PersonalContextService;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.memory.*;
import butvan.agent.agents.reset.*;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import butvan.agent.network.memory.client.MemoryRecallGateway;
import butvan.agent.network.memory.model.MemoryModels.State;
import butvan.agent.network.memory.repository.*;
import butvan.agent.network.memory.repository.MemorySyncRepository.SourceRow;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 有界远端召回 Adapter：独立查询授权、来源复核、取消期限与本轮有效性，不负责信封预算。 */
@Slf4j
@Component
public class MemoryRecallAdapter implements MemoryRecallPort {
    private final MemoryService memory;
    private final MemoryRecallGateway gateway;
    private final MemoryRecallRepository repository;
    private final MemorySyncRepository sources;
    private final MemorySourceVerifier verifier;
    private final PersonalContextService profiles;
    private final ResetBarrier barrier;
    private final CurrentUserProvider user;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final Duration timeout;
    private final Semaphore permits = new Semaphore(2);
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Autowired
    public MemoryRecallAdapter(MemoryService memory, MemoryRecallGateway gateway, MemoryRecallRepository repository,
                               MemorySyncRepository sources, MemorySourceVerifier verifier, PersonalContextService profiles,
                               ResetBarrier barrier, CurrentUserProvider user, Clock automationClock, PlatformTransactionManager manager) {
        this(memory, gateway, repository, sources, verifier, profiles, barrier, user, automationClock, manager, Duration.ofMillis(1500));
    }
    /** 显式期限供隔离测试；生产统一为 1500ms，不提供用户任意外发地址。 */
    public MemoryRecallAdapter(MemoryService memory, MemoryRecallGateway gateway, MemoryRecallRepository repository,
                               MemorySyncRepository sources, MemorySourceVerifier verifier, PersonalContextService profiles,
                               ResetBarrier barrier, CurrentUserProvider user, Clock clock, PlatformTransactionManager manager, Duration timeout) {
        this.memory = memory; this.gateway = gateway; this.repository = repository; this.sources = sources; this.verifier = verifier;
        this.profiles = profiles; this.barrier = barrier; this.user = user; this.clock = clock;
        this.transaction = new TransactionTemplate(manager); this.timeout = timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMillis(1500)) > 0)
            throw new IllegalArgumentException("召回期限无效");
    }

    @Override public Result recall(Request request) {
        if (closed.get()) return Result.empty(Status.UNAVAILABLE);
        String recipient = Objects.requireNonNullElse(gateway.recipientFingerprint(), "");
        if (recipient.isBlank() && !memory.remoteAvailable()) return Result.empty(Status.NOT_CONFIGURED);
        if (request == null || !Objects.equals(request.owner(), user.currentUserId()) || request.channel() == null
                || !MemorySourcePolicy.eligibleText(request.query())) return Result.empty(Status.NOT_AUTHORIZED);
        if (request.cancelled().getAsBoolean()) return Result.empty(Status.CANCELLED);
        if (!permits.tryAcquire()) return Result.empty(Status.UNAVAILABLE);
        long generation = barrier.state().generation();
        long deadline = System.nanoTime() + timeout.toNanos();
        var cancelled = new AtomicBoolean();
        var result = new CompletableFuture<Result>();
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            // 权限读取、外部调用与来源核对均在期限内；真实生产者结束前不释放租约和名额。
            try (var ignored = barrier.enter(generation)) {
                result.complete(perform(request, recipient, generation, deadline, cancelled));
            } catch (ResetBlockedException exception) { result.complete(Result.empty(Status.CANCELLED)); }
            catch (RuntimeException exception) {
                log.warn("经历召回降级：errorType={}", exception.getClass().getSimpleName());
                result.complete(Result.empty(Status.UNAVAILABLE));
            } finally { permits.release(); workers.remove(Thread.currentThread()); }
        });
        workers.add(worker);
        try { worker.start(); }
        catch (RuntimeException exception) { workers.remove(worker); permits.release(); throw exception; }
        try {
            while (true) {
                if (request.cancelled().getAsBoolean() || closed.get()) return Result.empty(Status.CANCELLED);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return Result.empty(Status.TIMEOUT);
                try {
                    var completed = result.get(Math.min(remaining, 20_000_000), TimeUnit.NANOSECONDS);
                    if (request.cancelled().getAsBoolean() || closed.get()) return Result.empty(Status.CANCELLED);
                    if (System.nanoTime() >= deadline) return Result.empty(Status.TIMEOUT);
                    return completed;
                } catch (TimeoutException exception) { /* 短等候后重新检查显式取消和总期限。 */ }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); return Result.empty(Status.CANCELLED);
        } catch (ExecutionException exception) {
            log.warn("经历召回任务失败：errorType={}", exception.getCause().getClass().getSimpleName());
            return Result.empty(Status.UNAVAILABLE);
        } finally { cancelled.set(true); if (worker.isAlive()) worker.interrupt(); }
    }

    private Result perform(Request request, String recipient, long generation, long deadline, AtomicBoolean cancelled) {
        State state = memory.state();
        if (!state.authorized() || !state.grant().recall() || !state.grant().channels().contains(request.channel()))
            return Result.empty(Status.NOT_AUTHORIZED);
        if (recipient.isBlank() || !recipient.equals(state.recipientFingerprint()) || !state.settings().autoRecall()
                || !profiles.get(request.owner()).enabled() || repository.deletionPending(state.settings().spaceId()))
            return Result.empty(Status.PAUSED);
        var scope = new Scope(request.owner(), state, generation);
        var control = new MemoryRecallGateway.Control(deadline, () -> cancelled.get() || closed.get()
                || request.cancelled().getAsBoolean() || !validScope(scope));
        if (control.isCancelled()) return Result.empty(Status.CANCELLED);
        boolean reserved = Boolean.TRUE.equals(transaction.execute(status -> repository.reserveQuery(state.settings().spaceId(),
                clock.instant().atZone(ZoneOffset.UTC).toLocalDate().toString(), state.grant().queryLimit())));
        if (!reserved) return Result.empty(Status.BUDGET_EXHAUSTED);
        int points = Math.min(1000, request.query().codePointCount(0, request.query().length()));
        String query = request.query().substring(0, request.query().offsetByCodePoints(0, points));
        var call = new MemoryRecallGateway.Query(state.settings().spaceId(), query, Math.min(500, request.tokenBudget()), Math.min(4, request.topK()));
        if (control.isCancelled()) return Result.empty(Status.CANCELLED);
        var candidates = gateway.recall(call, control);
        if (request.cancelled().getAsBoolean()) return Result.empty(Status.CANCELLED);
        if (System.nanoTime() >= deadline) return Result.empty(Status.TIMEOUT);
        if (!validScope(scope)) return Result.empty(Status.NOT_AUTHORIZED);
        if (candidates == null || candidates.size() > 16) return Result.empty(Status.INVALID_RESULT);
        var fragments = new ArrayList<Fragment>();
        for (var candidate : candidates) {
            if (fragments.size() >= Math.min(4, request.topK()) || control.isCancelled()) break;
            var evidence = evidence(candidate, scope);
            if (evidence.isEmpty()) continue;
            String source = "经历来源 " + evidence.stream().map(row -> row.id() + "@" + row.occurredAt()).reduce((x,y) -> x + "," + y).orElse("");
            fragments.add(new Fragment(source, "经历抽取参考，未经用户逐条确认：\n" + candidate.text(),
                    () -> validScope(scope) && evidence.stream().allMatch(row -> validEvidence(row, scope))));
        }
        if (request.cancelled().getAsBoolean()) return Result.empty(Status.CANCELLED);
        if (System.nanoTime() >= deadline) return Result.empty(Status.TIMEOUT);
        if (!validScope(scope)) return Result.empty(Status.NOT_AUTHORIZED);
        return new Result(fragments.isEmpty() ? (candidates.isEmpty() ? Status.EMPTY : Status.INVALID_RESULT) : Status.SUCCESS, fragments);
    }

    private List<SourceRow> evidence(MemoryRecallGateway.Candidate candidate, Scope scope) {
        if (candidate == null || candidate.text() == null || candidate.text().isBlank() || candidate.text().length() > 4000
                || !candidate.completeProvenance() || candidate.documentIds() == null || candidate.documentIds().isEmpty()
                || candidate.documentIds().size() > 4) return List.of();
        var rows = new ArrayList<SourceRow>();
        for (String document : new LinkedHashSet<>(candidate.documentIds())) {
            var id = repository.verifiedSource(scope.state.settings().spaceId(), document, scope.state.recipientFingerprint());
            if (id.isEmpty()) return List.of();
            var row = sources.source(id.get());
            if (row.isEmpty() || !validEvidence(row.get(), scope)) return List.of();
            rows.add(row.get());
        }
        return List.copyOf(rows);
    }
    private boolean validEvidence(SourceRow source, Scope scope) {
        try {
            if (source.generation() != scope.generation || source.revision() != scope.state.grant().revision()
                    || source.occurredAt().plus(Duration.ofDays(scope.state.grant().retentionDays())).isBefore(clock.instant())
                    || repository.verifiedSource(scope.state.settings().spaceId(), source.document(), scope.state.recipientFingerprint()).isEmpty()) return false;
            var turn = verifier.read(source);
            return turn != null && turn.assistant() != null && turn.assistant().status() == MessageStatus.COMPLETED;
        } catch (RuntimeException exception) {
            log.warn("召回来源核对失败：errorType={}", exception.getClass().getSimpleName()); return false;
        }
    }
    private boolean validScope(Scope scope) {
        try {
            if (!barrier.isCurrent(scope.generation) || closed.get() || !profiles.get(scope.owner).enabled()) return false;
            var state = memory.state();
            return state.authorized() && state.grant().recall() && state.settings().autoRecall()
                    && state.settings().spaceId().equals(scope.state.settings().spaceId())
                    && state.settings().version() == scope.state.settings().version()
                    && state.grant().revision() == scope.state.grant().revision()
                    && state.recipientFingerprint().equals(gateway.recipientFingerprint())
                    && !repository.deletionPending(state.settings().spaceId());
        } catch (RuntimeException exception) {
            log.debug("召回授权已不可读取：errorType={}", exception.getClass().getSimpleName()); return false;
        }
    }
    @PreDestroy public void close() { closed.set(true); workers.forEach(Thread::interrupt); }
    private record Scope(String owner, State state, long generation) { }
}
