package butvan.agent.network.review.life;

import butvan.agent.agents.usage.ApproximateTokenCounter;
import butvan.agent.network.review.service.ReviewGoalService;
import butvan.agent.network.review.dto.ReviewDtos.GoalView;
import butvan.agent.network.review.life.LifeReviewModels.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 显式目标和反馈预算不突破授权，用户纠正后旧理解失效。 */
class LifePersonalMaterialServiceTest {
    final ReviewGoalService goals = mock(ReviewGoalService.class);
    final LifeReviewRepository repository = mock(LifeReviewRepository.class);
    final LifePersonalMaterialService service = new LifePersonalMaterialService(goals, repository, new ApproximateTokenCounter());
    final Instant now = Instant.parse("2026-10-30T13:00:00Z");
    Settings settings(boolean enabled) { return new Settings("owner", 1, true, "grant", enabled ? Set.of("GOALS") : Set.of("JOURNAL"),
            "p", "m", "f", "Asia/Shanghai", 7, 1, false, false, "", now); }
    GoalView goal(String id, String title) { return new GoalView(id, title, "不牺牲休息", "完成一次尝试", "ACTIVE", 1, now, now); }
    @Test void separateSourceSelectionIsRequired() {
        assertTrue(service.collect(settings(false), now).isEmpty()); verifyNoInteractions(goals, repository);
    }
    @Test void goalBudgetKeepsOriginalConstraintsAndSkipsOversizedGoal() {
        when(goals.list("owner")).thenReturn(List.of(goal("short", "练习英语"), goal("long", "文".repeat(1000))));
        when(repository.feedbackMaterials(anyString(), any(), any())).thenReturn(List.of());
        var result = service.collect(settings(true), now);
        assertEquals(1, result.size()); assertTrue(result.getFirst().text().contains("不牺牲休息"));
        assertEquals("USER_GOAL", result.getFirst().kind());
        when(goals.list("owner")).thenReturn(List.of(goal("short", "新的目标")));
        assertThrows(IllegalArgumentException.class, () -> service.verify(settings(true), result));
    }
    @Test void explicitFeedbackUsesActualFeedbackTimeAndBudget() {
        when(goals.list("owner")).thenReturn(List.of());
        when(repository.feedbackMaterials("owner", now.minus(Duration.ofDays(7)), now)).thenReturn(List.of(
                new Material("feedback-f", "USER_FEEDBACK", "f", now.toString(), "用户明确反馈：NOT_RELEVANT", false)));
        var result = service.collect(settings(true), now);
        assertEquals(now.toString(), result.getFirst().occurredAt());
        assertEquals("USER_FEEDBACK", result.getFirst().kind());
    }
}
