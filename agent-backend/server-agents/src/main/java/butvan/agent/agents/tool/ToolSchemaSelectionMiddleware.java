package butvan.agent.agents.tool;

import butvan.agent.agents.routing.ToolRoutingDecision;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolSchema;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 按本轮 Jev 决策组装模型可见的 Tool Schema，不修改共享 Toolkit。 */
@Component
public class ToolSchemaSelectionMiddleware implements MiddlewareBase {

    /** 共享引用跨过框架 RuntimeContext 的浅复制；只属于当前 run，不写 AgentState。 */
    private static final class SelectionState {
        private volatile boolean manual;
    }

    /** 在传入框架前初始化；HITL 恢复沿用原 run 的同一份选择状态。 */
    public static void prepareContext(RuntimeContext context) {
        if (context != null && context.get(SelectionState.class) == null)
            context.put(SelectionState.class, new SelectionState());
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                  Function<AgentInput, Flux<AgentEvent>> next) {
        prepareContext(context);
        return next.apply(input);
    }

    /** capabilityCatalog：用于判断一个 Tool 是分组工具还是常驻兼容工具。 */
    private final ToolCapabilityCatalog capabilityCatalog;

    /**
     * 创建本轮 Tool Schema 选择 Middleware。
     *
     * @param capabilityCatalog 项目唯一的 Tool 能力组目录
     */
    public ToolSchemaSelectionMiddleware(ToolCapabilityCatalog capabilityCatalog) {
        this.capabilityCatalog = capabilityCatalog;
    }

    /**
     * 观察主模型是否调用了 reset_equipped_tools。
     *
     * <p>一旦调用，后续 Model Call 必须尊重 AgentScope 保存的会话级能力组，不能再用
     * Jev 的初始选择覆盖它。这使元工具能够真正补救 Jev 的漏选。</p>
     *
     * @param agent 当前执行工具的 Agent
     * @param context 当前运行独享的上下文
     * @param input 本轮准备执行的工具调用
     * @param next Middleware 链的下一个处理函数
     * @return 下游工具执行事件流
     */
    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next
    ) {
        boolean resetsEquippedTools = input != null
                && input.toolCalls() != null
                && input.toolCalls().stream().anyMatch(toolCall ->
                        ToolCapabilityCatalog.META_TOOL_NAME.equals(toolCall.getName()));
        if (resetsEquippedTools && context != null) {
            prepareContext(context);
            context.get(SelectionState.class).manual = true;
        }
        return next.apply(input);
    }

    /**
     * 在每次模型调用前，根据 RuntimeContext 生成本轮专属 Tool Schema。
     *
     * @param agent 当前执行 Model Call 的 Agent；从中只读完整 Toolkit
     * @param context 当前 AgentRun 独享的运行上下文
     * @param input 上游 Middleware 传入的模型调用参数
     * @param next Middleware 链的下一个处理函数
     * @return 下游 AgentEvent 响应流
     */
    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next
    ) {
        // decision：AgentService 为当前用户轮次写入 RuntimeContext 的路由决策。
        ToolRoutingDecision decision = context == null
                ? null
                : context.get(ToolRoutingDecision.class);

        if (decision == null || !decision.appliesToModelCall()
                || agent == null || agent.getToolkit() == null) {
            return next.apply(input);
        }

        // 主模型调用过 reset_equipped_tools 后，AgentScope 的会话状态成为权威来源。
        SelectionState selection = context.get(SelectionState.class);
        if (selection != null && selection.manual) {
            return next.apply(input);
        }

        // selectedByName：按 Tool 名称去重，并保持“常驻工具在前、路由工具在后”的顺序。
        Map<String, ToolSchema> selectedByName = new LinkedHashMap<>();

        // 只保留元工具和未被能力目录分类的工具。
        // 即使共享 Toolkit 曾被其他会话激活过，也不会把其能力组带进本轮。
        if (input.tools() != null) {
            input.tools().stream()
                    .filter(this::isAlwaysVisible)
                    // schema：当前被保留的元工具或未分类 Tool Schema。
                    .forEach(schema -> selectedByName.put(schema.getName(), schema));
        }

        // 直接读取指定组的 Schema，不调用 updateToolGroups，不修改共享状态。
        // routedSchemas：从完整 Toolkit 只读取得的本轮已选能力组 Schema。
        List<ToolSchema> routedSchemas = agent.getToolkit()
                .getToolSchemas(decision.selectedGroups());
        // schema：当前加入最终结果并按名称去重的路由 Tool Schema。
        routedSchemas.forEach(schema -> selectedByName.put(schema.getName(), schema));

        // routedInput：复制原模型调用参数，只替换最终发送给模型的 tools 列表。
        ModelCallInput routedInput = new ModelCallInput(
                input.messages(),
                List.copyOf(selectedByName.values()),
                input.options(),
                input.model()
        );
        return next.apply(routedInput);
    }

    /**
     * 判断 Tool Schema 是否应无条件保留在模型上下文中。
     *
     * @param schema 当前待判断的 Tool Schema
     * @return 元工具或目录无法分类的兼容工具返回 true
     */
    private boolean isAlwaysVisible(ToolSchema schema) {
        if (schema == null || schema.getName() == null) return false;
        return ToolCapabilityCatalog.META_TOOL_NAME.equals(schema.getName())
                || capabilityCatalog.groupFor(schema.getName()).isEmpty();
    }
}
