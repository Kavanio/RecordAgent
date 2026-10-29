package butvan.agent.agents.tool;

import butvan.agent.agents.routing.ToolRoutingDecision;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolSchemaSelectionMiddlewareTest {

    private final ToolCapabilityCatalog catalog = new ToolCapabilityCatalog();
    private final ToolSchemaSelectionMiddleware middleware =
            new ToolSchemaSelectionMiddleware(catalog);
    private final Toolkit toolkit = new Toolkit();
    private final Agent agent = mock(Agent.class);

    @BeforeEach
    void setUp() {
        registerSchema("read_file");
        registerSchema("calendar_query");
        registerSchema("method_search");
        registerSchema("diagnostic_ping");
        ToolSchemaRoutingPolicy.apply(toolkit, catalog);
        when(agent.getToolkit()).thenReturn(toolkit);
    }

    @Test
    void activeDecisionKeepsBaselineAndAddsOnlySelectedGroups() {
        RuntimeContext context = activeContext(Set.of("workspace"));
        ModelCallInput input = input(toolkit.getToolSchemas(List.of("calendar")));

        ModelCallInput routed = invokeModelCall(context, input);

        assertEquals(
                Set.of("reset_equipped_tools", "diagnostic_ping", "read_file"),
                names(routed.tools()));
    }

    @Test
    void activeDecisionKeepsEquippedMethodsButNotOtherPreviousGroups() {
        var context = activeContext(Set.of());
        var equipped = input(toolkit.getToolSchemas(List.of("methods", "calendar")));
        assertEquals(Set.of("reset_equipped_tools", "diagnostic_ping", "method_search"),
                names(invokeModelCall(context, equipped).tools()));
        var deactivated = input(toolkit.getToolSchemas(List.of("calendar")));
        assertEquals(Set.of("reset_equipped_tools", "diagnostic_ping"),
                names(invokeModelCall(context, deactivated).tools()));
    }

    @Test
    void shadowAndFallbackDecisionsPassInputThrough() {
        ModelCallInput input = input(toolkit.getToolSchemas(List.of("calendar")));

        RuntimeContext shadow = RuntimeContext.empty();
        shadow.put(ToolRoutingDecision.class, new ToolRoutingDecision(
                ToolRoutingDecision.Status.SHADOW,
                Set.of("workspace"),
                Map.of("workspace", 0.9),
                "jev-test",
                1));
        RuntimeContext fallback = RuntimeContext.empty();
        fallback.put(ToolRoutingDecision.class, ToolRoutingDecision.fallback(1));

        assertSame(input, invokeModelCall(shadow, input));
        assertSame(input, invokeModelCall(fallback, input));
    }

    @Test
    void resetEquippedToolsHandsControlBackToAgentScopeForTheRestOfTheRun() {
        RuntimeContext context = activeContext(Set.of("workspace"));
        ActingInput actingInput = new ActingInput(List.of(
                new ToolUseBlock("call-1", "reset_equipped_tools", Map.of(
                        "to_activate", List.of("calendar")))));
        middleware.onActing(agent, context, actingInput, next -> Flux.empty()).blockLast();
        ModelCallInput input = input(toolkit.getToolSchemas(List.of("calendar")));

        ModelCallInput routed = invokeModelCall(context, input);

        assertSame(input, routed);
        assertEquals(
                Set.of("reset_equipped_tools", "diagnostic_ping", "calendar_query"),
                names(routed.tools()));
    }

    @Test
    void manualSelectionSurvivesRuntimeContextCopiesAndDoesNotLeakToANewRun() {
        RuntimeContext original = activeContext(Set.of("workspace"));
        middleware.onAgent(agent, original, new AgentInput(List.of()), next -> Flux.empty()).blockLast();
        RuntimeContext actingCopy = RuntimeContext.builder(original).build();
        middleware.onActing(agent, actingCopy, new ActingInput(List.of(
                new ToolUseBlock("reset", "reset_equipped_tools", Map.of("to_activate", List.of("calendar"))))),
                next -> Flux.empty()).blockLast();
        ModelCallInput input = input(toolkit.getToolSchemas(List.of("calendar")));
        assertSame(input, invokeModelCall(RuntimeContext.builder(original).build(), input));
        assertEquals(Set.of("reset_equipped_tools", "diagnostic_ping", "read_file"),
                names(invokeModelCall(activeContext(Set.of("workspace")), input).tools()));
    }

    @Test
    void separateRuntimeContextsDoNotShareRoutingDecisions() {
        RuntimeContext workspaceContext = activeContext(Set.of("workspace"));
        RuntimeContext calendarContext = activeContext(Set.of("calendar"));
        ModelCallInput input = input(toolkit.getToolSchemas(List.of()));

        CompletableFuture<ModelCallInput> workspace = CompletableFuture.supplyAsync(
                () -> invokeModelCall(workspaceContext, input));
        CompletableFuture<ModelCallInput> calendar = CompletableFuture.supplyAsync(
                () -> invokeModelCall(calendarContext, input));

        assertEquals(Set.of("reset_equipped_tools", "diagnostic_ping", "read_file"),
                names(workspace.join().tools()));
        assertEquals(Set.of("reset_equipped_tools", "diagnostic_ping", "calendar_query"),
                names(calendar.join().tools()));
    }

    private RuntimeContext activeContext(Set<String> groups) {
        RuntimeContext context = RuntimeContext.empty();
        context.put(ToolRoutingDecision.class, new ToolRoutingDecision(
                ToolRoutingDecision.Status.ACTIVE,
                groups,
                Map.of(),
                "jev-test",
                1));
        return context;
    }

    private ModelCallInput invokeModelCall(RuntimeContext context, ModelCallInput input) {
        AtomicReference<ModelCallInput> captured = new AtomicReference<>();
        middleware.onModelCall(agent, context, input, routed -> {
            captured.set(routed);
            return Flux.empty();
        }).blockLast();
        return captured.get();
    }

    private ModelCallInput input(List<ToolSchema> tools) {
        return new ModelCallInput(List.of(), tools, null, null);
    }

    private Set<String> names(List<ToolSchema> schemas) {
        return schemas.stream().map(ToolSchema::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void registerSchema(String name) {
        toolkit.registerSchema(ToolSchema.builder()
                .name(name)
                .description(name)
                .parameters(Map.of("type", "object"))
                .build());
    }
}
