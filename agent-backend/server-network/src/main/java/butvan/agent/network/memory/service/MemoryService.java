package butvan.agent.network.memory.service;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.memory.MemorySourceRegistry;
import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.network.memory.client.MemoryRemoteGateway;
import butvan.agent.network.memory.model.MemoryModels.*;
import butvan.agent.network.memory.repository.MemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 授权与来源资格的唯一应用入口；租约包含整个数据库提交，不负责聊天接线或外发。 */
@Slf4j
@Service
public class MemoryService implements MemorySourceRegistry {
    private final MemoryRepository repository;
    private final ResetBarrier barrier;
    private final CurrentUserProvider user;
    private final Clock clock;
    private final MemoryRemoteGateway remote;
    private final TransactionTemplate transaction;
    private final String environment;
    // 固定数量的进程内条带锁：发送与管理删除串行，避免无界锁注册表。
    private final java.util.concurrent.locks.ReentrantLock[] sourceLocks = java.util.stream.IntStream.range(0, 256)
            .mapToObj(i -> new java.util.concurrent.locks.ReentrantLock()).toArray(java.util.concurrent.locks.ReentrantLock[]::new);

    /** 同一来源的外部副作用必须共用此锁；只 tryLock，调用方可保留等待状态。 */
    public java.util.concurrent.locks.ReentrantLock sourceOperationLock(String sourceId) {
        return sourceLocks[Math.floorMod(sourceId.hashCode(), sourceLocks.length)];
    }

    public MemoryService(MemoryRepository repository, ResetBarrier barrier, CurrentUserProvider user, Clock automationClock,
                         MemoryRemoteGateway remote, PlatformTransactionManager manager,
                         @Value("${butvan.memory.environment:desktop}") String environment) {
        this.repository = repository; this.barrier = barrier; this.user = user; this.clock = automationClock;
        this.remote = remote; this.transaction = new TransactionTemplate(manager);
        if (environment == null || !environment.matches("[a-zA-Z0-9_-]{1,40}"))
            throw new IllegalArgumentException("记忆环境标识无效");
        this.environment = environment;
    }

    /** 只返回当前用户的内部设置快照；HTTP 层另外映射 DTO。 */
    public Settings settings() {
        return guarded(null, () -> { String space = space(); currentGrant(space); return repository.settings(space); });
    }
    /** 接收方变更会持久化撤销，不因切回旧地址而自动恢复旧授权。 */
    public Grant grant() { return guarded(null, () -> currentGrant(space())); }
    /** 仅表示存在受信 Adapter，不执行网络健康检查。 */
    public boolean remoteAvailable() { return !fingerprint().isEmpty(); }

    /** 管理页使用同一事务的权威快照，接收方变化时不把旧授权显示为有效。 */
    public State state() {
        return guarded(null, () -> {
            String space = space();
            Grant grant = currentGrant(space);
            String recipient = fingerprint();
            return new State(repository.settings(space), grant, recipient,
                    grant.active() && !recipient.isEmpty() && recipient.equals(grant.fingerprint()));
        });
    }

    /** 设置开关不产生新授权；扩大到未获准用途时原子拒绝。 */
    public Settings saveSettings(long expectedVersion, boolean save, boolean recall) {
        if (expectedVersion < 0) throw new MemoryRequestException(400, "记忆设置版本无效");
        return guarded(null, () -> {
            String space = space();
            Grant grant = currentGrant(space);
            if ((save && !allowed(grant, true)) || (recall && !allowed(grant, false)))
                throw new MemoryRequestException(409, "请先确认当前接收方及对应记忆用途授权");
            repository.settings(space, expectedVersion, save, recall);
            return repository.settings(space);
        });
    }

    /** 用户确认只对服务端受信接收方生效；幂等重放不重新授权。 */
    public AuthorizationResult authorize(Authorization input) {
        validate(input);
        return guarded(null, () -> {
            String space = space();
            String hash = hash(input.expectedRevision() + ":" + input.recipientFingerprint() + ":" + input.allowSave()
                    + ":" + input.allowRecall() + ":" + new TreeSet<>(input.channels()) + ":" + input.retentionDays()
                    + ":" + input.dailyWriteLimit() + ":" + input.dailyQueryLimit() + ":" + input.policyVersion()
                    + ":" + input.recallMode() + ":" + input.continuousProcessing() + ":" + input.acknowledgeSourceImpact());
            var previous = replay(space, input.requestId(), "AUTHORIZE", hash);
            if (previous.isPresent()) return previous.get();
            if (!input.recipientFingerprint().equals(fingerprint()) || !remoteAvailable())
                throw new MemoryRequestException(409, "远端记忆尚未接入或接收方已变化");
            Instant now = clock.instant();
            repository.authorize(space, input, now);
            var result = new AuthorizationResult(input.expectedRevision() + 1, now);
            repository.command(space, input.requestId(), "AUTHORIZE", hash, result);
            return result;
        });
    }

