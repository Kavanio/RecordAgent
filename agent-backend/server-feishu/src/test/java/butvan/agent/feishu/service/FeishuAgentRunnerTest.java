package butvan.agent.feishu.service;

import butvan.agent.agents.agent.AgentService;
import butvan.agent.agents.agent.AgentUserCall;
import butvan.agent.agents.agent.event.AgentStreamEvent;
import butvan.agent.agents.session.AgentStreamSession;
import butvan.agent.agents.session.AgentTurnResult;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuAgentRunnerTest {
    final AgentService service = mock(AgentService.class);
    final String runId = "run";
    final String turnId = "turn";
    final FeishuAgentRunner.Control control = new FeishuAgentRunner.Control();
    AgentTurnResult result(MessageStatus status) { return new AgentTurnResult(runId, "session", turnId, status, "完整答案，尾字🙂\n"); }

    @Test void completedReplyUsesPersistedSnapshotEvenWhenQueueDroppedAllDeltas() {
        AgentStreamSession session = new AgentStreamSession(runId);
        for (int i = 0; i < 64; i++) session.queue().offer(new AgentStreamEvent.TextDelta("过期增量"));
        session.offerTerminal(new AgentStreamEvent.Completed());
        session.settle(result(MessageStatus.COMPLETED));
        when(service.streamAgent(any())).thenReturn(session);
        var reply = new FeishuAgentRunner(service).run("session", runId, turnId, "input", Duration.ofMinutes(10), control);
        assertEquals("完整答案，尾字🙂\n", reply.text());
        verify(service).streamAgent(new AgentUserCall("session", "input", "input", List.of(), runId, turnId));
    }

    @Test void cancelledTerminalDoesNotWaitForFiveMinutes() {
        AgentStreamSession session = new AgentStreamSession(runId);
        session.offerTerminal(new AgentStreamEvent.Cancelled(runId));
        session.settle(result(MessageStatus.CANCELLED));
        when(service.streamAgent(any())).thenReturn(session);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            var reply = new FeishuAgentRunner(service).run("session", runId, turnId, "input", Duration.ofMinutes(10), control);
            assertEquals(MessageStatus.CANCELLED, reply.result().status());
        });
    }

    @Test void absoluteDeadlineDoesNotResetWhenEventsArrive() {
        AtomicLong nanos = new AtomicLong();
        AtomicInteger cancellations = new AtomicInteger();
        AgentStreamSession session = new AgentStreamSession(runId);
        for (int i = 0; i < 60; i++) session.queue().offer(new AgentStreamEvent.TextDelta("持续输出"));
        session.bindCancellationAction(() -> {
            cancellations.incrementAndGet();
            session.offerTerminal(new AgentStreamEvent.Cancelled(runId));
            session.settle(result(MessageStatus.CANCELLED));
        });
        when(service.streamAgent(any())).thenReturn(session);
        var reply = new FeishuAgentRunner(service, () -> nanos.getAndAdd(TimeUnit.SECONDS.toNanos(1)))
                .run("session", runId, turnId, "input", Duration.ofSeconds(2), control);
        assertEquals("timeout", reply.stopReason()); assertEquals(1, cancellations.get());
    }

    @Test void approvalResumeWaitsForOldProducerToUnregister() throws Exception {
        AgentStreamSession paused = new AgentStreamSession(runId);
        paused.offerTerminal(new AgentStreamEvent.PermissionRequired("approval", runId, turnId, List.of()));
        AgentStreamSession resumed = new AgentStreamSession(runId);
        resumed.offerTerminal(new AgentStreamEvent.Completed()); resumed.settle(result(MessageStatus.COMPLETED));
        when(service.streamAgent(any())).thenReturn(paused);
        when(service.autoApproveAndResume("session", "approval")).thenReturn(resumed);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<FeishuAgentRunner.Reply> reply = executor.submit(() -> new FeishuAgentRunner(service)
                    .run("session", runId, turnId, "input", Duration.ofMinutes(10), control));
            verify(service, timeout(1000)).streamAgent(any());
            verify(service, never()).autoApproveAndResume(anyString(), anyString());
            paused.settle(null);
            assertEquals(MessageStatus.COMPLETED, reply.get(2, TimeUnit.SECONDS).result().status());
            verify(service).autoApproveAndResume("session", "approval");
        }
    }

    @Test void stopDuringPermissionNeverApprovesOrExecutesTools() {
        AgentStreamSession paused = new AgentStreamSession(runId);
        paused.offerTerminal(new AgentStreamEvent.PermissionRequired("approval", runId, turnId, List.of()));
        paused.settle(null);
        when(service.streamAgent(any())).thenAnswer(call -> { control.stop("user"); return paused; });
        when(service.cancelPendingApproval("session", "approval", runId)).thenReturn(result(MessageStatus.CANCELLED));
        var reply = new FeishuAgentRunner(service).run("session", runId, turnId, "input", Duration.ofMinutes(10), control);
        assertEquals(MessageStatus.CANCELLED, reply.result().status());
        verify(service, never()).autoApproveAndResume(anyString(), anyString());
    }

    @Test void failedApprovalResumeCancelsThePendingBatchRatherThanPoisoningTheNextMessage() {
        AgentStreamSession paused = new AgentStreamSession(runId);
        paused.offerTerminal(new AgentStreamEvent.PermissionRequired("approval", runId, turnId, List.of()));
        paused.settle(null);
        when(service.streamAgent(any())).thenReturn(paused);
        when(service.autoApproveAndResume("session", "approval")).thenThrow(new IllegalArgumentException("模拟恢复冲突"));
        when(service.cancelPendingApproval("session", "approval", runId)).thenReturn(result(MessageStatus.CANCELLED));
        var reply = new FeishuAgentRunner(service).run("session", runId, turnId, "input", Duration.ofMinutes(10), control);
        verify(service).cancelPendingApproval("session", "approval", runId);
        assertEquals(MessageStatus.CANCELLED, reply.result().status());
        assertEquals("failure", reply.stopReason());
    }

    @Test void completionWinsLateCancellationWithoutChangingSavedStatus() {
        AgentStreamSession completed = new AgentStreamSession(runId);
        completed.offerTerminal(new AgentStreamEvent.Completed()); completed.settle(result(MessageStatus.COMPLETED));
        when(service.streamAgent(any())).thenAnswer(call -> { control.stop("user"); return completed; });
        var reply = new FeishuAgentRunner(service).run("session", runId, turnId, "input", Duration.ofMinutes(10), control);
        assertNull(reply.stopReason()); assertEquals(MessageStatus.COMPLETED, reply.result().status());
    }
}
