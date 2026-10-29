package butvan.agent.network.memory;

import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import butvan.agent.agents.memory.MemoryRecallPort.Status;
import butvan.agent.network.memory.model.MemoryModels;
import butvan.agent.network.memory.model.MemoryModels.Authorization;
import butvan.agent.network.memory.model.MemoryModels.RecallMode;
import butvan.agent.network.memory.repository.MemoryRecallRepository;
import butvan.agent.network.memory.repository.MemoryStatusRepository;
import butvan.agent.network.memory.service.MemoryRequestException;
import butvan.agent.network.memory.service.MemoryStatusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** V2 长期授权、独立运行保护与分项计量；全部使用临时目录和假 Gateway。 */
class MemoryPolicyV2IntegrationTest {
    @TempDir Path directory;

    private Authorization v2(RecallMode mode, int days, int writes, int queries) {
        return new Authorization(0, "fake-recipient-chain-v1", true, true, Set.of(Channel.DESKTOP), days,
                writes, queries, MemoryModels.POLICY_V2, mode, true, true, true, "grant-v2");
    }

    @Test void v2RequiresExplicitConsentAndRejectsMixedDailyLimits() {
        var f = new MemoryFixture(directory);
        assertEquals(400, assertThrows(MemoryRequestException.class,
                () -> f.service.authorize(new Authorization(0, f.remote.fingerprint, true, true, Set.of(Channel.DESKTOP),
                        0, 0, 0, MemoryModels.POLICY_V2, RecallMode.ALL_AUTHORIZED, false, true, true, "no-continuous"))).getCode());
        assertEquals(400, assertThrows(MemoryRequestException.class,
                () -> f.service.authorize(new Authorization(0, f.remote.fingerprint, true, true, Set.of(Channel.DESKTOP),
                        0, 0, 0, MemoryModels.POLICY_V2, RecallMode.ALL_AUTHORIZED, true, false, true, "no-impact"))).getCode());
        // 长期模式不接受自授权日额度，也不接受看似“长期”的保留天数。
        assertEquals(400, assertThrows(MemoryRequestException.class,
                () -> f.service.authorize(new Authorization(0, f.remote.fingerprint, true, true, Set.of(Channel.DESKTOP),
                        0, 5, 30, MemoryModels.POLICY_V2, RecallMode.ALL_AUTHORIZED, true, true, true, "mixed"))).getCode());
        assertEquals(400, assertThrows(MemoryRequestException.class,
                () -> f.service.authorize(new Authorization(0, f.remote.fingerprint, true, true, Set.of(Channel.DESKTOP),
                        7, 0, 0, MemoryModels.POLICY_V2, RecallMode.ALL_AUTHORIZED, true, true, true, "fake-longterm"))).getCode());
        assertEquals(400, assertThrows(MemoryRequestException.class,
                () -> f.service.authorize(new Authorization(0, f.remote.fingerprint, true, true, Set.of(Channel.DESKTOP),
                        0, 0, 0, MemoryModels.POLICY_V2, RecallMode.RECENT_DAYS, true, true, true, "no-window"))).getCode());

        var result = f.service.authorize(v2(RecallMode.ALL_AUTHORIZED, 0, 0, 0));
        assertEquals(1, result.revision());
        assertEquals(2, f.service.grant().policyVersion());
        assertEquals(RecallMode.ALL_AUTHORIZED, f.service.grant().recallMode());
        // V1 兼容入口继续按原期限与日额度授权，不被 V2 默认值覆盖。
        var legacy = new MemoryFixture(directory.resolve("legacy"));
        legacy.service.authorize(legacy.authorization(0, "v1"));
        assertEquals(1, legacy.service.grant().policyVersion());
        assertEquals(RecallMode.RECENT_DAYS, legacy.service.grant().recallMode());
        assertEquals(5, legacy.service.grant().queryLimit());
    }

    @Test void runtimePolicyChangeKeepsGrantAndAlreadyCapturedSources() {
        var f = new MemoryFixture(directory);
        var granted = f.service.authorize(v2(RecallMode.ALL_AUTHORIZED, 0, 0, 0));
        f.service.saveSettings(0, true, true);
        var receipt = f.service.capture(f.source()).orElseThrow();
        var policy = f.service.runtimePolicy();
        assertEquals(5, policy.batchSize());

        f.service.saveRuntimePolicy(policy.version(), 10, 800, 3000, 400);

        var grant = f.service.grant();
        assertEquals(granted.revision(), grant.revision());
        assertEquals(granted.effectiveAt(), grant.effectiveAt());
        assertTrue(f.service.isCurrent(receipt));
        var updated = f.service.runtimePolicy();
        assertEquals(policy.version() + 1, updated.version());
        assertEquals(400, updated.dailyRecallLimit());
        assertEquals(409, assertThrows(MemoryRequestException.class,
                () -> f.service.saveRuntimePolicy(policy.version(), 10, 800, 3000, 400)).getCode());
        assertEquals(400, assertThrows(MemoryRequestException.class,
                () -> f.service.saveRuntimePolicy(updated.version(), 21, 800, 3000, 400)).getCode());
    }

