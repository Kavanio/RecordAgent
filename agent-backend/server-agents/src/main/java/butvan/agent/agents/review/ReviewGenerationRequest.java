package butvan.agent.agents.review;

import java.util.List;

/**
 * 长期复盘生成的中立输入契约。
 *
 * <p>只包含已经由程序计算并授权的最小化事实、用户目标、历史行动和方法卡；
 * 不包含聊天记忆、资料正文、账户名称或订单备注。实现不得访问业务库。</p>
 */
public record ReviewGenerationRequest(
        String period,
        String periodLabel,
        String coverage,
        List<Evidence> evidence,
        List<Goal> goals,
        List<HistoryAction> historyActions,
        List<Observation> observations,
        List<MethodCard> methods,
        int inputTokenBudget,
        int outputTokenBudget,
        long timeoutMillis,
        String providerId,
        String modelName) {

    public ReviewGenerationRequest {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        goals = goals == null ? List.of() : List.copyOf(goals);
        historyActions = historyActions == null ? List.of() : List.copyOf(historyActions);
        observations = observations == null ? List.of() : List.copyOf(observations);
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    /** 程序计算的事实证据；金额使用最小单位，单位与币种必须随行。 */
    public record Evidence(String id, String domain, String metricKey, String value, String unit,
                           String currency, String period, String coverage, String sourceAsOf) {}

    /** 用户显式维护的目标，模型只能引用不能改写。 */
    public record Goal(String id, String title, String constraintText, String successCriterion) {}

    /** 已有建议的当前状态；未反馈不等于失败。 */
    public record HistoryAction(String id, String topicKey, String title, String state, String checkOn,
                                String feedback) {}

    /** 尚未被用户否定的候选观察，只能作为弱证据引用。 */
    public record Observation(String id, String topicKey, String content, String uncertainty) {}

    /** 静态方法卡，按主题最多选择两张。 */
    public record MethodCard(String id, String title, String summary, String whenToUse, String counterExample) {}
}