    /** 撤权保留远端删除目标，重新授权不会恢复旧来源。 */
    public AuthorizationResult revoke(long expectedRevision, String requestId) {
        if (expectedRevision < 0) throw new MemoryRequestException(400, "记忆授权版本无效");
        requestId(requestId);
        return guarded(null, () -> {
            String space = space();
            String hash = hash(Long.toString(expectedRevision));
            var previous = replay(space, requestId, "REVOKE", hash);
            if (previous.isPresent()) return previous.get();
            repository.revoke(space, expectedRevision);
            Settings settings = repository.settings(space);
            repository.settings(space, settings.version(), false, false);
            var result = new AuthorizationResult(expectedRevision + 1, clock.instant());
            repository.command(space, requestId, "REVOKE", hash, result);
            return result;
        });
    }

    /** 供 Worker／召回解析本次配额类别；V1 旧授权仍共用合并查询上限。 */
    public Limits limits(Grant grant) {
        return guarded(null, () -> Limits.of(grant, repository.runtimePolicy(space())));
    }

    /** 运行保护快照；读取本身不探测远端或启用任何开关。 */
    public RuntimePolicy runtimePolicy() {
        return guarded(null, () -> repository.runtimePolicy(space()));
    }

    /** 只改节流参数；不产生新授权，也不使已登记来源失效。 */
    public RuntimePolicy saveRuntimePolicy(long expectedVersion, int batchSize, int writeLimit, int statusLimit, int recallLimit) {
        if (expectedVersion < 0 || batchSize < 1 || batchSize > 20 || writeLimit < 1 || writeLimit > 100_000
                || statusLimit < 1 || statusLimit > 100_000 || recallLimit < 1 || recallLimit > 100_000)
            throw new MemoryRequestException(400, "请填写有效的运行批次与每日调用保护（1—100000）。");
        return guarded(null, () -> repository.runtimePolicy(space(), expectedVersion, batchSize, writeLimit, statusLimit, recallLimit));
    }

    /** 仅记录已授权空间被过滤的原因计数；不保存正文，也不阻断聊天。 */
    public void skip(MemorySourceRegistry.Channel channel, String reason) {
        if (channel == null || reason == null || !reason.matches("[A-Z_]{1,40}")) return;
        try {
            guarded(null, () -> {
                String space = space();
                Grant grant = currentGrant(space);
                if (!repository.settings(space).autoSave() || !allowed(grant, true) || !grant.channels().contains(channel)) return null;
                repository.recordSkip(space, clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(), reason);
                return null;
            });
        } catch (RuntimeException exception) {
            log.info("记忆过滤计数未保存：errorType={}", exception.getClass().getSimpleName());
        }
    }

    @Override public Optional<Receipt> capture(Source source) {
        validate(source);
        return guarded(source.generation(), () -> {
            String space = space();
            Grant grant = currentGrant(space);
            if (!repository.settings(space).autoSave() || !allowed(grant, true)
                    || !grant.channels().contains(source.channel()) || source.occurredAt().isBefore(grant.effectiveAt())
                    || source.occurredAt().isAfter(clock.instant())) return Optional.empty();
            return repository.capture(space, source, grant);
        });
    }

    @Override public boolean isCurrent(Receipt receipt) {
        if (receipt == null || !barrier.isCurrent(receipt.generation())) return false;
        return guarded(receipt.generation(), () -> {
            String space = space();
            Grant grant = currentGrant(space);
            return space.equals(receipt.spaceId()) && grant.revision() == receipt.authorizationRevision()
                    && allowed(grant, true) && repository.settings(space).autoSave() && repository.current(receipt, space);
        });
    }

    /** 供后续 Worker 在发送前登记稳定操作 ID；不发送，也不能替代发送瞬间的授权校验。 */
    public void recordRemoteOperation(Receipt receipt, String operationId) {
        if (receipt == null || operationId == null || !operationId.matches("[a-zA-Z0-9_-]{1,128}"))
            throw new MemoryRequestException(400, "记忆操作标识无效");
        guarded(receipt.generation(), () -> {
            String space = space();
            Grant grant = currentGrant(space);
            if (!space.equals(receipt.spaceId()) || grant.revision() != receipt.authorizationRevision()
                    || !allowed(grant, true) || !repository.settings(space).autoSave() || !repository.current(receipt, space))
                throw new MemoryRequestException(409, "记忆来源资格已失效");
            repository.recordRemoteOperation(receipt, operationId);
            return null;
        });
    }

