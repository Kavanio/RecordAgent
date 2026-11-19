package butvan.agent.agents.agent;

import butvan.agent.agents.context.ContextInjectionMiddleware;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.prompts.PromptBuilder;
import butvan.agent.agents.project.ProjectRegistry;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.agents.subagent.SubagentCatalog;
import butvan.agent.agents.tool.ToolRegistry;
import butvan.agent.agents.tool.ToolSchemaSelectionMiddleware;
import butvan.agent.agents.usage.TokenUsageMiddleware;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HarnessAgent 构建与缓存。
 *
 * <p>模型切换时重建 Agent；会话状态保存在独立的 agentStateStore 中，
 * 因此不会因 Agent 实例替换而丢失。</p>
 */
@Slf4j
@Component
public class AgentFactory {

    static final int DEFAULT_MAX_CACHED_AGENTS = 16;

    private final ModelHolder modelHolder;
    private final ToolRegistry toolRegistry;
    private final AgentStorageProperties storageProperties;
    private final AgentStateStore agentStateStore;
    private final TaskRepository subagentTaskRepository;
    private final MessageBus subagentMessageBus;
    private final SubagentCatalog subagentCatalog;
    private final TokenUsageMiddleware tokenUsageMiddleware;
    private final ContextInjectionMiddleware contextInjectionMiddleware;
    private final ToolSchemaSelectionMiddleware toolSchemaSelectionMiddleware;
    private final SessionCatalogService sessionCatalogService;
    private final ProjectRegistry projectRegistry;
    private final int maxCachedAgents;

    /** 按会话与项目根隔离 Agent，使用 LRU 上限避免长驻运行后实例无限增长。 */
    private final Map<AgentCacheKey, HarnessAgent> cachedAgents = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public AgentFactory(
            ModelHolder modelHolder,
            ToolRegistry toolRegistry,
            AgentStorageProperties storageProperties,
            AgentStateStore agentStateStore,
            TaskRepository subagentTaskRepository,
            MessageBus subagentMessageBus,
            SubagentCatalog subagentCatalog,
            TokenUsageMiddleware tokenUsageMiddleware,
            ContextInjectionMiddleware contextInjectionMiddleware,
            ToolSchemaSelectionMiddleware toolSchemaSelectionMiddleware,
            SessionCatalogService sessionCatalogService,
            ProjectRegistry projectRegistry
    ) {
        this(modelHolder, toolRegistry, storageProperties, agentStateStore, subagentTaskRepository,
                subagentMessageBus, subagentCatalog, tokenUsageMiddleware, contextInjectionMiddleware,
                toolSchemaSelectionMiddleware, sessionCatalogService, projectRegistry, DEFAULT_MAX_CACHED_AGENTS);
    }

    AgentFactory(
            ModelHolder modelHolder,
            ToolRegistry toolRegistry,
            AgentStorageProperties storageProperties,
            AgentStateStore agentStateStore,
            TaskRepository subagentTaskRepository,
            MessageBus subagentMessageBus,
            SubagentCatalog subagentCatalog,
            TokenUsageMiddleware tokenUsageMiddleware,
            ContextInjectionMiddleware contextInjectionMiddleware,
            ToolSchemaSelectionMiddleware toolSchemaSelectionMiddleware,
            SessionCatalogService sessionCatalogService,
            ProjectRegistry projectRegistry,
            int maxCachedAgents
    ) {
        if (maxCachedAgents < 1) {
            throw new IllegalArgumentException("maxCachedAgents 必须大于 0");
        }
        this.modelHolder = modelHolder;
        this.toolRegistry = toolRegistry;
        this.storageProperties = storageProperties;
        this.agentStateStore = agentStateStore;
        this.subagentTaskRepository = subagentTaskRepository;
        this.subagentMessageBus = subagentMessageBus;
        this.subagentCatalog = subagentCatalog;
        this.tokenUsageMiddleware = tokenUsageMiddleware;
        this.contextInjectionMiddleware = contextInjectionMiddleware;
        this.toolSchemaSelectionMiddleware = toolSchemaSelectionMiddleware;
        this.sessionCatalogService = sessionCatalogService;
        this.projectRegistry = projectRegistry;
        this.maxCachedAgents = maxCachedAgents;
    }

    /** 项目根变化时重建文件系统能力；无绑定会话的兼容入口独占一个缓存项。 */
    private record AgentCacheKey(String sessionId, Path projectRoot) {}

    /**
     * 用对象引用识别 ModelHolder 是否已经切换了模型实例。
     */
    private volatile Model cachedModel;

