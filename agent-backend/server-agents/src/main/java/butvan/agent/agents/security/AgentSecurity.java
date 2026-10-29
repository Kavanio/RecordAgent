package butvan.agent.agents.security;

import butvan.agent.agents.session.dto.SessionPermissionMode;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 智能体安全与 AgentScope PermissionContext 适配中心。
 * <p>
 * 负责将 {@link PermissionChecker} 加载的三层规则与安全模式，
 * 组装为 AgentScope 原生 {@link PermissionContextState} 并注入 HarnessAgent。
 */
@Slf4j
@Component
public class AgentSecurity {

    /** 不产生外部副作用的读取、状态查询与结果整理工具。 */
    private static final List<String> SAFE_TOOLS = List.of(
            "read_file",
            "grep_files",
            "glob_files",
            "list_files",
            "memory_search",
            "memory_get",
            "session_search",
            "session_history",
            "session_list",
            "session_recent",
            "agent_list",
            "task_output",
            "task_list",
            "wait_async_results",
            "calendar_query",
            "finance_query",
            "library_search",
            "library_get",
            "study_query",
            "acceptance_report"
    );

    /** 自动批准编辑模式额外允许的工作区编辑与常规智能体协作工具。 */
    private static final List<String> AUTO_EDIT_TOOLS = List.of(
            "write_file",
            "edit_file",
            "web_search",
            "memory_save",
            "agent_generate",
            "agent_spawn",
            "agent_send"
    );

    /**
     *
     *
     * @param checker 权限检查与配置加载器
     * @return 组装完毕的 PermissionContextState 实例
     */
    public PermissionContextState createPermissionContext(PermissionChecker checker) {
        PermissionMode configured = checker == null ? PermissionMode.DEFAULT : checker.getMode();
        return createPermissionContext(switch (configured) {
            case ACCEPT_EDITS -> SessionPermissionMode.AUTO_EDIT;
            case BYPASS -> SessionPermissionMode.FULL_ACCESS;
            default -> SessionPermissionMode.ASK;
        });
    }

    /** 为单个会话构造完整权限上下文，避免不同模式之间残留动态规则。 */
    public PermissionContextState createPermissionContext(SessionPermissionMode mode) {
        PermissionMode frameworkMode = switch (mode) {
            case ASK -> PermissionMode.DEFAULT;
            case AUTO_EDIT -> PermissionMode.ACCEPT_EDITS;
            case FULL_ACCESS -> PermissionMode.BYPASS;
        };
        PermissionContextState.Builder builder = PermissionContextState.builder().mode(frameworkMode);

        SAFE_TOOLS.forEach(toolName -> addAllow(builder, toolName, "builtInSafeTool"));

        if (mode == SessionPermissionMode.AUTO_EDIT) {
            AUTO_EDIT_TOOLS.forEach(toolName -> addAllow(builder, toolName, "autoEditTool"));
        }

        return builder.build();
    }


    private void addAllow(PermissionContextState.Builder builder, String toolName, String source) {
        builder.addAllowRule(toolName, new PermissionRule(
                toolName, null, PermissionBehavior.ALLOW, source
        ));
    }

}
