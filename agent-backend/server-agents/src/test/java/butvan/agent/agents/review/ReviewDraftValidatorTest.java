package butvan.agent.agents.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 校验结构化草案的确定性约束；不调用任何模型。 */
class ReviewDraftValidatorTest {
    private final ReviewGenerationRequest request = new ReviewGenerationRequest(
            "WEEKLY_REPORT", "本周", "7 天完整", List.of(
            new ReviewGenerationRequest.Evidence("ev-1", "STUDY", "duration", "3600", "SECONDS", "", "本周", "完整", "now"),
            new ReviewGenerationRequest.Evidence("ev-2", "FINANCE", "expense", "123.45", "MONEY_MINOR", "CNY", "本周", "完整", "now")),
            List.of(new ReviewGenerationRequest.Goal("goal-1", "规律学习", "不牺牲休息", "每周三次")),
            List.of(new ReviewGenerationRequest.HistoryAction("action-1", "study.start", "先做五分钟", "ACCEPTED", "2026-10-20", "")),
            List.of(), List.of(new ReviewGenerationRequest.MethodCard("small-start-v1", "小步启动", "", "", "")),
            5000, 600, 15_000, "provider-a", "model-a");

    @Test void fabricatedNumbersAndUnknownReferencesAreDropped() {
        var draft = new ReviewDraft(1, "本周", List.of(
                new ReviewDraft.Observation("study.start", "FACT_OBSERVATION", "学习时长 99 分钟", List.of("ev-1"), List.of(), ""),
                new ReviewDraft.Observation("study.start", "FACT_OBSERVATION", "学习 3600 秒", List.of("ev-1"), List.of(), ""),
                new ReviewDraft.Observation("money.spend", "HYPOTHESIS", "支出 123.45 元", List.of("ev-x"), List.of(), "")),
                List.of(), List.of());
        var result = ReviewDraftValidator.validate(request, draft);
        assertEquals(1, result.draft().observations().size());
        assertTrue(result.draft().observations().getFirst().text().contains("3600"));
        assertEquals(2, result.issues().size());
    }

    @Test void actionsRequireKnownMethodGoalEvidenceAndBoundedChecks() {
        var draft = new ReviewDraft(1, "本周", List.of(), List.of(), List.of(
                new ReviewDraft.Action("study.start", "goal-1", "small-start-v1", "先做五分钟", "降低启动成本",
                        List.of("ev-1"), 7, "是否完成一次"),
                new ReviewDraft.Action("study.start", "goal-x", "small-start-v1", "无效目标", "", List.of("ev-1"), 7, "标准"),
                new ReviewDraft.Action("study.start", null, "unknown-method", "未知方法", "", List.of("ev-1"), 7, "标准"),
                new ReviewDraft.Action("study.start", null, "small-start-v1", "过长检查", "", List.of("ev-1"), 90, "标准")));
        var result = ReviewDraftValidator.validate(request, draft);
        assertEquals(1, result.draft().actions().size());
        assertEquals(7, result.draft().actions().getFirst().checkAfterDays());
        // 超量项先被截断，其余非法引用逐项丢弃；每条都有可解释原因。
        assertEquals(3, result.issues().size());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("截断")));
    }

    @Test void checkAfterDaysMustStayWithinThirtyDays() {
        var draft = new ReviewDraft(1, "本周", List.of(), List.of(), List.of(
                new ReviewDraft.Action("study.start", null, "small-start-v1", "过长检查", "", List.of("ev-1"), 90, "标准")));
        var result = ReviewDraftValidator.validate(request, draft);
        assertTrue(result.draft().actions().isEmpty());
        assertEquals(1, result.issues().size());
    }

    @Test void diagnosesAndInvestmentPromisesAreRejected() {
        var draft = new ReviewDraft(1, "本周", List.of(
                new ReviewDraft.Observation("mind.set", "HYPOTHESIS", "你可能是焦虑症", List.of("ev-1"), List.of(), "")),
                List.of(), List.of(new ReviewDraft.Action("money.invest", null, "small-start-v1", "这只可以稳赚", "",
                        List.of("ev-2"), 7, "看收益")));
        var result = ReviewDraftValidator.validate(request, draft);
        assertTrue(result.draft().observations().isEmpty());
        assertTrue(result.draft().actions().isEmpty());
    }

    @Test void followUpsStayBoundToExistingActionIds() {
        var draft = new ReviewDraft(1, "本周", List.of(), List.of(
                new ReviewDraft.FollowUp("action-1", "学习时长达到 3600 秒", List.of("ev-1")),
                new ReviewDraft.FollowUp("action-x", "未知行动", List.of("ev-1"))), List.of());
        var result = ReviewDraftValidator.validate(request, draft);
        assertEquals(1, result.draft().followUps().size());
        assertEquals("action-1", result.draft().followUps().getFirst().actionId());
    }
}
