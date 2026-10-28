package butvan.agent.agents.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 项目内 Tool 能力组的唯一目录。
 */
@Component
public class ToolCapabilityCatalog {

    /** AgentScope 用于重选会话能力组的常驻元工具名称。 */
    public static final String META_TOOL_NAME = "reset_equipped_tools";

    /** capabilities：项目当前支持的全部能力组及其 Tool 名称匹配规则。 */
    private final List<Capability> capabilities = List.of(
            capability("workspace", "读写、搜索项目文件以及执行本地命令；代码和本机任务需要启用",
                    names("read_file", "write_file", "edit_file", "list_files", "glob_files",
                            "grep_files", "execute", "custom_bash")),
            capability("memory", "检索和保存长期记忆、搜索当前或历史会话",
                    prefixes("memory_", "session_")),
            capability("planning", "进入或维护计划模式，并在任务完成后生成验收报告",
                    nameOrPrefix("acceptance_report", "plan_")),
            capability("delegation", "创建、联系和等待子 Agent，以及管理异步任务",
                    prefixes("agent_", "task_", "wait_async_")),
            capability("skills", "按路径加载任务所需的 Skill 说明",
                    prefixes("load_skill_")),
            capability("methods", "按需查带一手来源、适用条件和限制的学习方法卡；只读，不创建目标或待办",
                    names("method_search")),
            capability("web", "搜索互联网中的实时信息与外部资料",
                    names("web_search")),
            capability("calendar", "查询、创建、更新、完成或删除日程与待办",
                    prefixes("calendar_")),
            capability("finance", "查询财务数据、创建账户和记录收支",
                    prefixes("finance_")),
            capability("library", "搜索、读取、创建、更新或回收项目内部资料",
                    prefixes("library_")),
            capability("study", "查询、开始、结束、补录、更新或删除学习记录",
                    prefixes("study_"))
    );

    /**
     * 返回全部能力组定义。
     *
     * @return 不可变的能力组定义列表
     */
    public List<Capability> capabilities() {
        return capabilities;
    }

    /**
     * 提取全部能力组名称，供 Jev 构建候选问题。
     *
     * @return 不可变且不重复的能力组名称集合
     */
    public Set<String> groupNames() {
        return capabilities.stream().map(Capability::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 根据 Tool 名称查找它所属的能力组。
     *
     * @param toolName Tool Schema 中的唯一工具名称
     * @return 匹配到的能力组；未分类时返回空
     */
    public Optional<Capability> groupFor(String toolName) {
        if (toolName == null) return Optional.empty();
        // item：流处理中正在检查的单个能力组。
        return capabilities.stream().filter(item -> item.matches(toolName)).findFirst();
    }

    /**
     * 创建一条能力组定义，减少目录初始化时的重复样板代码。
     *
     * @param name 能力组的稳定英文名称
     * @param description 提供给主模型和 Jev 的中文能力说明
     * @param matcher 判断某个 Tool 是否属于该组的规则
     * @return 新的能力组定义
     */
    private static Capability capability(
            String name,
            String description,
            Predicate<String> matcher
    ) {
        return new Capability(name, description, matcher);
    }

    /**
     * 创建“精确名称集合”匹配器。
     *
     * @param names 允许匹配的 Tool 名称列表
     * @return Tool 名称匹配规则
     */
    private static Predicate<String> names(String... names) {
        // values：由可变参数转换成的不可变名称集合，用于常量时间查找。
        Set<String> values = Set.of(names);
        return values::contains;
    }

    /**
     * 创建“名称前缀”匹配器。
     *
     * @param prefixes 一个或多个 Tool 名称前缀
     * @return Tool 名称匹配规则
     */
    private static Predicate<String> prefixes(String... prefixes) {
        // values：保存全部允许前缀，供返回的 Predicate 闭包读取。
        List<String> values = List.of(prefixes);
        // toolName：Predicate 每次判断时收到的具体 Tool 名称。
        return toolName -> values.stream().anyMatch(toolName::startsWith);
    }

    /**
     * 创建“一个精确名称或一个前缀”匹配器。
     *
     * @param name 允许精确匹配的 Tool 名称
     * @param prefix 允许匹配的 Tool 名称前缀
     * @return Tool 名称匹配规则
     */
    private static Predicate<String> nameOrPrefix(String name, String prefix) {
        // toolName：Predicate 每次判断时收到的具体 Tool 名称。
        return toolName -> name.equals(toolName) || toolName.startsWith(prefix);
    }

    /**
     * 单个 Tool 能力组定义。
     *
     * @param name 能力组的稳定英文名称
     * @param description 能力组用途说明
     * @param matcher Tool 名称匹配规则
     */
    public record Capability(
            String name,
            String description,
            Predicate<String> matcher
    ) {
        /**
         * 判断给定 Tool 是否属于当前能力组。
         *
         * @param toolName 待判断的 Tool 名称
         * @return 属于当前组时返回 true
         */
        public boolean matches(String toolName) {
            return matcher.test(toolName);
        }
    }
}
