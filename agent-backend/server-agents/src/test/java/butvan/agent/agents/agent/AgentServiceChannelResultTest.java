package butvan.agent.agents.agent;

import butvan.agent.agents.agent.event.AgentStreamEvent;
import butvan.agent.agents.agent.permission.*;
import butvan.agent.agents.agent.run.*;
import butvan.agent.agents.context.*;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.routing.*;
import butvan.agent.agents.security.AgentSecurity;
import butvan.agent.agents.session.*;
import butvan.agent.agents.session.dto.SessionPermissionMode;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import butvan.agent.agents.tool.ToolCapabilityCatalog;
import io.agentscope.core.event.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 AgentService／终态收尾与运行注册表，验证非 HTTP 渠道取得结果时已完成持久化和解注册。 */
class AgentServiceChannelResultTest {
    final ModelHolder model = mock(ModelHolder.class, RETURNS_DEEP_STUBS);
    final CurrentUserProvider owner = new CurrentUserProvider();
    final SessionCatalogService sessions = mock(SessionCatalogService.class);
    final TranscriptService transcript = mock(TranscriptService.class);
    final PendingApprovalStore pending = new PendingApprovalStore();
    final AgentFactory factory = mock(AgentFactory.class);
    final HarnessAgent agent = mock(HarnessAgent.class, RETURNS_DEEP_STUBS);
    final AgentRunCheckpointService checkpoints = mock(AgentRunCheckpointService.class);
    final ConversationContextAssembler context = mock(ConversationContextAssembler.class);
    final ToolCapabilityRouter router = mock(ToolCapabilityRouter.class);
    final ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
    final String turn = UUID.randomUUID().toString();
    AgentService service;
    AgentRunCompleter completer;

    @BeforeEach void prepare() {
        when(model.isInitialized()).thenReturn(true); when(model.getModel().getModelName()).thenReturn("test");
        when(factory.currentAgent("session")).thenReturn(agent);
        when(sessions.getPermissionMode("session")).thenReturn(SessionPermissionMode.ASK);
        when(transcript.appendUserMessageWithTurnId("session", "input", turn)).thenReturn(turn);
        when(context.assemble(any())).thenReturn(ContextEnvelope.empty());
        when(router.route(any())).thenReturn(mock(ToolRoutingDecision.class));
        completer = new AgentRunCompleter(transcript, sessions, checkpoints);
        service = new AgentService(model, owner, sessions, transcript, pending, new AgentEventManager(), factory,
                mock(AgentSecurity.class), completer, checkpoints, context,
                mock(ProfileMaintenanceScheduler.class), registry, mock(OrphanedToolCallRecovery.class), router,
                mock(ToolCapabilityCatalog.class), mock(ToolRoutingStateSynchronizer.class));
    }

    AgentUserCall request() { return new AgentUserCall("session", "input", "input", List.of(), "run", turn); }

    void drainTerminal(AgentStreamSession stream) throws Exception {
        for (int i = 0; i < 10; i++) {
            AgentStreamEvent event = stream.queue().poll(2, TimeUnit.SECONDS);
            assertNotNull(event);
            if (event.isTerminal()) return;
        }
        fail("没有收到终态");
    }

    @Test void rejectsImagesBeforeRegisteringRunOrPersistingAnythingForTextModel() {
        var image = new AgentImageAttachment("image/png", new byte[32]);
        var error = assertThrows(IllegalArgumentException.class, () -> service.streamAgent(
                new AgentUserCall("session", "input", "input", List.of(), "run", turn, List.of(image))));
        assertTrue(error.getMessage().contains("图片识别能力"));
        verifyNoInteractions(transcript, sessions, agent);
        assertEquals("NOT_FOUND", registry.cancel("local-default", "session", "run").status());
    }

