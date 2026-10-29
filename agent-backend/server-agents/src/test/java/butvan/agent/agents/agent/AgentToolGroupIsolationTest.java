package butvan.agent.agents.agent;

import butvan.agent.agents.context.ContextInjectionMiddleware;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.method.MethodCardCatalog;
import butvan.agent.agents.method.MethodCardTool;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.routing.ToolRoutingDecision;
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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实 AgentFactory／Harness／工具执行链复现另一会话停用分组造成的 Unauthorized。 */
class AgentToolGroupIsolationTest {
    @TempDir Path directory;

    @Test
    void manualGroupSelectionSurvivesEveryModelCallInAnActiveRoutedTurn() {
        var steps = new AtomicInteger();
        Model model = new Model() {
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                                       GenerateOptions options) {
                // 框架隐藏的维护调用单独回应，不能把它冒充聊天步骤。
                if (tools == null) return Flux.just(response(TextBlock.builder().text("NO_REPLY").build()));
                int step = steps.getAndIncrement();
                if (step > 0) assertTrue(tools.stream().anyMatch(t -> "method_search".equals(t.getName())),
                        "元工具启用后，后续模型输入不应再次丢失 method_search：step=" + step);
                if (step == 0) return Flux.just(response(ToolUseBlock.builder().id("activate")
                        .name("reset_equipped_tools").input(Map.of("to_activate", List.of("methods")))
                        .content("{\"to_activate\":[\"methods\"]}").build()));
                if (step < 3) return Flux.just(response(lookup("lookup-" + step)));
                return Flux.just(response(TextBlock.builder().text("完成").build()));
            }
            @Override public String getModelName() { return "group-regression"; }
        };
        var fixture = fixture(model);
        String sid = fixture.sessions().create(new CreateSessionRequest(SessionKind.GENERAL, "group", null)).id();
        try (HarnessAgent agent = fixture.factory().currentAgent(sid)) {
            RuntimeContext rc = context(sid);
            rc.put(ToolRoutingDecision.class, new ToolRoutingDecision(
                    ToolRoutingDecision.Status.ACTIVE, Set.of(), Map.of(), "fake", 0));
            agent.streamEvents(new UserMessage("连续查询"), rc).blockLast(Duration.ofSeconds(5));
            assertEquals(4, steps.get());
            assertTrue(agent.getDelegate().getAgentState("test-user", sid)
                    .getToolContext().getActivatedGroups().contains("methods"));
        }
    }

    private ChatResponse response(ContentBlock content) {
        return ChatResponse.builder().id(java.util.UUID.randomUUID().toString())
                .content(List.of(content)).finishReason(content instanceof ToolUseBlock ? "tool_calls" : "stop").build();
    }

    private Fixture fixture(Model model) {
        var storage = new AgentStorageProperties(directory);
        var user = new CurrentUserProvider();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var projects = new ProjectRegistry(storage, user, mapper);
        var sessions = new SessionCatalogService(storage, user, mapper, projects);
        var catalog = new ToolCapabilityCatalog();
        var holder = new ModelHolder() { @Override public Model getModel() { return model; } };
        var subagents = new SubagentCatalog(new AgentDefinitionLoader(), storage) {
            @Override public List<io.agentscope.harness.agent.subagent.SubagentDeclaration> declarations() {
                return List.of();
            }
        };
        return new Fixture(new AgentFactory(holder,
                new ToolRegistry(List.of(new MethodCardTool(new MethodCardCatalog())), catalog), storage,
                new InMemoryAgentStateStore(), null, null, subagents,
                new TokenUsageMiddleware(new ApproximateTokenCounter(), mapper), new ContextInjectionMiddleware(),
                new ToolSchemaSelectionMiddleware(catalog), sessions, projects), sessions);
    }

    private record Fixture(AgentFactory factory, SessionCatalogService sessions) {}

    @Test
    void anotherConversationCannotDeactivateAVisibleMethodTool() throws Exception {
        var model = new InterleavedModel();
        var storage = new AgentStorageProperties(directory);
        var user = new CurrentUserProvider();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var projects = new ProjectRegistry(storage, user, mapper);
        var sessions = new SessionCatalogService(storage, user, mapper, projects);
        var catalog = new ToolCapabilityCatalog();
        var holder = new ModelHolder() { @Override public Model getModel() { return model; } };
        var subagents = new SubagentCatalog(new AgentDefinitionLoader(), storage) {
            @Override public List<io.agentscope.harness.agent.subagent.SubagentDeclaration> declarations() {
                return List.of();
            }
        };
        var factory = new AgentFactory(holder,
                new ToolRegistry(List.of(new MethodCardTool(new MethodCardCatalog())), catalog),
                storage, new InMemoryAgentStateStore(), null, null, subagents,
                new TokenUsageMiddleware(new ApproximateTokenCounter(), mapper),
                new ContextInjectionMiddleware(), new ToolSchemaSelectionMiddleware(catalog), sessions, projects);
        String firstId = sessions.create(new CreateSessionRequest(SessionKind.GENERAL, "first", null)).id();
        String secondId = sessions.create(new CreateSessionRequest(SessionKind.GENERAL, "second", null)).id();
        HarnessAgent first = factory.currentAgent(firstId);
        HarnessAgent second = factory.currentAgent(secondId);
        try {
            first.getDelegate().getAgentState("test-user", firstId)
                    .getToolContext().setActivatedGroups(List.of("methods"));
            first.getDelegate().saveAgentState("test-user", firstId);
            var firstRun = first.streamEvents(new UserMessage("查主动回忆"), context(firstId))
                    .collectList().toFuture();
            model.firstEntered.get(5, TimeUnit.SECONDS);
            second.streamEvents(new UserMessage("随便聊聊"), context(secondId))
                    .blockLast(Duration.ofSeconds(5));
            model.release.tryEmitValue(ChatResponse.builder().id("lookup")
                    .content(List.of(lookup("lookup")))
                    .finishReason("tool_calls").build());
            firstRun.get(5, TimeUnit.SECONDS);
            assertNotNull(model.toolResult);
            assertFalse(model.toolResult.contains("Unauthorized tool call"), model.toolResult);
            assertTrue(model.toolResult.contains("retrieval-practice-v1"), model.toolResult);
            assertTrue(first.getDelegate().getAgentState("test-user", firstId)
                    .getToolContext().getActivatedGroups().contains("methods"));
            assertTrue(second.getDelegate().getAgentState("test-user", secondId)
                    .getToolContext().getActivatedGroups().isEmpty());
            assertSame(first, factory.currentAgent(firstId));
            first.streamEvents(new UserMessage("再查一次"), context(firstId)).blockLast(Duration.ofSeconds(5));
            assertTrue(model.toolResult.contains("retrieval-practice-v1"), model.toolResult);
            assertFalse(model.toolResult.contains("Unauthorized tool call"), model.toolResult);
        } finally {
            model.release.tryEmitEmpty();
            first.close();
            if (second != first) second.close();
        }
    }

    private RuntimeContext context(String sessionId) {
        return RuntimeContext.builder().userId("test-user").sessionId(sessionId).build();
    }

    private static ToolUseBlock lookup(String id) {
        return ToolUseBlock.builder().id(id).name("method_search")
                .input(java.util.Map.of("query", "主动回忆"))
                .content("{\"query\":\"主动回忆\"}").build();
    }

    private static final class InterleavedModel implements Model {
        final CompletableFuture<Void> firstEntered = new CompletableFuture<>();
        final Sinks.One<ChatResponse> release = Sinks.one();
        volatile String toolResult;

        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                                   GenerateOptions options) {
            Msg last = messages.getLast();
            if (last.getRole() == MsgRole.TOOL) {
                toolResult = last.getContentBlocks(ToolResultBlock.class).stream()
                        .flatMap(result -> result.getOutput().stream()).filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast).map(TextBlock::getText)
                        .collect(java.util.stream.Collectors.joining());
            } else if ("查主动回忆".equals(last.getTextContent())) {
                assertTrue(tools.stream().anyMatch(tool -> "method_search".equals(tool.getName())));
                firstEntered.complete(null);
                return release.asMono().flux();
            } else if ("再查一次".equals(last.getTextContent())) {
                assertTrue(tools.stream().anyMatch(tool -> "method_search".equals(tool.getName())));
                return Flux.just(ChatResponse.builder().id("lookup-again")
                        .content(List.of(lookup("lookup-again")))
                        .finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder().id("done")
                    .content(List.of(TextBlock.builder().text("完成").build()))
                    .finishReason("stop").build());
        }

        @Override public String getModelName() { return "isolated-test"; }
    }
}