    /** 返回不绑定项目的通用 Agent，主要用于兼容与测试。 */
    public synchronized HarnessAgent currentAgent() {
        return currentAgent(null);
    }

    /** 按会话身份与解析后的项目根返回独享 Toolkit 的 Agent。 */
    public synchronized HarnessAgent currentAgent(String sessionId) {
        Model currentModel = modelHolder.getModel();
        if (cachedModel != currentModel) {
            cachedAgents.values().forEach(this::closeQuietly);
            cachedAgents.clear();
            cachedModel = currentModel;
        }

        Path projectRoot = resolveProjectRoot(sessionId);
        AgentCacheKey cacheKey = new AgentCacheKey(
                sessionId == null || sessionId.isBlank() ? null : sessionId, projectRoot);
        HarnessAgent existing = cachedAgents.get(cacheKey);
        if (existing != null) {
            return existing;
        }
        HarnessAgent created = createHarnessAgent(currentModel, projectRoot);
        cachedAgents.put(cacheKey, created);
        evictOverflow();
        return created;
    }

    private void evictOverflow() {
        var iterator = cachedAgents.entrySet().iterator();
        while (cachedAgents.size() > maxCachedAgents && iterator.hasNext()) {
            HarnessAgent evicted = iterator.next().getValue();
            iterator.remove();
            closeQuietly(evicted);
        }
    }

    /** 构建 Agent 时只指定根目录和状态存储，不拼接任何 sessionDir。 */
    private HarnessAgent createHarnessAgent(Model model, Path projectRoot) {
        // 模型名缺失时给兜底名，保证系统提示词可读
        String modelName = model.getModelName() == null ? "unknown-model" : model.getModelName();
        // 项目会话使用不含日常聊天模块的工程提示词；普通会话保留聊天、语言与判断规则
        String systemPrompt = projectRoot == null
                ? PromptBuilder.buildDefaultSystemPrompt(modelName, System.getProperty("user.dir"))
                : PromptBuilder.buildProjectSystemPrompt(modelName, projectRoot.toString());
        // 组装 HarnessAgent：计划模式、工具集、权限、工作区、状态存储、上下文压缩
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("butvan_agent")
                .sysPrompt(systemPrompt)
                .model(model)
                .maxIters(25)
                .middleware(new butvan.agent.agents.prompts.RuntimeDateMiddleware())
                .middleware(contextInjectionMiddleware) // 先向消息列表临时注入有界个人上下文
                .middleware(toolSchemaSelectionMiddleware)
                .middleware(tokenUsageMiddleware)
                .enablePlanMode() // 开启计划模式
                .planFileDirectory("plans")
                .toolkit(toolRegistry.getToolkit())
                .workspace(storageProperties.getWorkspaceDirectory())
                .disableWorkspaceContext()
                // 同时传播到框架子Agent，防止其重新注册本地Markdown记忆工具。
                .disableMemoryTools()
                // 自动经历仅走应用授权 seam；关闭框架整段抽取、合并与维护写入。
                .disableMemoryHooks()
                // 框架自动恢复发生在 ConfirmResult 消费之前，会误伤正常 HITL；遗留调用由应用层恢复。
                .enablePendingToolRecovery(false)
                .stateStore(agentStateStore)
                .taskRepository(subagentTaskRepository)
                .messageBus(subagentMessageBus)
                .subagents(subagentCatalog.declarations())
                .compaction(CompactionConfig.builder()
                        .flushBeforeCompact(false)
                        .triggerMessages(30)    // 30 条消息触发上下文压缩
                        .keepMessages(10)       // 压缩后保留最近 10 条
                        //.triggerTokens(131072)   // 固定 token 阈值，禁用动态 contextWindowSize 探测（避免框架向 DashScope 发送非法 URL 的探测请求）
                        .build());
        if (projectRoot != null) {
            builder.filesystem(new LocalFilesystemSpec()
                    .project(projectRoot)
                    .projectWritable(true)
                    .mode(LocalFsMode.ROOTED));
        }
        HarnessAgent agent = builder.build();
        toolRegistry.enableOnDemandSchemas(agent.getToolkit());
        return agent;
    }

    private Path resolveProjectRoot(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        String projectId = sessionCatalogService.requireActive(sessionId).projectId();
        if (projectId == null || projectId.isBlank()) return null;
        return Path.of(projectRegistry.resolve(projectId).rootPath());
    }

    private void closeQuietly(HarnessAgent agent) {
        try {
            agent.close();
        } catch (Exception e) {
            // 模型切换时旧 Agent 已不可再用，关闭失败不阻断新 Agent 创建。
            log.debug("模型切换时关闭旧 Agent 失败", e);
        }
    }
}
