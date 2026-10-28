package butvan.agent.agents.agent;

import butvan.agent.agents.context.ContextInjectionMiddleware;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.method.MethodCardCatalog;
import butvan.agent.agents.method.MethodCardTool;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.project.ProjectRegistry;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.agents.subagent.AgentDefinitionLoader;
import butvan.agent.agents.subagent.SubagentCatalog;
import butvan.agent.agents.tool.ToolCapabilityCatalog;
import butvan.agent.agents.tool.ToolRegistry;
import butvan.agent.agents.tool.ToolSchemaSelectionMiddleware;
import butvan.agent.agents.usage.ApproximateTokenCounter;
import butvan.agent.agents.usage.TokenUsageMiddleware;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFactoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void currentAgentDoesNotAutomaticallyInjectWorkspaceContext() throws Exception {
        Model model = new StubModel();
        ModelHolder modelHolder = new ModelHolder() {
            @Override
            public Model getModel() {
                return model;
            }
        };
        AgentStorageProperties storage = new AgentStorageProperties(temporaryDirectory);
        CurrentUserProvider currentUserProvider = new CurrentUserProvider();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ProjectRegistry projectRegistry = new ProjectRegistry(storage, currentUserProvider, objectMapper);
        SessionCatalogService sessionCatalogService = new SessionCatalogService(
                storage, currentUserProvider, objectMapper, projectRegistry);
        SubagentCatalog subagentCatalog = new SubagentCatalog(
                new AgentDefinitionLoader(), storage) {
            @Override
            public List<io.agentscope.harness.agent.subagent.SubagentDeclaration> declarations() {
                return List.of();
            }
        };
        ToolCapabilityCatalog capabilityCatalog = new ToolCapabilityCatalog();

        AgentFactory factory = new AgentFactory(
                modelHolder,
                new ToolRegistry(List.of(new MethodCardTool(new MethodCardCatalog())), capabilityCatalog),
                storage,
                new InMemoryAgentStateStore(),
                null,
                null,
                subagentCatalog,
                new TokenUsageMiddleware(new ApproximateTokenCounter(), objectMapper),
                new ContextInjectionMiddleware(),
                new ToolSchemaSelectionMiddleware(capabilityCatalog),
                sessionCatalogService,
                projectRegistry
        );

        try (HarnessAgent agent = factory.currentAgent()) {
            assertTrue(agent.getDelegate().getSysPrompt().contains("# 普通聊天"),
                    "普通会话需要聊天、语言与判断规则");
            assertTrue(agent.getDelegate().getSysPrompt().contains("# 语言与英语"),
                    "普通会话应在需要时提供适量英语表达");
            assertTrue(agent.getDelegate().getSysPrompt().contains("method_search"));
            assertFalse(agent.getDelegate().getSysPrompt().contains("Spacing Effects in Learning"),
                    "常驻 Prompt 不携带方法卡正文");
            assertFalse(agent.getDelegate().isPendingToolRecoveryEnabled(),
                    "框架自动恢复会抢先把 HITL ConfirmResult 改写为失败结果");
            assertTrue(agent.getDelegate().getMiddlewares().stream()
                    .noneMatch(WorkspaceContextMiddleware.class::isInstance));
            List<Class<?>> middlewareOrder = agent.getDelegate().getMiddlewares().stream()
                    .map(Object::getClass).toList();
            assertTrue(middlewareOrder.indexOf(ContextInjectionMiddleware.class)
                            < middlewareOrder.indexOf(ToolSchemaSelectionMiddleware.class),
                    "上下文注入必须先于 Tool Schema 路由");
            assertTrue(middlewareOrder.indexOf(ToolSchemaSelectionMiddleware.class)
                            < middlewareOrder.indexOf(TokenUsageMiddleware.class),
                    "Tool Schema 路由必须先于 Token 计数，使归因看到最终工具集合");
            assertEquals(List.of("reset_equipped_tools"), agent.getToolkit().getToolSchemas().stream()
                    .map(ToolSchema::getName).toList());
            assertTrue(schemaTokens(agent.getToolkit().getToolSchemas()) < 800,
                    "默认 Tool Schema 应保持在 800 个估算 Token 以内");
        }
    }

    @Test
    void projectSessionsUseAgentsIsolatedByCanonicalRoot() throws Exception {
        Model model = new StubModel();
        ModelHolder modelHolder = new ModelHolder() {
            @Override
            public Model getModel() {
                return model;
            }
        };
        AgentStorageProperties storage = new AgentStorageProperties(temporaryDirectory.resolve("runtime"));
        CurrentUserProvider user = new CurrentUserProvider();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ProjectRegistry projects = new ProjectRegistry(storage, user, mapper);
        SessionCatalogService sessions = new SessionCatalogService(storage, user, mapper, projects);
        SubagentCatalog subagents = new SubagentCatalog(new AgentDefinitionLoader(), storage) {
            @Override
            public List<io.agentscope.harness.agent.subagent.SubagentDeclaration> declarations() {
                return List.of();
            }
        };
        ToolCapabilityCatalog capabilityCatalog = new ToolCapabilityCatalog();
        AgentFactory factory = new AgentFactory(
                modelHolder,
                new ToolRegistry(List.of(), capabilityCatalog),
                storage,
                new InMemoryAgentStateStore(),
                null,
                null,
                subagents,
                new TokenUsageMiddleware(new ApproximateTokenCounter(), mapper),
                new ContextInjectionMiddleware(),
                new ToolSchemaSelectionMiddleware(capabilityCatalog),
                sessions,
                projects
        );
        String firstProject = projects.importProject("first",
                Files.createDirectories(temporaryDirectory.resolve("projects/first")).toString()).id();
        String secondProject = projects.importProject("second",
                Files.createDirectories(temporaryDirectory.resolve("projects/second")).toString()).id();
        String firstSession = sessions.create(new CreateSessionRequest(SessionKind.PROJECT, "first", firstProject)).id();
        String secondSession = sessions.create(new CreateSessionRequest(SessionKind.PROJECT, "second", secondProject)).id();
        String siblingSession = sessions.create(new CreateSessionRequest(SessionKind.PROJECT, "sibling", firstProject)).id();

        HarnessAgent first = factory.currentAgent(firstSession);
        HarnessAgent firstAgain = factory.currentAgent(firstSession);
        HarnessAgent second = factory.currentAgent(secondSession);
        HarnessAgent sibling = factory.currentAgent(siblingSession);

        assertSame(first, firstAgain);
        assertNotSame(first, second);
        assertNotSame(first, sibling, "同项目的不同会话也必须独享可变 Toolkit");
        assertNotSame(first.getToolkit(), sibling.getToolkit());
        String projectPrompt = first.getDelegate().getSysPrompt();
        assertTrue(projectPrompt.contains("# 任务执行规范"));
        assertTrue(projectPrompt.contains("acceptance_report"));
        assertFalse(projectPrompt.contains("# 普通聊天"),
                "项目会话不混入日常聊天与英语练习模块");
        assertFalse(projectPrompt.contains("# 语言与英语"));
        assertFalse(projectPrompt.contains("method_search"), "工程会话不主动插入生活学习指导");
        assertTrue(first.getDelegate().getSysPrompt().contains("PermissionContext"));
        first.close();
        second.close();
        sibling.close();
    }

    private int schemaTokens(List<ToolSchema> schemas) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ApproximateTokenCounter counter = new ApproximateTokenCounter();
        int total = 0;
        for (ToolSchema schema : schemas) {
            total += counter.count(mapper.writeValueAsString(java.util.Map.of(
                    "name", schema.getName(),
                    "description", schema.getDescription(),
                    "parameters", schema.getParameters()
            )));
        }
        return total;
    }

    private static final class StubModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options
        ) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "test-model";
        }
    }
}
