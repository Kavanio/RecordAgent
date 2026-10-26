package butvan.agent.agents.agent;

import butvan.agent.agents.agent.permission.PendingApproval;
import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.permission.PermissionToolDecision;
import butvan.agent.agents.agent.run.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖飞书全权限渠道使用的自动批准与挂起查询逻辑。
 */
class PendingApprovalStoreTest {

    @Test
    void approveAllDecidesEveryTool() {
        AgentRun run = new AgentRun("session-1", "local-default", "turn-1",
                RuntimeContext.builder().userId("local-default").sessionId("session-1").build());
        PendingApproval approval = new PendingApproval(run, List.of(
                new ToolUseBlock("call-1", "execute", Map.of("command", "ls")),
                new ToolUseBlock("call-2", "write_file", Map.of("path", "/tmp/a.txt"))));

        approval.approveAll();

        assertTrue(approval.allDecided());
        assertTrue(approval.toConfirmResults().stream().allMatch(result -> result.isConfirmed()));
        assertTrue(approval.isWaiting());
    }

    @Test
    void hasPendingMatchesSessionAndUser() {
        AgentRun run = new AgentRun("session-1", "local-default", "turn-1",
                RuntimeContext.builder().userId("local-default").sessionId("session-1").build());
        PendingApprovalStore store = new PendingApprovalStore();
        store.save(new PendingApproval(run, List.of(
                new ToolUseBlock("call-1", "execute", Map.of("command", "ls")))));

        assertTrue(store.hasPending("local-default", "session-1"));
        assertFalse(store.hasPending("local-default", "session-2"));
        assertFalse(store.hasPending("other-user", "session-1"));
    }

    @Test
    void clearSessionRemovesPendingApprovalAndRememberedDecision() {
        ToolUseBlock tool = new ToolUseBlock("call-1", "execute", Map.of("command", "ls"));
        AgentRun run = new AgentRun("session-1", "local-default", "turn-1",
                RuntimeContext.builder().userId("local-default").sessionId("session-1").build());
        PendingApprovalStore store = new PendingApprovalStore();
        store.save(new PendingApproval(run, List.of(tool)));
        store.remember("local-default", "session-1", tool, true);

        store.clearSession("local-default", "session-1");

        assertFalse(store.hasPending("local-default", "session-1"));
        assertTrue(store.remembered("local-default", "session-1", tool).isEmpty());
    }

    @Test
    void currentRestoresPendingApprovalAndResumeRequiresOriginalRunId() {
        AgentRun run = new AgentRun("session-1", "local-default", "turn-1",
                RuntimeContext.builder().userId("local-default").sessionId("session-1").build());
        PendingApproval approval = new PendingApproval(run, List.of(
                new ToolUseBlock("call-1", "custom_bash", Map.of("command", "ls"))), "run-1");
        PendingApprovalStore store = new PendingApprovalStore();
        store.save(approval);

        var view = store.current("local-default", "session-1").orElseThrow();
        assertEquals("run-1", view.runId());
        assertEquals("call-1", view.tools().getFirst().toolCallId());
        assertThrows(IllegalArgumentException.class,
                () -> store.claimForResume(approval.approvalId(), "local-default", "session-1", "other-run"));

        approval.decide("call-1", true);
        var readyView = store.current("local-default", "session-1").orElseThrow();
        assertTrue(readyView.readyToResume());
        assertTrue(readyView.tools().isEmpty());
        store.claimForResume(approval.approvalId(), "local-default", "session-1", "run-1");
        assertFalse(approval.isWaiting());
        assertThrows(IllegalArgumentException.class,
                () -> store.claimForResume(approval.approvalId(), "local-default", "session-1", "run-1"));
    }

    @Test
    void batchDecisionRequiresEveryPendingToolAndCommitsAtomically() {
        AgentRun run = new AgentRun("session-1", "local-default", "turn-1",
                RuntimeContext.builder().userId("local-default").sessionId("session-1").build());
        PendingApproval approval = new PendingApproval(run, List.of(
                new ToolUseBlock("call-1", "execute", Map.of("command", "ls")),
                new ToolUseBlock("call-2", "write_file", Map.of("path", "/tmp/a.txt"))));

        assertThrows(IllegalArgumentException.class, () -> approval.decideBatch(List.of(
                new PermissionToolDecision("call-1", true, false))));
        assertFalse(approval.allDecided());
        assertEquals(2, approval.pendingTools().size());

        approval.decideBatch(List.of(
                new PermissionToolDecision("call-1", true, false),
                new PermissionToolDecision("call-2", false, false)));
        assertTrue(approval.allDecided());
        assertEquals(List.of(true, false), approval.toConfirmResults().stream()
                .map(result -> result.isConfirmed()).toList());
    }

    @Test
    void rejectsSecondPendingApprovalForSameSession() {
        AgentRun run = new AgentRun("session-1", "local-default", "turn-1",
                RuntimeContext.builder().userId("local-default").sessionId("session-1").build());
        PendingApprovalStore store = new PendingApprovalStore();
        store.save(new PendingApproval(run, List.of(
                new ToolUseBlock("call-1", "custom_bash", Map.of("command", "ls"))), "run-1"));

        assertThrows(IllegalArgumentException.class, () -> store.save(new PendingApproval(run, List.of(
                new ToolUseBlock("call-2", "custom_bash", Map.of("command", "pwd"))), "run-2")));
        assertThrows(IllegalArgumentException.class,
                () -> store.requireNoPending("local-default", "session-1"));
    }

    @Test
    void cancellationChecksRunAndAtomicallyReleasesUnapprovedBatch() {
        AgentRun run = new AgentRun("session", "owner", "turn", null);
        PendingApproval approval = new PendingApproval(run,
                List.of(new ToolUseBlock("tool", "custom_bash", Map.of("command", "test"))), "run");
        PendingApprovalStore store = new PendingApprovalStore(); store.save(approval);
        assertThrows(IllegalArgumentException.class, () -> store.claimForCancellation(approval.approvalId(), "owner", "session", "wrong"));
        assertTrue(store.hasPending("owner", "session"));
        var cancelled = store.claimForCancellation(approval.approvalId(), "owner", "session", "run");
        assertFalse(cancelled.allDecided()); assertFalse(store.hasPending("owner", "session"));
        assertThrows(IllegalArgumentException.class, () -> store.claimForResume(approval.approvalId(), "owner", "session", "run"));
    }

    @Test
    void claimingResumeReleasesSessionSlotForNextApprovalBatch() {
        AgentRun run = new AgentRun("session-1", "local-default", "turn-1",
                RuntimeContext.builder().userId("local-default").sessionId("session-1").build());
        PendingApprovalStore store = new PendingApprovalStore();
        PendingApproval first = new PendingApproval(run, List.of(
                new ToolUseBlock("call-1", "execute", Map.of("command", "ls"))), "run-1");
        store.save(first);
        first.decide("call-1", true);

        store.claimForResume(first.approvalId(), "local-default", "session-1", "run-1");
        assertFalse(store.hasPending("local-default", "session-1"));

        PendingApproval next = new PendingApproval(run, List.of(
                new ToolUseBlock("call-2", "execute", Map.of("command", "pwd"))), "run-1");
        store.save(next);
        assertTrue(store.hasPending("local-default", "session-1"));
    }
}
