package butvan.agent.agents.review;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 文字材料保持独立 schema，校验仍按本次来源白名单。 */
class LifeReviewNarrativeTest {
    ReviewGenerationRequest request(boolean narrative) {
        return new ReviewGenerationRequest("LIFE_DAILY", "生活复盘", "部分材料", List.of(), List.of(), List.of(),
                List.of(), List.of(), 5000, 1200, 60000, "p", "m", narrative ? List.of(
                new ReviewGenerationRequest.Narrative("journal-1", "JOURNAL", "r1", "2026-10-30", "今天练习英语20分钟", false)) : List.of());
    }
    @Test void narrativeReferencesAreAllowedOnlyWhenExplicitlyPresent() {
        var draft = new ReviewDraft(1, "有限材料", List.of(new ReviewDraft.Observation("life.study", "FACT_OBSERVATION",
                "记录了练习英语20分钟", List.of("journal-1"), List.of(), "只是一次尝试")), List.of(), List.of());
        assertEquals(1, ReviewDraftValidator.validate(request(true), draft).draft().observations().size());
        assertTrue(ReviewDraftValidator.validate(request(false), draft).draft().observations().isEmpty());
    }
    @Test void inventedNumbersAreStillRejectedWithNarrativeInputs() {
        var draft = new ReviewDraft(1, "有限材料", List.of(new ReviewDraft.Observation("life.study", "FACT_OBSERVATION",
                "练习英语99分钟", List.of("journal-1"), List.of(), "")), List.of(), List.of());
        assertTrue(ReviewDraftValidator.validate(request(true), draft).draft().observations().isEmpty());
    }
}
