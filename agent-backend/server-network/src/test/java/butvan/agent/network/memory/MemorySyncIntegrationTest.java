package butvan.agent.network.memory;

import butvan.agent.agents.agent.AgentUserCall;
import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import butvan.agent.network.memory.client.MemoryWriteGateway.Outcome;
import butvan.agent.network.memory.model.MemoryModels.Authorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 经完整本地生命周期验证崩溃窗口、队列幂等、来源与异步副作用；无真实外部调用。 */
class MemorySyncIntegrationTest {
    @TempDir Path directory;

    @Test void successfulTerminalNotificationCreatesOneTaskAndOnlyUserTextReachesFakeWriter() {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        String text = "今天尝试把手记翻成英文，感觉不错。";
        String turn = f.admit(text);
        f.finish(turn, MessageStatus.COMPLETED, true);
        f.adapter.completed(f.base.user.currentUserId(), f.session, turn, MessageStatus.COMPLETED);
        assertEquals(1, f.sync.operations().size());
        assertEquals("PENDING", f.sync.operations().getFirst().state());
        f.writer.beforeSubmit = () -> assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        f.sync.runOnce(20);
        assertEquals("SUCCEEDED", f.sync.operations().getFirst().state());
        assertEquals(text, f.writer.writes.getFirst().content());
        assertEquals(Channel.DESKTOP, f.writer.writes.getFirst().channel());
        f.sync.runOnce(20);
        assertEquals(1, f.writer.writes.size());
    }

    @Test void crashAfterTerminalBeforeEnqueueIsRecoveredFromExistingReceiptAfterRestart() {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        String turn = f.admit("今天记下一个值得回看的想法。");
        f.finish(turn, MessageStatus.COMPLETED, false);
        assertTrue(f.sync.operations().isEmpty());
        var restarted = f.worker();
        restarted.reconcile(20);
        assertEquals(1, restarted.operations().size());
        restarted.runOnce(20);
        assertEquals(1, f.writer.writes.size());
    }

    @Test void missingReceiptCannotBeInventedFromOldTranscriptOrLaterAuthorization() {
        var f = new MemorySyncFixture(directory);
        String old = f.admit("授权之前说过的内容。");
        f.finish(old, MessageStatus.COMPLETED, false);
        f.base.enabled();
        f.sync.reconcile(20);
        f.sync.runOnce(20);
        assertTrue(f.sync.operations().isEmpty());
        assertTrue(f.writer.writes.isEmpty());
    }

    @Test void permissionPauseFailureAndCancellationNeverBecomeReadyTasks() {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        String pending = f.admit("还在等待工具审批。");
        f.sync.reconcile(20);
        assertTrue(f.sync.operations().isEmpty());
        f.finish(pending, MessageStatus.FAILED, true);
        String cancelled = f.admit("这次轮次随后取消。");
        f.finish(cancelled, MessageStatus.CANCELLED, true);
        f.sync.reconcile(20);
        assertTrue(f.sync.operations().isEmpty());
        assertEquals(0, f.sync.runOnce(20));
    }

    @Test void unknownSubmissionQueriesOriginalOperationInsteadOfResubmitting() {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        f.finish(f.admit("一次英语学习尝试。"), MessageStatus.COMPLETED, true);
        f.writer.beforeSubmit = () -> { throw new IllegalStateException("synthetic transport loss"); };
        f.sync.runOnce(20);
        String id = f.sync.operations().getFirst().id();
        assertEquals("UNKNOWN", f.sync.operations().getFirst().state());
        f.clock.advance(Duration.ofMinutes(1));
        f.worker().runOnce(20);
        assertEquals(List.of(id), f.writer.queries);
        assertEquals(1, f.writer.writes.size());
        assertEquals("SUCCEEDED", f.sync.operations().getFirst().state());
    }

