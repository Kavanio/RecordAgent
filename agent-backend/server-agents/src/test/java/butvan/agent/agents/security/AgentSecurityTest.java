package butvan.agent.agents.security;

import butvan.agent.agents.session.dto.SessionPermissionMode;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSecurityTest {

    private final AgentSecurity security = new AgentSecurity();

    @Test
    void autoEditAllowsRoutineAgentWorkButLeavesRiskyOperationsUnruled() {
        PermissionContextState context = security.createPermissionContext(SessionPermissionMode.AUTO_EDIT);

        assertEquals(PermissionMode.ACCEPT_EDITS, context.getMode());
        assertTrue(context.getAllowRules().containsKey("write_file"));
        assertTrue(context.getAllowRules().containsKey("edit_file"));
        assertTrue(context.getAllowRules().containsKey("web_search"));
        assertTrue(context.getAllowRules().containsKey("agent_spawn"));
        assertTrue(context.getAllowRules().containsKey("acceptance_report"));
        assertTrue(context.getAllowRules().containsKey("calendar_query"));
        assertTrue(context.getAllowRules().containsKey("finance_query"));
        assertTrue(context.getAllowRules().containsKey("library_search"));
        assertTrue(context.getAllowRules().containsKey("library_get"));
        assertTrue(context.getAllowRules().containsKey("study_query"));
        assertFalse(context.getAllowRules().containsKey("plan_exit"));
        assertFalse(context.getAllowRules().containsKey("calendar_create"));
        assertFalse(context.getAllowRules().containsKey("finance_record_transaction"));
        assertFalse(context.getAllowRules().containsKey("execute"));
        assertFalse(context.getAllowRules().containsKey("custom_bash"));
        assertFalse(context.getAllowRules().containsKey("task_cancel"));
    }

    @Test
    void askAndFullAccessDoNotCarryAutoEditRules() {
        PermissionContextState ask = security.createPermissionContext(SessionPermissionMode.ASK);
        PermissionContextState full = security.createPermissionContext(SessionPermissionMode.FULL_ACCESS);

        assertEquals(PermissionMode.DEFAULT, ask.getMode());
        assertEquals(PermissionMode.BYPASS, full.getMode());
        assertFalse(ask.getAllowRules().containsKey("write_file"));
        assertFalse(full.getAllowRules().containsKey("write_file"));
        assertTrue(ask.getAllowRules().containsKey("acceptance_report"));
        assertTrue(ask.getAllowRules().containsKey("session_recent"));
        assertFalse(ask.getAllowRules().containsKey("memory_write"));
        assertFalse(ask.getAllowRules().containsKey("plan_exit"));
    }
}
