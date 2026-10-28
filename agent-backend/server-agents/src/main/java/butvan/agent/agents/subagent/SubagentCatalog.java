package butvan.agent.agents.subagent;

import butvan.agent.agents.storage.AgentStorageProperties;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 目录：内置 + 项目级 + 用户级，后加载覆盖先加载（同名后者生效）。
 */
@Component
public class SubagentCatalog {

    private final Map<String, ButvanSubagentSpec> specs = new LinkedHashMap<>();

    public SubagentCatalog(AgentDefinitionLoader loader, AgentStorageProperties storage) {
        List<String> toolNames = List.of(); // 占位；build() 时由 AgentFactory 重新加载
        loader.loadBuiltins(toolNames).forEach(s -> specs.put(s.name(), s));
        if (storage != null) {
            // 项目级：workspace 同级 .butvan-agent/agents（目录不存在时 loadDir 返回空）
            loader.loadDir(storage.getWorkspaceDirectory().resolve("agents"), toolNames)
                    .forEach(s -> specs.put(s.name(), s));
        }
    }

    /**
     * 用真实工具全集重建目录（内置与目录定义的黑名单转换需要全集）。
     * AgentFactory 构建 Agent 后调用一次。
     */
    public synchronized void refresh(List<String> allToolNames) {
        // 清空后重新加载；本类构造时只放了占位数据
        specs.clear();
        AgentDefinitionLoader loader = new AgentDefinitionLoader();
        loader.loadBuiltins(allToolNames).forEach(s -> specs.put(s.name(), s));
        // 用户级与项目级按需补充（见第 9 步 config 后改为从 LocalConfigService 读路径）
    }

    /** 停稳后的出厂重置只恢复内置定义，不重新读取已删除的个人工作区。 */
    public synchronized void resetToBuiltins() {
        refresh(List.of());
    }

    public synchronized List<SubagentDeclaration> declarations() {
        return specs.values().stream().map(ButvanSubagentSpec::declaration).toList();
    }

    public synchronized ButvanSubagentSpec get(String name) {
        return specs.get(name);
    }

    public synchronized List<String> names() {
        return new ArrayList<>(specs.keySet());
    }
}