    @Test void confirmedNotSubmittedRetriesAreBoundedAndReuseStableOperationId() {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        f.finish(f.admit("需要有限重试的合成来源。"), MessageStatus.COMPLETED, true);
        f.writer.outcome = Outcome.NOT_SUBMITTED_RETRYABLE;
        for (int i = 0; i < 5; i++) { f.sync.runOnce(20); f.clock.advance(Duration.ofMinutes(1)); }
        assertEquals(3, f.writer.writes.size());
        assertEquals(1, f.writer.writes.stream().map(w -> w.operationId()).distinct().count());
        assertEquals("FAILED", f.sync.operations().getFirst().state());
    }

    @Test void revokeDuringSubmissionCannotPublishSuccessOrResurrectOnReauthorization() {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        f.finish(f.admit("在途任务随后撤权。"), MessageStatus.COMPLETED, true);
        f.writer.beforeSubmit = () -> f.base.service.revoke(1, "in-flight-revoke");
        f.sync.runOnce(20);
        assertEquals(1, f.writer.writes.size());
        assertEquals("CANCELLED", f.sync.operations().getFirst().state());
        f.base.service.authorize(f.base.authorization(2, "grant-again"));
        f.base.service.saveSettings(f.base.service.settings().version(), true, true);
        f.sync.runOnce(20);
        assertEquals(1, f.writer.writes.size());
    }

    @Test void changedOrDeletedOriginalIsNotSentEvenIfTaskWasAlreadyEnqueued() throws Exception {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        f.finish(f.admit("original source phrase"), MessageStatus.COMPLETED, true);
        Path file = f.base.storage.transcriptFile(f.session);
        Files.writeString(file, Files.readString(file).replace("original source phrase", "modified source phrase"));
        f.sync.runOnce(20);
        assertTrue(f.writer.writes.isEmpty());
        assertEquals("CANCELLED", f.sync.operations().getFirst().state());
        String second = f.admit("随后删除原始聊天。");
        f.finish(second, MessageStatus.COMPLETED, true);
        f.transcript.delete(f.session);
        f.sync.runOnce(20);
        assertTrue(f.writer.writes.isEmpty());
        assertTrue(f.sync.operations().stream().allMatch(o -> o.state().equals("CANCELLED")));
    }

    @Test void dailyBudgetAndBatchBoundAreEnforcedBeforeExternalSubmission() {
        var f = new MemorySyncFixture(directory);
        f.base.service.authorize(new Authorization(0, f.base.remote.fingerprint, true, true, Set.of(Channel.DESKTOP), 30, 1, 1, true, "one-per-day"));
        f.base.service.saveSettings(0, true, true);
        f.finish(f.admit("第一条合成经历。"), MessageStatus.COMPLETED, true);
        f.finish(f.admit("第二条合成经历。"), MessageStatus.COMPLETED, true);
        f.sync.runOnce(20);
        f.sync.runOnce(20);
        assertEquals(1, f.writer.writes.size());
        assertThrows(butvan.agent.network.memory.service.MemoryRequestException.class, () -> f.sync.runOnce(21));
        f.clock.advance(Duration.ofDays(1));
        f.sync.runOnce(20);
        assertEquals(2, f.writer.writes.size());
    }

    @Test void legacyExpandedSensitiveAndUnapprovedChannelInputsDoNotAcquireReceipts() {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        List<AgentUserCall> requests = List.of(
                new AgentUserCall(f.session, "legacy", "legacy", List.of(), "run"),
                new AgentUserCall(f.session, "用户问题", "expanded private record", List.of("record"), "run", null, List.of(), Channel.DESKTOP),
                f.request("我的密码只用于合成测试", Channel.DESKTOP),
                f.request("飞书尚未授权的来源", Channel.FEISHU));
        for (var request : requests) {
            String turn = f.transcript.appendUserMessage(f.session, request.content());
            f.adapter.admitted(request, f.base.user.currentUserId(), turn);
            f.finish(turn, MessageStatus.COMPLETED, true);
        }
        f.sync.runOnce(20);
        assertTrue(f.sync.operations().isEmpty());
    }

