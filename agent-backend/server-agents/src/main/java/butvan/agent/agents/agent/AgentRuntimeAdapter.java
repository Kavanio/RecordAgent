package butvan.agent.agents.agent;

import butvan.agent.agents.agent.permission.OrphanedToolCallRecovery;
import butvan.agent.agents.agent.run.AgentRun;
import butvan.agent.agents.routing.ToolRoutingDecision;
import butvan.agent.agents.routing.ToolRoutingStateSynchronizer;
import butvan.agent.agents.security.AgentSecurity;
import butvan.agent.agents.session.AgentStreamSession;
import butvan.agent.agents.session.dto.SessionPermissionMode;
import butvan.agent.agents.tool.ToolSchemaSelectionMiddleware;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 隔离 AgentScope 运行时细节的薄适配器。
 *
 * <p>将对 {@link HarnessAgent}、底层 {@code AgentState}、中断控制、权限模式映射、
 * 工作区计划文件与恢复确认消息的操作统一收敛到此处，避免业务编排层直接耦合框架内部 API。</p>
 */
@Slf4j
@RequiredArgsConstructor
class AgentRuntimeAdapter {

    private final AgentFactory agentFactory;
    private final AgentSecurity agentSecurity;
    private final OrphanedToolCallRecovery orphanedToolCallRecovery;
    private final ToolRoutingStateSynchronizer toolRoutingStateSynchronizer;

    /** 构造带有工具选择状态的运行时上下文。 */
    RuntimeContext createRuntimeContext(String userId, String sessionId) {
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
        ToolSchemaSelectionMiddleware.prepareContext(context);
        return context;
    }

    /** 清理进程重启后遗留的未完成工具调用，避免阻塞新一轮对话。 */
    void recoverOrphanedToolCalls(String userId, String sessionId) {
        HarnessAgent agent = agentFactory.currentAgent(sessionId);
        int recovered = orphanedToolCallRecovery.recover(
                agent.getDelegate().getAgentState(userId, sessionId), agent.getName());
        if (recovered > 0) {
            agent.getDelegate().saveAgentState(userId, sessionId);
            log.warn("已安全收尾进程遗留工具调用：sessionId={}, 数量={}", sessionId, recovered);
        }
    }

    /** 将 ACTIVE 路由决策写入当前会话状态，并在模型调用前持久化。 */
    void synchronizeToolRoutingState(String userId, String sessionId, ToolRoutingDecision decision) {
        if (decision == null || !decision.appliesToModelCall()) return;

        HarnessAgent agent = agentFactory.currentAgent(sessionId);
        var state = agent.getDelegate().getAgentState(userId, sessionId);
        if (toolRoutingStateSynchronizer.apply(state, decision)) {
            agent.getDelegate().saveAgentState(userId, sessionId);
        }
    }

    /** 绑定流中断动作、配置权限上下文并启动 AgentScope 事件流。 */
    Iterable<AgentEvent> streamEvents(
            AgentRun run,
            SessionPermissionMode productMode,
            List<Msg> inputMessages,
            AgentStreamSession streamSession
    ) {
        HarnessAgent agent = agentFactory.currentAgent(run.sessionId());
        streamSession.bindCancellationAction(
                () -> agent.getDelegate().interrupt(run.userId(), run.sessionId()));
        agent.getDelegate().getAgentState(run.userId(), run.sessionId())
                .setPermissionContext(agentSecurity.createPermissionContext(productMode));
        // setPermissionMode 同时重建当前会话的权限引擎并持久化新的上下文。
        agent.setPermissionMode(run.userId(), run.sessionId(), toAgentScopeMode(productMode));
        return agent.streamEvents(inputMessages, run.runtimeContext()).toIterable();
    }

    /** 构建携带 ConfirmResult 的恢复消息，供 HITL 审批后继续执行同一轮。 */
    Msg buildResumeMessage(List<ConfirmResult> results) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, results);

        // 这是框架恢复信号，不是用户的新提问。
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("permission confirmation received")
                .metadata(metadata)
                .build();
    }

    /** 读取当前会话工作区下的任务计划书（plans/PLAN.md）。 */
    String readPlan(String userId, String sessionId) {
        HarnessAgent agent = agentFactory.currentAgent(sessionId);
        RuntimeContext context = createRuntimeContext(userId, sessionId);
        String planPath = PlanModeManager.DEFAULT_PLAN_DIR + "/PLAN.md";
        return agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(context, planPath);
    }

    /** 将主动发送的助手消息同步进底层 AgentState 上下文，便于用户直接接着回复。 */
    void appendAssistantMessageToState(String userId, String sessionId, String content) {
        try {
            HarnessAgent agent = agentFactory.currentAgent(sessionId);
            var state = agent.getDelegate().getAgentState(userId, sessionId);
            state.contextMutable().add(Msg.builder().role(MsgRole.ASSISTANT).textContent(content).build());
            agent.getDelegate().saveAgentState(userId, sessionId);
        } catch (RuntimeException exception) {
            log.debug("同步主动消息到 AgentState 跳过：sessionId={}, errorType={}",
                    sessionId, exception.getClass().getSimpleName());
        }
    }

    private PermissionMode toAgentScopeMode(SessionPermissionMode mode) {
        return switch (mode) {
            case ASK -> PermissionMode.DEFAULT;
            case AUTO_EDIT -> PermissionMode.ACCEPT_EDITS;
            case FULL_ACCESS -> PermissionMode.BYPASS;
        };
    }
}
