package butvan.agent.agents.tool;

import io.agentscope.core.tool.Toolkit;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ToolRegistry {

    /**
     * -- GETTER --
     *  获取配置好的 AgentScope Toolkit 容器
     */
    @Getter
    private final Toolkit toolkit;

    /** capabilityCatalog：Tool 能力组的唯一目录。 */
    private final ToolCapabilityCatalog capabilityCatalog;

    /** 应用工具在框架注册完成后重新应用，保证权威实现不会被同名内置工具覆盖。 */
    private final List<AgentToolModule> toolModules;

    /**
     * 创建业务工具注册表。
     *
     * @param toolModules Spring 收集到的业务 Tool 模块
     * @param capabilityCatalog 项目唯一的 Tool 能力组目录
     */
    public ToolRegistry(
            List<AgentToolModule> toolModules,
            ToolCapabilityCatalog capabilityCatalog
    ) {
        this.capabilityCatalog = capabilityCatalog;
        this.toolModules = List.copyOf(toolModules);
        this.toolkit = new Toolkit();
        toolModules.forEach(this.toolkit::registerTool);
    }

    /**
     * 为最终 Agent Toolkit 启用按需 Schema 路由。
     *
     * <p>必须在 HarnessAgent 完成内置工具注册后调用，确保框架工具和业务工具使用同一套路由策略。</p>
     */
    public void enableOnDemandSchemas(Toolkit agentToolkit) {
        toolModules.forEach(agentToolkit::registerTool);
        ToolSchemaRoutingPolicy.apply(agentToolkit, capabilityCatalog);
    }

}