    @Test void expiredClaimAfterAbruptWorkerExitOnlyQueriesTheOriginalOperation() {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        f.finish(f.admit("模拟领取后进程消失。"), MessageStatus.COMPLETED, true);
        f.writer.beforeSubmit = () -> { throw new AssertionError("synthetic process exit"); };
        assertThrows(AssertionError.class, () -> f.sync.runOnce(20));
        assertEquals("CLAIMED", f.sync.operations().getFirst().state());
        String operation = f.sync.operations().getFirst().id();
        f.clock.advance(Duration.ofSeconds(31));
        f.worker().runOnce(20);
        assertEquals(List.of(operation), f.writer.queries);
        assertEquals(1, f.writer.writes.size());
        assertEquals("SUCCEEDED", f.sync.operations().getFirst().state());
    }

    @Test void lateExpiredWorkerCannotOverwriteANewerClaimResult() throws Exception {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        f.finish(f.admit("旧消费者迟到结果测试。"), MessageStatus.COMPLETED, true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        f.writer.beforeSubmit = () -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic timeout"); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("synthetic interruption"); }
        };
        f.writer.outcome = Outcome.FAILED;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> f.sync.runOnce(20));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                f.clock.advance(Duration.ofSeconds(31));
                f.worker().runOnce(20);
                assertEquals("SUCCEEDED", f.sync.operations().getFirst().state());
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
            assertEquals("SUCCEEDED", f.sync.operations().getFirst().state());
            assertEquals(1, f.writer.writes.size());
        }
    }

    @Test void unfinishedEarlyTurnsCannotStarveLaterCompletedSources() {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        for (int i = 0; i < 20; i++) f.admit("等待中的轮次 " + i);
        String last = f.admit("已经完成的后续轮次。");
        f.finish(last, MessageStatus.COMPLETED, false);
        assertEquals(20, f.sync.reconcile(20));
        assertTrue(f.sync.operations().isEmpty());
        assertEquals(1, f.sync.reconcile(20));
        assertEquals(1, f.sync.operations().size());
    }

    @Test void resetDuringSubmissionRetainsTargetAndRemovesTasksOnlyAfterRemoteVerification() {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        f.finish(f.admit("重置中的异步任务。"), MessageStatus.COMPLETED, true);
        f.writer.beforeSubmit = () -> f.base.barrier.begin("sync-reset", "sync-confirmation", f.base.user.currentUserId(), "m2-confirmed-scope");
        f.sync.runOnce(20);
        assertTrue(f.base.barrier.blocked());
        var participant = new butvan.agent.network.memory.reset.MemoryResetParticipant(
                new butvan.agent.network.memory.repository.MemoryResetRepository(f.base.jdbc, f.base.repository),
                f.base.barrier, f.base.remote, f.base.transactions, "test");
        participant.stop(f.base.user.currentUserId());
        participant.clean(f.base.user.currentUserId());
        assertEquals(1, f.base.remote.deletions.get());
        assertEquals("COMPLETE", participant.remoteStatus(f.base.user.currentUserId(), "sync-reset"));
        f.base.barrier.complete();
        assertTrue(f.sync.operations().isEmpty());
        assertFalse(f.base.service.settings().autoSave());
    }

    @Test void projectBoundSessionCannotAcquireReceiptEvenWithMarkedPlainText() {
        var f = new MemorySyncFixture(directory);
        f.base.enabled();
        var projectSessions = org.mockito.Mockito.mock(butvan.agent.agents.session.SessionCatalogService.class);
        org.mockito.Mockito.when(projectSessions.requireActive(f.session)).thenReturn(new butvan.agent.agents.session.dto.SessionSummaryDto(
                f.session, butvan.agent.agents.session.dto.SessionKind.PROJECT, "project-id", "title", "", f.clock.instant(), f.clock.instant(),
                butvan.agent.agents.session.dto.SessionStatus.ACTIVE));
        var adapter = new butvan.agent.network.memory.service.MemoryTurnAdapter(f.base.service, f.sync, f.transcript, projectSessions, f.base.barrier);
        String turn = f.transcript.appendUserMessage(f.session, "项目正文不自动上传");
        adapter.admitted(f.request("项目正文不自动上传", Channel.DESKTOP), f.base.user.currentUserId(), turn);
        f.finish(turn, MessageStatus.COMPLETED, false);
        f.sync.reconcile(20);
        assertTrue(f.sync.operations().isEmpty());
    }
}
