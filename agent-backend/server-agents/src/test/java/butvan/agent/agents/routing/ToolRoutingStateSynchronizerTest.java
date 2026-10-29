package butvan.agent.agents.routing;

import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRoutingStateSynchronizerTest {

    private final ToolRoutingStateSynchronizer synchronizer =
            new ToolRoutingStateSynchronizer();

    @Test
    void activeDecisionPreservesOnlyAlreadyEquippedMethodsAndRespectsExplicitDeactivation() {
        AgentState state = AgentState.builder().build();
        state.getToolContext().setActivatedGroups(List.of("calendar", "methods", "memory"));
        var decision = new ToolRoutingDecision(ToolRoutingDecision.Status.ACTIVE,
                Set.of("web"), Map.of(), "jev-test", 0);
        assertTrue(synchronizer.apply(state, decision));
        assertEquals(List.of("methods", "web"), state.getToolContext().getActivatedGroups());
        assertFalse(synchronizer.apply(state, decision));
        // 对应元工具停用后的权威状态，不能因为先前用过方法卡而再次补回。
        state.getToolContext().setActivatedGroups(List.of());
        assertTrue(synchronizer.apply(state, decision));
        assertEquals(List.of("web"), state.getToolContext().getActivatedGroups());
    }

    @Test
    void activeDecisionReplacesSessionActivatedGroupsBeforeExecution() {
        AgentState state = AgentState.builder().build();
        state.getToolContext().setActivatedGroups(List.of("calendar"));
        ToolRoutingDecision decision = new ToolRoutingDecision(
                ToolRoutingDecision.Status.ACTIVE,
                Set.of("web"),
                Map.of("web", 0.96),
                "jev-test",
                20
        );

        boolean changed = synchronizer.apply(state, decision);

        assertTrue(changed);
        assertEquals(List.of("web"), state.getToolContext().getActivatedGroups());
    }

    @Test
    void shadowDecisionDoesNotChangeSessionActivatedGroups() {
        AgentState state = AgentState.builder().build();
        state.getToolContext().setActivatedGroups(List.of("calendar"));
        ToolRoutingDecision decision = new ToolRoutingDecision(
                ToolRoutingDecision.Status.SHADOW,
                Set.of("web"),
                Map.of("web", 0.96),
                "jev-test",
                20
        );

        boolean changed = synchronizer.apply(state, decision);

        assertFalse(changed);
        assertEquals(List.of("calendar"), state.getToolContext().getActivatedGroups());
    }
}
