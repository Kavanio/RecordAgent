package butvan.agent.agents.method;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 随应用发布的只读学习方法卡；仅检索已核实资料，不读取用户数据或访问网络。
 *
 * <p>完整卡片只作为显式工具结果返回，不加入常驻 Prompt 或个人上下文自动召回。</p>
 */
@Component
public class MethodCardCatalog {

    /** 单次最多返回两卡，卡片数据与关键词均来自固定的 classpath 资源。 */
    private final List<Entry> entries;

    /** 加载受版本管理的卡片；资源缺失或格式错误时拒绝启动，避免静默伪造资料。 */
    public MethodCardCatalog() {
        try (var input = MethodCardCatalog.class.getResourceAsStream("/methods/cards.json")) {
            if (input == null) throw new IllegalStateException("学习方法卡资源缺失");
            entries = List.copyOf(new ObjectMapper().readValue(input, new TypeReference<List<Entry>>() {}));
        } catch (IOException exception) {
            throw new IllegalStateException("学习方法卡资源读取失败", exception);
        }
    }

    /**
     * 按固定关键词检索，支持精确卡片 ID；无匹配即返回空，不兜底灌入整个卡库。
     *
     * @param query 工具层已校验的非空、有界主题
     * @return 最多两张卡，相关度相同时按稳定 ID 排序
     */
    public List<Card> search(String query) {
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        return entries.stream()
                .map(entry -> new Match(entry.card(), score(entry, normalized)))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingInt(Match::score).reversed()
                        .thenComparing(match -> match.card().id()))
                .limit(2)
                .map(Match::card)
                .toList();
    }

    private int score(Entry entry, String query) {
        if (entry.card().id().equals(query)) return Integer.MAX_VALUE;
        return (int) entry.keywords().stream().filter(query::contains).count();
    }

    /** 不进入工具结果的检索索引；关键词不能由用户扩写。 */
    private record Entry(Card card, List<String> keywords) {}

    private record Match(Card card, int score) {}

    /**
     * 一张方法卡的完整引用契约；概括、应用建议和证据限制共同返回。
     *
     * @param id 版本化卡片身份
     * @param title 中文名称
     * @param summary 有限范围的研究概括，不是原文引语
     * @param whenToUse 应用方建议的适用条件
     * @param whenNotToUse 应用方建议的不适用条件
     * @param tryNext 一个可选的小尝试，不是已同意目标
     * @param limits 证据范围及推广限制
     * @param source 已核实的一手出处和定位
     */
    public record Card(String id, String title, String summary, String whenToUse,
                       String whenNotToUse, String tryNext, String limits, Source source) {}

    /** 一手资料的可复核身份；核验日期不代表个人效果验证日期。 */
    public record Source(String title, String authors, int year, String url, String doi,
                         String locator, String checkedAt) {}
}
