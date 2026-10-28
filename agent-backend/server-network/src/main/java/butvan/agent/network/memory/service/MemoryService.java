package butvan.agent.network.memory.service;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.memory.MemorySourceRegistry;
import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.network.memory.client.MemoryRemoteGateway;
import butvan.agent.network.memory.model.MemoryModels.*;
import butvan.agent.network.memory.repository.MemoryRepository;
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
@Service
public class MemoryService implements MemorySourceRegistry {
    private final MemoryRepository repository;
    private final ResetBarrier barrier;
    private final CurrentUserProvider user;
    private final Clock clock;
    private final MemoryRemoteGateway remote;
    private final TransactionTemplate transaction;
    private final String environment;

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
    public Settings settings() { return guarded(null, () -> repository.settings(space())); }
    public Grant grant() { return guarded(null, () -> repository.grant(space())); }
    public boolean remoteAvailable() { return !fingerprint().isEmpty(); }

    public Settings saveSettings(long expectedVersion, boolean save, boolean recall) {
        return guarded(null, () -> {
            String space = space();
            Grant grant = repository.grant(space);
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
                    + ":" + input.dailyWriteLimit() + ":" + input.dailyQueryLimit());
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

    @Override public Optional<Receipt> capture(Source source) {
        validate(source);
        return guarded(source.generation(), () -> {
            String space = space();
            Grant grant = repository.grant(space);
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
            Grant grant = repository.grant(space);
            return space.equals(receipt.spaceId()) && grant.revision() == receipt.authorizationRevision()
                    && allowed(grant, true) && repository.settings(space).autoSave() && repository.current(receipt, space);
        });
    }

    private boolean allowed(Grant grant, boolean save) {
        return grant.active() && !fingerprint().isEmpty() && grant.fingerprint().equals(fingerprint())
                && (save ? grant.save() : grant.recall());
    }
    private String fingerprint() { return Objects.requireNonNullElse(remote.recipientFingerprint(), ""); }
    private String space() { return repository.space(user.currentUserId(), environment); }

    /** 租约在数据库事务提交后释放；HTTP 代次检查由统一 AOP 提供。 */
    private <T> T guarded(Long generation, Supplier<T> body) {
        try (var ignored = barrier.enter(generation)) { return transaction.execute(status -> body.get()); }
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
                || input.channels().stream().anyMatch(Objects::isNull) || input.retentionDays() < 1 || input.retentionDays() > 3650
                || input.dailyWriteLimit() < 1 || input.dailyWriteLimit() > 1000
                || input.dailyQueryLimit() < 1 || input.dailyQueryLimit() > 1000)
            throw new MemoryRequestException(400, "请确认有效的记忆用途、渠道、保留期限和调用上限");
        requestId(input.requestId());
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
