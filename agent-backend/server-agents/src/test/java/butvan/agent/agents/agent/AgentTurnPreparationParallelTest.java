package butvan.agent.agents.agent;

import butvan.agent.agents.agent.permission.OrphanedToolCallRecovery;
import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.run.ActiveAgentRunRegistry;
import butvan.agent.agents.agent.run.AgentRunCheckpointService;
import butvan.agent.agents.context.ContextEnvelope;
import butvan.agent.agents.context.ConversationContextAssembler;
import butvan.agent.agents.context.ProfileMaintenanceScheduler;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.routing.ToolCapabilityRouter;
import butvan.agent.agents.routing.ToolRoutingDecision;
import butvan.agent.agents.routing.ToolRoutingStateSynchronizer;
import butvan.agent.agents.security.AgentSecurity;
import butvan.agent.agents.session.AgentStreamSession;
import butvan.agent.agents.session.AgentTurnResult;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.SessionChannel;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.SessionPermissionMode;
import butvan.agent.agents.session.dto.SessionSummaryDto;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import butvan.agent.agents.tool.ToolCapabilityCatalog;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证单轮准备阶段中个人上下文组装与 Jev 工具路由并行执行，避免串行放大首字延迟。 */
class AgentTurnPreparationParallelTest {

    @Test
    void assemblesContextAndRoutesToolsInParallel() throws Exception {
        ModelHolder model = mock(ModelHolder.class, RETURNS_DEEP_STUBS);
        when(model.isInitialized()).thenReturn(true);
        when(model.getModel().getModelName()).thenReturn("test");

        SessionCatalogService sessions = mock(SessionCatalogService.class);
        SessionSummaryDto summary = mock(SessionSummaryDto.class);
        when(summary.kind()).thenReturn(SessionKind.GENERAL);
        when(sessions.requireActive("session")).thenReturn(summary);
        when(sessions.getChannel("session")).thenReturn(SessionChannel.DESKTOP);
        when(sessions.getPermissionMode("session")).thenReturn(SessionPermissionMode.ASK);

        TranscriptService transcript = mock(TranscriptService.class);
        String turnId = UUID.randomUUID().toString();
        when(transcript.appendUserMessageWithTurnId("session", "hello", turnId, List.of())).thenReturn(turnId);

        AgentFactory factory = mock(AgentFactory.class);
        HarnessAgent agent = mock(HarnessAgent.class, RETURNS_DEEP_STUBS);
        when(factory.currentAgent("session")).thenReturn(agent);
        when(agent.streamEvents(anyList(), any())).thenReturn(Flux.just(
                new AgentResultEvent(Msg.builder().role(MsgRole.ASSISTANT).textContent("ok").build()),
                new AgentEndEvent("reply")));

        ToolCapabilityCatalog catalog = mock(ToolCapabilityCatalog.class);
        when(catalog.groupNames()).thenReturn(Set.of("workspace"));

        // 两个阶段必须同时到达屏障才能继续；若退化为串行则必然超时失败
        CountDownLatch bothEntered = new CountDownLatch(2);
        ConversationContextAssembler contextAssembler = mock(ConversationContextAssembler.class);
        when(contextAssembler.assemble(any())).thenAnswer(ignored -> {
            bothEntered.countDown();
            assertTrue(bothEntered.await(2, TimeUnit.SECONDS), "上下文组装未能与路由并行启动");
            return ContextEnvelope.empty();
        });

        ToolCapabilityRouter router = mock(ToolCapabilityRouter.class);
        when(router.route(any())).thenAnswer(ignored -> {
            bothEntered.countDown();
            assertTrue(bothEntered.await(2, TimeUnit.SECONDS), "Jev 路由未能与上下文组装并行启动");
            return ToolRoutingDecision.off();
        });

        AgentRunCheckpointService checkpoints = mock(AgentRunCheckpointService.class);
        AgentService service = new AgentService(
                model,
                new CurrentUserProvider(),
                sessions,
                transcript,
                new PendingApprovalStore(),
                new AgentEventManager(),
                factory,
                mock(AgentSecurity.class),
                new AgentRunCompleter(transcript, sessions, checkpoints),
                checkpoints,
                contextAssembler,
                mock(ProfileMaintenanceScheduler.class),
                new ActiveAgentRunRegistry(),
                mock(OrphanedToolCallRecovery.class),
                router,
                catalog,
                mock(ToolRoutingStateSynchronizer.class)
        );

        AgentStreamSession stream = service.streamAgent(
                new AgentUserCall("session", "hello", "hello", List.of(), "run-parallel", turnId));
        AgentTurnResult result = stream.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

        assertEquals(MessageStatus.COMPLETED, result.status());
        assertEquals("ok", result.content());
    }
}
