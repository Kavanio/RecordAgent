package butvan.agent.agents.tool;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;

import java.util.List;

/**
 * 将完整工具目录按能力域延迟暴露给模型，避免每次 Model Call 重复携带全部 Schema。
 *
 * <p>工具仍完整注册在 Toolkit 中；默认只暴露 AgentScope 的元工具，模型通过
 * {@code reset_equipped_tools} 为当前会话启用所需能力组。无法匹配的新增工具保持常驻，
 * 避免工具升级时因路由表尚未同步而静默丢失能力。</p>
 */
final class ToolSchemaRoutingPolicy {

    private ToolSchemaRoutingPolicy() {}

    /**
     * 将 Toolkit 中已注册的工具划入能力组，并注册常驻元工具。
     *
     * @param toolkit 当前 HarnessAgent 使用的完整工具容器
     * @param catalog 项目唯一的 Tool 能力组目录
     */
    static void apply(Toolkit toolkit, ToolCapabilityCatalog catalog) {
        // capability：当前正在创建或检查的单个能力组定义。
        for (ToolCapabilityCatalog.Capability capability : catalog.capabilities()) {
            if (toolkit.getToolGroup(capability.name()) == null) {
                toolkit.createToolGroup(
                        capability.name(),
                        capability.description(),
                        false
                );
            }
        }

        // toolName：Toolkit 当前已经注册的单个 Tool 名称。
        for (String toolName : List.copyOf(toolkit.getToolNames())) {
            if (ToolCapabilityCatalog.META_TOOL_NAME.equals(toolName)) continue;
            // group：toolName 匹配到的能力组。
            catalog.groupFor(toolName)
                    .ifPresent(group -> moveToGroup(toolkit, toolName, group.name()));
        }

        if (toolkit.getTool(ToolCapabilityCatalog.META_TOOL_NAME) == null) {
            toolkit.registerMetaTool();
        }
    }

    /**
     * 将指定 Tool 重新注册到目标能力组。
     *
     * @param toolkit 当前 Agent 使用的工具容器
     * @param toolName 需要移动的 Tool 名称
     * @param groupName 目标能力组名称
     */
    private static void moveToGroup(
            Toolkit toolkit,
            String toolName,
            String groupName
    ) {
        AgentTool tool = toolkit.getTool(toolName);

        if (tool == null) return;

        if (("memory_search".equals(toolName) || "memory_get".equals(toolName))
                && !(tool instanceof BoundedMemoryTool)) tool = new BoundedMemoryTool(tool);

        toolkit.registration()
                .agentTool(tool)
                .group(groupName)
                .apply();
    }
}
