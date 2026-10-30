package butvan.agent.agents.review;

import java.util.*;

/**
 * 结构化草案的确定性校验：白名单引用、长度、数量与已知禁用结论。
 *
 * <p>校验通过只证明格式与已知约束成立，不保证推理正确，也不替代用户对行动的采纳。</p>
 */
public final class ReviewDraftValidator {
    private static final int MAX_ITEMS = 3;
    private static final Set<String> KINDS = Set.of("FACT_OBSERVATION", "HYPOTHESIS");
    private static final List<String> BANNED = List.of(
            "懒惰", "抑郁", "焦虑症", "人格障碍", "必然成功", "保证收益", "稳赚", "买这只", "加杠杆");

    private ReviewDraftValidator() {}

    /** 返回通过校验的项和可解释的丢弃原因；不修改输入。 */
    public static Result validate(ReviewGenerationRequest request, ReviewDraft draft) {
        List<String> issues = new ArrayList<>();
        if (request == null || draft == null) return new Result(empty(), List.of("缺少生成输入"));
        if (draft.schemaVersion() != 1) issues.add("草案版本不受支持");
        Set<String> evidence = new HashSet<>();
        request.evidence().forEach(item -> evidence.add(item.id()));
        request.narratives().forEach(item -> evidence.add(item.id()));
        Set<String> goals = new HashSet<>();
        request.goals().forEach(item -> goals.add(item.id()));
        Set<String> methods = new HashSet<>();
        request.methods().forEach(item -> methods.add(item.id()));
        Set<String> actions = new HashSet<>();
        request.historyActions().forEach(item -> actions.add(item.id()));
        Map<String, String> evidenceValues = new HashMap<>();
        request.evidence().forEach(item -> evidenceValues.put(item.id(), item.value()));
        request.narratives().forEach(item -> evidenceValues.put(item.id(), item.text()));

        List<ReviewDraft.Observation> observations = new ArrayList<>();
        for (ReviewDraft.Observation item : limit(draft.observations(), "观察", issues)) {
            if (!text(item.text(), 240) || !KINDS.contains(item.kind() == null ? "" : item.kind())
                    || !topicKey(item.topicKey()) || !banned(item.text())) { issues.add("丢弃一条观察：格式或内容不合规"); continue; }
            if (!evidence.containsAll(item.evidenceIds()) || item.evidenceIds().isEmpty()
                    || !evidence.containsAll(item.counterEvidenceIds())) { issues.add("丢弃一条观察：引用了未知证据"); continue; }
            if (!numbersSupported(item.text(), item.evidenceIds(), evidenceValues)) { issues.add("丢弃一条观察：包含无依据数字"); continue; }
            observations.add(new ReviewDraft.Observation(item.topicKey(), item.kind(), item.text().strip(),
                    List.copyOf(item.evidenceIds()), List.copyOf(item.counterEvidenceIds()), clean(item.uncertainty(), 200)));
        }

        List<ReviewDraft.FollowUp> followUps = new ArrayList<>();
        for (ReviewDraft.FollowUp item : limit(draft.followUps(), "跟进", issues)) {
            if (!actions.contains(item.actionId()) || !text(item.text(), 240) || !banned(item.text())
                    || !evidence.containsAll(item.evidenceIds()) || item.evidenceIds().isEmpty()
                    || !numbersSupported(item.text(), item.evidenceIds(), evidenceValues)) {
                issues.add("丢弃一条跟进：引用或内容不合规");
                continue;
            }
            followUps.add(new ReviewDraft.FollowUp(item.actionId(), item.text().strip(), List.copyOf(item.evidenceIds())));
        }

        List<ReviewDraft.Action> validActions = new ArrayList<>();
        for (ReviewDraft.Action item : limit(draft.actions(), "建议", issues)) {
            if (!topicKey(item.topicKey()) || !methods.contains(item.methodId())
                    || (item.goalId() != null && !item.goalId().isBlank() && !goals.contains(item.goalId()))
                    || !text(item.text(), 200) || !text(item.successCriterion(), 200)
                    || !clean(item.rationale(), 300).equals(item.rationale() == null ? "" : item.rationale().strip())
                    || !banned(item.text()) || !banned(item.rationale())
                    || !evidence.containsAll(item.evidenceIds()) || item.evidenceIds().isEmpty()
                    || !numbersSupported(item.text(), item.evidenceIds(), evidenceValues)) {
                issues.add("丢弃一条建议：引用、方法或内容不合规");
                continue;
            }
            int days = item.checkAfterDays() == null ? 7 : item.checkAfterDays();
            if (days < 1 || days > 30) { issues.add("丢弃一条建议：检查日期超出范围"); continue; }
            validActions.add(new ReviewDraft.Action(item.topicKey(), blank(item.goalId()), item.methodId(),
                    item.text().strip(), clean(item.rationale(), 300), List.copyOf(item.evidenceIds()), days,
                    item.successCriterion().strip()));
        }
        String coverage = clean(draft.coverageNote(), 300);
        if (coverage.isBlank()) { coverage = request.coverage(); issues.add("草案未说明数据范围"); }
        return new Result(new ReviewDraft(1, coverage, observations, followUps, validActions), List.copyOf(issues));
    }

    private static <T> List<T> limit(List<T> values, String label, List<String> issues) {
        if (values.size() > MAX_ITEMS) issues.add("截断超量的" + label);
        return values.subList(0, Math.min(values.size(), MAX_ITEMS));
    }

    private static boolean numbersSupported(String text, List<String> ids, Map<String, String> values) {
        if (text == null) return false;
        for (String token : text.split("[^0-9]+")) {
            if (token.isBlank()) continue;
            boolean found = ids.stream().map(values::get).filter(Objects::nonNull)
                    .anyMatch(value -> value.contains(token));
            if (!found) return false;
        }
        return true;
    }

    private static boolean banned(String value) {
        String text = value == null ? "" : value;
        return BANNED.stream().noneMatch(text::contains);
    }

    private static boolean text(String value, int max) {
        String clean = clean(value, max);
        return !clean.isBlank() && clean.equals(value == null ? "" : value.strip());
    }

    private static String clean(String value, int max) {
        String result = value == null ? "" : value.strip();
        if (result.length() > max || result.indexOf('\0') >= 0) return "";
        return result;
    }

    private static boolean topicKey(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9._-]{2,79}");
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }

    private static ReviewDraft empty() { return new ReviewDraft(1, "", List.of(), List.of(), List.of()); }

    /** 校验结果；issues 仅用于日志与降级说明，不包含模型原文。 */
    public record Result(ReviewDraft draft, List<String> issues) {}
}
