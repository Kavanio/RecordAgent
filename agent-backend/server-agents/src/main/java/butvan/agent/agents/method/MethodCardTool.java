package butvan.agent.agents.method;

import butvan.agent.agents.tool.AgentToolModule;
import butvan.agent.agents.tool.result.ToolError;
import butvan.agent.agents.tool.result.ToolResult;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 学习方法卡的只读工具 Adapter，不创建目标、待办或个人记忆。 */
@Component
@RequiredArgsConstructor
public class MethodCardTool implements AgentToolModule {

    private static final String NOTE = "以下是研究概括；适用条件与小尝试是应用建议，非书籍原话。"
            + "结合当前情境选择，建议须经用户采纳，不自动创建目标或待办；无匹配不代表没有其他方法。";

    private final MethodCardCatalog catalog;

    /**
     * 检索有来源的小方法卡；不回显查询，不读取路径，也不调用外部服务。
     *
     * @param query 学习主题或稳定卡片 ID，最多 200 个字符
     * @return 最多两卡及使用边界；参数错误使用稳定错误码
     */
    @Tool(name = "method_search", readOnly = true, description = "按需查学习方法卡（间隔学习、主动回忆、目标与反馈练习），"
            + "返回最多两卡及一手出处、适用和不适用条件、证据限制；只读，无网络，不是书籍原话")
    public ToolResult<Result> search(
            @ToolParam(name = "query", description = "具体学习方法主题或卡片 ID，1—200 字符") String query) {
        if (query == null || query.isBlank() || query.length() > 200) {
            return new ToolResult<>(false, "请提供 1—200 字符的学习方法主题", null,
                    new ToolError(
                            "INVALID_METHOD_QUERY", "请提供 1—200 字符的学习方法主题"));
        }
        List<MethodCardCatalog.Card> cards = catalog.search(query);
        return ToolResult.success(cards.isEmpty() ? "没有匹配的已核实方法卡" : "已找到学习方法卡",
                new Result(cards, NOTE));
    }

    /** 有界卡片与建议性质说明，列表不可变，不能替代用户事实来源。 */
    public record Result(List<MethodCardCatalog.Card> cards, String note) {
        public Result {
            cards = List.copyOf(cards);
        }
    }
}