    private boolean allowed(Grant grant, boolean save) {
        return grant.active() && !fingerprint().isEmpty() && grant.fingerprint().equals(fingerprint())
                && (save ? grant.save() : grant.recall());
    }
    private Grant currentGrant(String space) {
        Grant grant = repository.grant(space);
        if (grant.active() && !grant.fingerprint().equals(fingerprint())) {
            repository.revoke(space, grant.revision());
            Settings settings = repository.settings(space);
            repository.settings(space, settings.version(), false, false);
            return repository.grant(space);
        }
        return grant;
    }
    private String fingerprint() { return Objects.requireNonNullElse(remote.recipientFingerprint(), ""); }
    private String space() { return repository.space(user.currentUserId(), environment); }

    /** 租约在数据库事务提交后释放；HTTP 代次检查由统一 AOP 提供。 */
    private <T> T guarded(Long generation, Supplier<T> body) {
        try (var ignored = barrier.enter(generation)) {
            // 接收方变化是独立的撤销事实，不应随随后发生的业务冲突一起回滚。
            transaction.executeWithoutResult(status -> currentGrant(space()));
            return transaction.execute(status -> body.get());
        }
    }

    private Optional<AuthorizationResult> replay(String space, String request, String kind, String hash) {
        return repository.command(space, request).map(command -> {
            if (!kind.equals(command.kind()) || !hash.equals(command.payloadHash()))
                throw new MemoryRequestException(409, "请求 ID 已用于另一项记忆操作");
            return new AuthorizationResult(command.revision(), command.effectiveAt());
        });
    }

    private void validate(Authorization input) {
        if (input == null || !input.confirmed() || input.expectedRevision() < 0
                || input.recipientFingerprint() == null || !input.recipientFingerprint().matches("[a-zA-Z0-9_-]{1,128}")
                || (!input.allowSave() && !input.allowRecall()) || input.channels() == null || input.channels().isEmpty()
                || input.channels().stream().anyMatch(Objects::isNull))
            throw new MemoryRequestException(400, "请确认有效的记忆用途与渠道。");
        requestId(input.requestId());
        if (input.v2()) validateV2(input);
        else validateV1(input);
    }

    /** V1 保持原有限期与自授权日额度，升级本身不扩大旧确认内容。 */
    private void validateV1(Authorization input) {
        if (input.retentionDays() < 1 || input.retentionDays() > 3650
                || input.dailyWriteLimit() < 1 || input.dailyWriteLimit() > 1000
                || input.dailyQueryLimit() < 1 || input.dailyQueryLimit() > 1000)
            throw new MemoryRequestException(400, "请确认有效的保留期限和调用上限。");
    }

    /** V2 必须显式选择召回模式、确认持续后台处理与旧来源失效，且不得混入 V1 日额度。 */
    private void validateV2(Authorization input) {
        if (!input.continuousProcessing() || !input.acknowledgeSourceImpact())
            throw new MemoryRequestException(400, "请确认持续后台处理及已有来源不能自动沿用。");
        if (input.dailyWriteLimit() != 0 || input.dailyQueryLimit() != 0)
            throw new MemoryRequestException(400, "长期召回授权不包含每日记忆条数上限，请在运行保护中调整。");
        if (input.recallMode() == RecallMode.ALL_AUTHORIZED) {
            if (input.retentionDays() != 0)
                throw new MemoryRequestException(400, "长期召回不再按统一保留天数排除来源。");
            return;
        }
        if (input.retentionDays() < 1 || input.retentionDays() > 3650)
            throw new MemoryRequestException(400, "请填写 1—3650 天的召回时间范围。");
    }
    private void validate(Source source) {
        if (source == null || !Objects.equals(source.owner(), user.currentUserId()) || source.generation() < 0
                || source.channel() == null || source.occurredAt() == null || source.contentHash() == null
                || !source.contentHash().matches("[a-f0-9]{64}"))
            throw new MemoryRequestException(400, "记忆来源无效");
        for (String id : new String[]{source.sessionId(), source.turnId(), source.messageId()})
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,128}")) throw new MemoryRequestException(400, "记忆来源标识无效");
    }
    private void requestId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}")) throw new MemoryRequestException(400, "记忆请求 ID 无效");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("系统缺少摘要算法"); }
    }
}
