package butvan.agent.network.review.service;

import butvan.agent.agents.review.ReviewGenerationRequest.MethodCard;
import java.util.List;
import java.util.Map;

/** 自写的短方法卡；只按主题选择，不导入书籍全文，也不构成效果保证。 */
public final class ReviewMethodCards {
    private ReviewMethodCards() {}

    private static final Map<String, MethodCard> CARDS = Map.of(
            "small-start-v1", new MethodCard("small-start-v1", "小步启动",
                    "把启动动作缩小到几乎不需要决定的程度，例如先打开材料做五分钟。",
                    "任务长期未启动、启动成本明显高于完成成本时。",
                    "如果限制来自睡眠、疾病或外部安排，加大自我要求不会解决问题。"),
            "reduce-friction-v1", new MethodCard("reduce-friction-v1", "减少环境阻力",
                    "提前移除一次性的准备步骤，让开始所需的选择更少。",
                    "常见阻碍是准备、切换或寻找材料时。",
                    "把阻力全部归因于个人意志，忽略工具与环境的限制。"),
            "goal-alignment-v1", new MethodCard("goal-alignment-v1", "目标与投入对齐",
                    "对照用户明确目标，检查时间与金钱投入是否落在同一方向。",
                    "多个领域投入与自述重点不一致时。",
                    "把忙碌或投入多直接当作进步证据。"),
            "decision-vs-outcome-v1", new MethodCard("decision-vs-outcome-v1", "区分决策与结果",
                    "一次结果好坏不足以判断方法本身，先看当时信息与可重复性。",
                    "出现单次波动、偶然收益或单次失误时。",
                    "用结果反推动机或人格。"),
            "one-variable-v1", new MethodCard("one-variable-v1", "一次只改一个变量",
                    "下一周期只调整一个可观察条件，其余保持不变。",
                    "同时尝试多个改变、无法判断哪项有效时。",
                    "用多变量同时变化来宣称某个方法有效。"),
            "recovery-v1", new MethodCard("recovery-v1", "检查恢复与留白",
                    "把休息与恢复视为可持续投入的一部分，而不是失败。",
                    "投入增加但状态、完成质量或持续性下降时。",
                    "把所有疲惫都解释为需要更强自律。")
    );

    /** 按周期和研究主题选择最多两张卡片；未知主题回退到小步启动。 */
    public static List<MethodCard> select(String period, List<String> topics) {
        List<String> preferred = new java.util.ArrayList<>();
        String joined = String.join(" ", topics).toLowerCase(java.util.Locale.ROOT);
        if (joined.contains("study") || joined.contains("start") || joined.contains("learn")) preferred.add("small-start-v1");
        if (joined.contains("todo") || joined.contains("delay")) preferred.add("reduce-friction-v1");
        if (joined.contains("finance") || joined.contains("money")) preferred.add("goal-alignment-v1");
        if (joined.contains("volatile") || joined.contains("result")) preferred.add("decision-vs-outcome-v1");
        if (preferred.isEmpty()) preferred.add("small-start-v1");
        if (!preferred.contains("one-variable-v1")) preferred.add("one-variable-v1");
        if ("MONTHLY_REPORT".equals(period)) preferred.add("recovery-v1");
        return preferred.stream().distinct().limit(2).map(CARDS::get).toList();
    }
}
