package butvan.agent.network.memory;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.memory.MemorySourceRegistry.*;
import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.network.config.database.*;
import butvan.agent.network.memory.client.MemoryRemoteGateway;
import butvan.agent.network.memory.model.MemoryModels.Authorization;
import butvan.agent.network.memory.repository.MemoryRepository;
import butvan.agent.network.memory.service.MemoryService;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.Path;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 只使用临时 home／SQLite、可控时钟和假远端，供公开 Interface 测试复用。 */
final class MemoryFixture {
    final AgentStorageProperties storage;
    final ResetBarrier barrier;
    final MemoryRepository repository;
    final PlatformTransactionManager transactions;
    final org.springframework.jdbc.core.JdbcTemplate jdbc;
    final FakeRemote remote = new FakeRemote();
    final Clock clock;
    final CurrentUserProvider user = new CurrentUserProvider();
    final MemoryService service;

    MemoryFixture(Path directory) {
        this(directory, Clock.fixed(Instant.parse("2026-10-28T08:00:00Z"), ZoneOffset.UTC));
    }
    MemoryFixture(Path directory, Clock clock) {
        this.clock = clock;
        storage = new AgentStorageProperties(directory);
        barrier = new ResetBarrier(storage);
        var properties = new LocalDatabaseProperties();
        properties.setPath(directory.resolve("data/test.db"));
        var config = new LocalDatabaseConfiguration();
        var dataSource = config.localDataSource(properties);
        config.flyway(dataSource).migrate();
        jdbc = config.jdbcTemplate(dataSource);
        repository = new MemoryRepository(jdbc);
        transactions = config.transactionManager(dataSource);
        service = service(remote, user, "test");
    }
    MemoryService service(MemoryRemoteGateway gateway, CurrentUserProvider identity, String environment) {
        return new MemoryService(repository, barrier, identity, clock, gateway, transactions, environment);
    }
    Authorization authorization(long revision, String request) {
        return new Authorization(revision, remote.fingerprint, true, true, Set.of(Channel.DESKTOP), 30, 5, 5, true, request);
    }
    void enabled() {
        service.authorize(authorization(0, "grant"));
        service.saveSettings(service.settings().version(), true, true);
    }
    Source source() {
        return new Source(user.currentUserId(), "session", UUID.randomUUID().toString(), "message", clock.instant(),
                Channel.DESKTOP, "a".repeat(64), barrier.state().generation());
    }
    static final class FakeRemote implements MemoryRemoteGateway {
        volatile String fingerprint = "fake-recipient-chain-v1";
        volatile DeletionResult result = DeletionResult.VERIFIED;
        final AtomicInteger deletions = new AtomicInteger();
        volatile Runnable beforeDeletion = () -> { };
        public String recipientFingerprint() { return fingerprint; }
        public DeletionResult deleteAndVerify(Target target) { deletions.incrementAndGet(); beforeDeletion.run(); return result; }
    }
}