    @Test void longTermRecallIgnoresAgeWhileRecentDaysStillExpires() {
        try (var f = new MemoryRecallFixture(directory)) {
            f.preparedV2(RecallMode.ALL_AUTHORIZED, 0);
            f.synced();
            f.sync.clock.advance(Duration.ofDays(400));
            assertEquals(Status.SUCCESS, f.adapter.recall(f.portRequest(() -> false)).status());
        }
        try (var f = new MemoryRecallFixture(directory.resolve("recent"))) {
            f.preparedV2(RecallMode.RECENT_DAYS, 30);
            f.synced();
            f.sync.clock.advance(Duration.ofDays(31));
            assertEquals(Status.INVALID_RESULT, f.adapter.recall(f.portRequest(() -> false)).status());
        }
    }

    @Test void statusChecksUseTheirOwnBudgetAndCannotConsumeRecallQuota() {
        try (var f = new MemoryRecallFixture(directory)) {
            f.preparedV2(RecallMode.ALL_AUTHORIZED, 0);
            f.sync.base.service.saveRuntimePolicy(f.sync.base.service.runtimePolicy().version(), 5, 500, 1000, 1);
            // 第一条来源真实写入成功，可用于召回核对。
            f.synced();
            // 第二条只被远端受理，后续消费都是状态核对。
            f.sync.writer.outcome = butvan.agent.network.memory.client.MemoryWriteGateway.Outcome.ACCEPTED;
            f.sync.finish(f.sync.admit("第二次把手记翻成英文，也想记下来。"), butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus.COMPLETED, true);
            f.sync.sync.runOnce(5);
            f.sync.writer.queryOutcome = butvan.agent.network.memory.client.MemoryWriteGateway.Outcome.ACCEPTED;
            for (int i = 0; i < 3; i++) {
                f.sync.clock.advance(Duration.ofMinutes(1));
                f.sync.sync.runOnce(5);
            }
            assertEquals(3, f.sync.writer.queries.size());
            assertEquals(0, f.gateway.calls.get());
            var today = f.sync.clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
            var usage = new MemoryStatusRepository(f.sync.base.jdbc).usage(f.sync.base.service.state().settings().spaceId(), today);
            assertEquals(3, usage.statusCalls());
            assertEquals(0, usage.recallCalls());
            assertEquals(Status.SUCCESS, f.adapter.recall(f.portRequest(() -> false)).status());
            assertEquals(Status.BUDGET_EXHAUSTED, f.adapter.recall(f.portRequest(() -> false)).status());
            assertEquals(1, f.gateway.calls.get());
        }
    }

    @Test void statusReportsPausesBacklogAndFilteredReasonsWithoutBodies() {
        var f = new MemorySyncFixture(directory);
        {
            f.base.service.authorize(new Authorization(0, f.base.remote.fingerprint, true, true, Set.of(Channel.DESKTOP),
                    0, 0, 0, MemoryModels.POLICY_V2, RecallMode.ALL_AUTHORIZED, true, true, true, "grant-v2"));
            f.base.service.saveSettings(0, true, true);
            f.admit("不要记住这次聊的具体内容。");
            var turn = f.admit("今天尝试把手记翻成英文，感觉不错。");
            f.finish(turn, butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus.COMPLETED, true);
            var status = statusService(f).status();
            assertEquals(1, status.sourceTotal());
            assertEquals(1, status.sourceActive());
            assertEquals(1L, status.skippedToday().get("USER_OPT_OUT"));
            assertFalse(status.toString().contains("手记翻成英文"));
            assertTrue(status.pauseReasons().isEmpty());

            f.base.service.saveSettings(f.base.service.settings().version(), true, false);
            var paused = statusService(f).status();
            assertEquals(java.util.List.of("RECALL_DISABLED"), paused.pauseReasons());
            assertEquals(1L, paused.operations().getOrDefault("PENDING", 0L));
            assertNotNull(paused.oldestPendingAt());
            assertEquals(0, paused.usage().recallCalls());
            // 还没有消费任何一条，不能因为已登记就显示已处理。
            assertEquals(0, paused.usage().writeCalls());
        }
    }

    private MemoryStatusService statusService(MemorySyncFixture f) {
        return new MemoryStatusService(f.base.service, new MemoryStatusRepository(f.base.jdbc),
                new MemoryRecallRepository(f.base.jdbc), f.base.clock);
    }
}
