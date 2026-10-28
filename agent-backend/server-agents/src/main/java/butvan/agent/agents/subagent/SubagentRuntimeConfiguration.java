package butvan.agent.agents.subagent;

import butvan.agent.agents.storage.AgentStorageProperties;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * SubAgent 运行时三件套：工作区、后台任务仓库、消息总线。
 *
 * <p>三者共享同一个 workspace 根目录：任务文件落在
 * {@code agents/butvan_agent/tasks/<sessionId>.json}，消息落在 {@code /bus/...}，
 * 与主 Agent 的 workspace 是同一棵目录树，因此 AgentScope 事件与文件都可见。</p>
 */
@Configuration
public class SubagentRuntimeConfiguration {


    @Bean
    public WorkspaceManager subagentWorkspaceManager(AgentStorageProperties storageProperties) {
        Path workspace = storageProperties.getWorkspaceDirectory();
        AbstractFilesystem filesystem = new LocalFilesystemSpec()
                .toFilesystem(workspace, IsolationScope.USER.toNamespaceFactory());
        // 共享任务仓库不需要可选全文索引；避免启动时打开旧记忆索引阻碍恢复清理。
        return new WorkspaceManager(workspace, filesystem, null);

    }

    /**
     * 后台任务仓库
     * @return
     */
    @Bean(destroyMethod = "shutdown")
    public WorkspaceTaskRepository subagentTaskRepository(
            WorkspaceManager subagentWorkspaceManager,
            ApplicationEventPublisher eventPublisher) {
        return new PublishingWorkspaceTaskRepository(
                subagentWorkspaceManager, "butvan_agent", eventPublisher);
    }

    @Bean(destroyMethod = "close")
    public MessageBus subagentMessageBus(WorkspaceManager subagentWorkspaceManager) {
        return new WorkspaceMessageBus(subagentWorkspaceManager.getFilesystem(), "/bus");
    }


}