    @Test void enforcesSharedCountLimitEvenForNonHttpCallers() {
        when(model.supportsVision()).thenReturn(true);
        var images = java.util.Collections.nCopies(5, new AgentImageAttachment("image/png", new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> service.streamAgent(
                new AgentUserCall("session", "input", "input", List.of(), "run", turn, images)));
        verifyNoInteractions(transcript, sessions, agent);
    }

    @Test void resultIsCompletePersistedAndRunUnregisteredBeforePublishing() throws Exception {
        when(agent.streamEvents(anyList(), any())).thenReturn(Flux.just(
                new TextBlockDeltaEvent("reply", "block", "不完整的增量"),
                new AgentResultEvent(Msg.builder().role(MsgRole.ASSISTANT).textContent("完整最终回答🙂\n").build()),
                new AgentEndEvent("reply")));
        AgentStreamSession stream = service.streamAgent(request());
        drainTerminal(stream);
        AgentTurnResult result = stream.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals("完整最终回答🙂\n", result.content()); assertEquals(turn, result.turnId());
        assertEquals(MessageStatus.COMPLETED, result.status());
        verify(transcript).appendAssistantMessage(eq("session"), eq(turn), eq(result.content()), anyString(),
                eq(MessageStatus.COMPLETED), anyLong(), anyList(), any());
        assertEquals("NOT_FOUND", registry.cancel("local-default", "session", "run").status());
    }

    @Test void persistenceFailureNeverPublishesASuccessfulResult() throws Exception {
        when(agent.streamEvents(anyList(), any())).thenReturn(Flux.just(
                new AgentResultEvent(Msg.builder().role(MsgRole.ASSISTANT).textContent("answer").build()), new AgentEndEvent("reply")));
        doThrow(new IllegalStateException("模拟磁盘写入失败")).when(transcript)
                .appendAssistantMessage(anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyList(), any());
        AgentStreamSession stream = service.streamAgent(request()); drainTerminal(stream);
        assertNull(stream.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertEquals("NOT_FOUND", registry.cancel("local-default", "session", "run").status());
    }

    @Test void cancellationPreservesPartialReplyAndReleasesRunOnlyAfterSettlement() throws Exception {
        when(agent.streamEvents(anyList(), any())).thenReturn(Flux.concat(
                Flux.just(new TextBlockDeltaEvent("reply", "block", "已生成的部分")), Flux.never()));
        AgentStreamSession stream = service.streamAgent(request());
        assertInstanceOf(AgentStreamEvent.RunStarted.class, stream.queue().poll(2, TimeUnit.SECONDS));
        assertInstanceOf(AgentStreamEvent.TextDelta.class, stream.queue().poll(2, TimeUnit.SECONDS));
        assertTrue(stream.requestCancellation());
        AgentTurnResult result = stream.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MessageStatus.CANCELLED, result.status()); assertEquals("已生成的部分", result.content());
        assertEquals("NOT_FOUND", registry.cancel("local-default", "session", "run").status());
    }

    @Test void memoryCallbackFailuresCannotChangePersistedSuccessfulChat() throws Exception {
        var lifecycle = mock(butvan.agent.agents.memory.MemoryTurnLifecycle.class);
        service.setMemoryLifecycle(lifecycle);
        completer.setMemoryLifecycle(lifecycle);
        doThrow(new IllegalStateException("synthetic ledger failure")).when(lifecycle).admitted(any(), anyString(), anyString());
        doThrow(new IllegalStateException("synthetic queue failure")).when(lifecycle).completed(anyString(), anyString(), anyString(), any());
        when(agent.streamEvents(anyList(), any())).thenReturn(Flux.just(
                new AgentResultEvent(Msg.builder().role(MsgRole.ASSISTANT).textContent("完整回复").build()), new AgentEndEvent("reply")));
        var stream = service.streamAgent(request());
        var result = stream.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MessageStatus.COMPLETED, result.status());
        var order = inOrder(transcript, lifecycle);
        order.verify(transcript).appendUserMessageWithTurnId("session", "input", turn);
        order.verify(lifecycle).admitted(any(), eq("local-default"), eq(turn));
        order.verify(transcript).appendAssistantMessage(anyString(), eq(turn), anyString(), anyString(), eq(MessageStatus.COMPLETED), anyLong(), anyList(), any());
        order.verify(lifecycle).completed("local-default", "session", turn, MessageStatus.COMPLETED);
        assertEquals("NOT_FOUND", registry.cancel("local-default", "session", "run").status());
    }

    @Test void cancellationDuringRecallStillPersistsOneCancelledTurnWithoutStartingTheModel() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        when(context.assemble(any())).thenAnswer(call -> {
            ContextRequest request = call.getArgument(0);
            verify(checkpoints).save(any(AgentRun.class));
            entered.countDown();
            while (!request.cancelled().getAsBoolean()) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            return ContextEnvelope.empty();
        });
        var stream = service.streamAgent(request());
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        stream.requestCancellation();
        var result = stream.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(MessageStatus.CANCELLED, result.status());
        verify(agent, never()).streamEvents(anyList(), any());
        verify(transcript).appendAssistantMessage(eq("session"), eq(turn), anyString(), anyString(), eq(MessageStatus.CANCELLED), anyLong(), anyList(), any());
    }
}
