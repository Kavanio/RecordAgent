package butvan.agent.agents.tool;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static final Set<String> CURRENT_ROUTED_TOOL_NAMES = Set.of(
            "acceptance_report", "agent_list", "agent_send", "agent_spawn", "calendar_create",
            "calendar_delete", "calendar_query", "calendar_set_todo_completed", "calendar_update",
            "edit_file", "execute", "finance_create_account", "finance_query",
            "finance_record_transaction", "finance_transfer", "glob_files", "grep_files", "library_create",
            "library_get", "library_recycle", "library_search", "library_update", "list_files",
            "load_skill_through_path", "memory_get", "memory_save", "memory_search", "method_search", "plan_enter",
            "plan_exit", "plan_write", "read_file", "session_history", "session_recent", "session_list",
            "session_search", "study_create_manual", "study_delete", "study_finish", "study_query",
            "study_start", "study_update", "task_cancel", "task_list", "task_output",
            "wait_async_results", "web_search", "write_file"
    );

    @Test
    void registersEveryDiscoveredToolModule() {
        ToolRegistry registry = new ToolRegistry(
                List.of(new FirstTool(), new SecondTool()), new ToolCapabilityCatalog());

        assertEquals(List.of("first_test_tool", "second_test_tool"),
                registry.getToolkit().getToolNames().stream().sorted().toList());
    }

    @Test
    void exposesOnlyMetaAndUnclassifiedToolsUntilAGroupIsActivated() {
        ToolRegistry registry = new ToolRegistry(List.of(), new ToolCapabilityCatalog());
        Toolkit toolkit = registry.getToolkit();
        toolkit.registerTool(new RoutedTools());
        toolkit.registerTool(new AlwaysVisibleTool());

        registry.enableOnDemandSchemas(toolkit);
        registry.enableOnDemandSchemas(toolkit);

        assertEquals(Set.of("reset_equipped_tools", "diagnostic_ping"), schemaNames(toolkit));
        assertFalse(toolkit.getToolGroup("calendar").isActive());

        toolkit.updateToolGroups(List.of("calendar"), true);

        assertEquals(Set.of("reset_equipped_tools", "diagnostic_ping", "calendar_query"),
                schemaNames(toolkit));
        assertEquals(Set.of("reset_equipped_tools", "diagnostic_ping", "calendar_query"),
                toolkit.getToolSchemas(List.of("calendar")).stream()
                        .map(ToolSchema::getName)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(toolkit.getToolGroup("calendar").isActive());
    }

    @Test
    void routesEveryCurrentlyRegisteredToolName() {
        ToolRegistry registry = new ToolRegistry(List.of(), new ToolCapabilityCatalog());
        Toolkit toolkit = registry.getToolkit();
        CURRENT_ROUTED_TOOL_NAMES.forEach(name -> toolkit.registerSchema(ToolSchema.builder()
                .name(name)
                .description(name)
                .parameters(Map.of("type", "object"))
                .build()));

        registry.enableOnDemandSchemas(toolkit);

        assertEquals(Set.of("reset_equipped_tools"), schemaNames(toolkit));
    }

    private Set<String> schemaNames(Toolkit toolkit) {
        return toolkit.getToolSchemas().stream().map(schema -> schema.getName()).collect(
                java.util.stream.Collectors.toSet());
    }

    private static final class FirstTool implements AgentToolModule {
        @Tool(name = "first_test_tool", description = "第一个注册测试工具")
        public String run(@ToolParam(name = "value", description = "测试值") String value) {
            return value;
        }
    }

    private static final class SecondTool implements AgentToolModule {
        @Tool(name = "second_test_tool", description = "第二个注册测试工具")
        public String run(@ToolParam(name = "value", description = "测试值") String value) {
            return value;
        }
    }

    private static final class RoutedTools {
        @Tool(name = "calendar_query", description = "查询日历")
        public String query() {
            return "ok";
        }
    }

    private static final class AlwaysVisibleTool {
        @Tool(name = "diagnostic_ping", description = "诊断未知工具")
        public String ping() {
            return "ok";
        }
    }
}
