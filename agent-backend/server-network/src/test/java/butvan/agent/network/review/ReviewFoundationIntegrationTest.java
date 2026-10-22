package butvan.agent.network.review;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.finance.model.FinanceModels.FinanceTransaction;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.review.controller.ReviewController;
import butvan.agent.network.review.dto.ReviewDtos.*;
import butvan.agent.network.review.model.ReviewModels.ModelIdentity;
import butvan.agent.network.review.repository.ReviewRepository;
import butvan.agent.network.review.service.*;
import butvan.agent.network.study.model.StudyModels.StudyStatistics;
import butvan.agent.network.study.service.StudyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 经公开 Service／HTTP 验证迁移、授权、隐私最小化、不可变事实与用户目标；不触碰真实模型或用户数据。 */
@SpringBootTest(classes = ReviewFoundationIntegrationTest.Application.class)
@AutoConfigureMockMvc
class ReviewFoundationIntegrationTest {
    private static final Path DATABASE = temporaryDatabase();
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final AtomicLong TIME = new AtomicLong(NOW.toEpochMilli());
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({LocalDatabaseConfiguration.class, ReviewRepository.class, ReviewAuthorizationService.class,
            ReviewFactCollector.class, ReviewFactService.class, ReviewGoalService.class, ReviewDataService.class,
            ReviewController.class, ApiExceptionHandler.class})
    static class Application {
        @Bean Clock automationClock() {
            return new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return Instant.ofEpochMilli(TIME.get()); }
            };
        }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("butvan.database.path", DATABASE::toString);
    }
    @MockitoBean ReviewModelCatalog models;
    @MockitoBean FinanceService finance;
    @MockitoBean ExpenseAnalyticsService expenses;
    @MockitoBean DailyEventService daily;
    @MockitoBean StudyService study;
    @MockitoBean CurrentUserProvider user;
    @jakarta.annotation.Resource ReviewAuthorizationService authorization;
    @jakarta.annotation.Resource ReviewFactService facts;
    @jakarta.annotation.Resource ReviewGoalService goals;
    @jakarta.annotation.Resource ReviewDataService data;
    @jakarta.annotation.Resource MockMvc mvc;

    @BeforeEach void setup() {
        TIME.set(NOW.toEpochMilli());
        when(user.currentUserId()).thenReturn("review-http-user");
        when(models.resolve(anyString(), anyString())).thenAnswer(call -> new ModelIdentity(
                call.getArgument(0), call.getArgument(1), "openai", "connection-v1"));
        when(daily.getTodoOccurrences(anyString(), any())).thenReturn(List.of());
        when(study.getStatistics(anyString(), any(), any(), any())).thenReturn(stat(1200));
    }

    @Test void defaultIsOffAndPublicApiCannotPretendGenerationIsAvailable() throws Exception {
        mvc.perform(get("/agent/reviews/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.authorized").value(false)).andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.generationAvailable").value(false));
        mvc.perform(post("/agent/reviews/grants").contentType("application/json")
                .content("{\"expectedSettingsRevision\":0,\"policyVersion\":\"report-review-v1\",\"confirmed\":false}"))
                .andExpect(jsonPath("$.code").value(400));
        assertThrows(IllegalArgumentException.class, () -> facts.capture("default-off", capture()));
        verifyNoInteractions(finance, expenses, daily, study, models);
    }

    @Test void savingConfigurationDoesNotGrantAndUnknownScopesAreRejected() {
        var saved = configure("config-only", Set.of("STUDY"), 30);
        assertFalse(saved.authorized());
        assertFalse(saved.generationAvailable());
        assertThrows(IllegalArgumentException.class, () -> facts.capture("config-only", capture()));
        assertThrows(IllegalArgumentException.class, () -> authorization.save("invalid-scope", new SaveSettings(0, true,
                Set.of("DAILY_REPORT"), Set.of("CHAT"), 30, 3, "provider-a", "model-a")));
        assertThrows(IllegalArgumentException.class, () -> authorization.save("config-only", settings(0, true, Set.of("STUDY"), 30)));
        assertThrows(IllegalArgumentException.class, () -> authorization.grant("config-only", new GrantRequest(saved.revision(), "old-policy", true)));
        verifyNoInteractions(finance, study);
    }

    @Test void narrowedScopeRemainsAuthorizedButExpansionRequiresNewConsent() {
        String owner = "scope-user";
        var saved = configure(owner, Set.of("STUDY", "TODO"), 30);
        var grant = authorization.grant(owner, new GrantRequest(saved.revision(), ReviewPolicy.VERSION, true));
        assertTrue(authorization.settings(owner).authorized());
        var smaller = authorization.save(owner, settings(saved.revision(), true, Set.of("STUDY"), 7));
        assertTrue(smaller.authorized());
        var expanded = authorization.save(owner, settings(smaller.revision(), true, Set.of("STUDY", "FINANCE"), 60));
        assertFalse(expanded.authorized());
        assertThrows(IllegalArgumentException.class, () -> facts.capture(owner, capture()));
        assertThrows(IllegalArgumentException.class, () -> authorization.revoke("other", grant.id(), new RevokeRequest(1)));
        authorization.revoke(owner, grant.id(), new RevokeRequest(1));
        assertFalse(authorization.settings(owner).authorized());
    }

    @Test void changedModelIdentityInvalidatesConsentAndBrokenModelCanStillBePaused() {
        String owner = "changed-model";
        permit(owner, Set.of("STUDY"));
        when(models.resolve("provider-a", "model-a")).thenReturn(new ModelIdentity("provider-a", "model-a", "anthropic", "connection-v2"));
        assertFalse(authorization.settings(owner).authorized());
        assertThrows(IllegalArgumentException.class, () -> facts.capture(owner, capture()));
        when(models.resolve("provider-a", "model-a")).thenThrow(new IllegalArgumentException("unavailable"));
        var paused = authorization.save(owner, settings(1, false, Set.of("STUDY"), 180));
        assertFalse(paused.enabled());
        assertFalse(paused.authorized());
    }

    @Test void snapshotsAreDeduplicatedImmutableAndOwned() throws Exception {
        String owner = "snapshots-user";
        permit(owner, Set.of("STUDY"));
        var first = facts.capture(owner, capture());
        assertEquals(first.id(), facts.capture(owner, capture()).id());
        when(study.getStatistics(eq(owner), any(), any(), any())).thenReturn(stat(2400));
        var corrected = facts.capture(owner, capture());
        assertNotEquals(first.id(), corrected.id());
        assertEquals("1200", facts.get(owner, first.id()).facts().stream().filter(f -> f.metricKey().equals("duration")).findFirst().orElseThrow().value());
        assertEquals(2, facts.list(owner, 20).size());
        assertTrue(facts.list("other-owner", 20).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> facts.get("other-owner", first.id()));
        assertThrows(IllegalArgumentException.class, () -> facts.list(owner, 51));
        when(user.currentUserId()).thenReturn(owner);
        mvc.perform(get("/agent/reviews/facts")).andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].facts").doesNotExist()).andExpect(jsonPath("$.data[0].sources").doesNotExist());
    }

    @Test void closedDayWindowAndExpirationAreEnforced() {
        String owner = "bounded-user";
        permit(owner, Set.of("STUDY"));
        assertThrows(IllegalArgumentException.class, () -> facts.capture(owner, new CaptureRequest(DAY, DAY.plusDays(1), "UTC")));
        assertThrows(IllegalArgumentException.class, () -> facts.capture(owner, new CaptureRequest(DAY.minusDays(7), DAY, "UTC")));
        assertThrows(IllegalArgumentException.class, () -> facts.capture(owner, new CaptureRequest(DAY.minusDays(181), DAY.minusDays(181), "UTC")));
        assertThrows(IllegalArgumentException.class, () -> facts.capture(owner, new CaptureRequest(DAY, DAY, "invalid-zone")));
        var snapshot = facts.capture(owner, capture());
        TIME.addAndGet(Duration.ofDays(181).toMillis());
        assertTrue(facts.list(owner, 20).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> facts.get(owner, snapshot.id()));
    }

    @Test void revokedDuringCollectionCannotCommitData() {
        String owner = "race-revoke";
        var grant = permit(owner, Set.of("STUDY"));
        when(study.getStatistics(eq(owner), any(), any(), any())).thenAnswer(call -> {
            authorization.revoke(owner, grant.id(), new RevokeRequest(grant.revision()));
            return stat(1200);
        });
        assertThrows(IllegalArgumentException.class, () -> facts.capture(owner, capture()));
        assertTrue(facts.list(owner, 20).isEmpty());
    }

    @Test void financeUsesMinorUnitsAndNeverPersistsNotesOrTransfersAsIncome() throws Exception {
        String owner = "money-user";
        permit(owner, Set.of("FINANCE"));
        when(finance.getReportTransactions(eq(owner), any(), any())).thenReturn(List.of(
                transaction("income", "CNY", "500"), transaction("yield", "USD", "2"), transaction("transfer_in", "CNY", "99999")));
        when(expenses.reportExpenses(eq(owner), any(), any())).thenReturn(List.of(new ExpenseAnalyticsService.ExpenseEntry(
                "expense", DAY, LocalTime.NOON, "餐饮", "PRIVATE_ORDER_NOTE", new BigDecimal("12.34"), "CNY", "finance", NOW)));
        when(finance.getReportAssets(eq(owner), any(), any())).thenReturn(List.of(new FinanceService.ReportAsset("opaque-account", "PRIVATE_ACCOUNT_NAME", "CNY", new BigDecimal("1000"))));
        var snapshot = facts.capture(owner, capture());
        assertEquals("50000", snapshot.facts().stream().filter(f -> f.metricKey().equals("income") && f.currency().equals("CNY")).findFirst().orElseThrow().value());
        assertEquals("200", snapshot.facts().stream().filter(f -> f.metricKey().equals("income") && f.currency().equals("USD")).findFirst().orElseThrow().value());
        assertEquals("48766", snapshot.facts().stream().filter(f -> f.metricKey().equals("net_cashflow") && f.currency().equals("CNY")).findFirst().orElseThrow().value());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot);
        assertFalse(json.contains("PRIVATE_"));
        assertFalse(json.contains("99999"));
    }

    @Test void unavailableSourceIsNotZeroAndAllUnavailableDoesNotCreateSnapshot() {
        String owner = "partial-user";
        permit(owner, Set.of("FINANCE", "TODO", "STUDY"));
        when(finance.getReportTransactions(eq(owner), any(), any())).thenThrow(new IllegalStateException("private-error"));
        var snapshot = facts.capture(owner, capture());
        assertTrue(snapshot.sources().stream().anyMatch(s -> s.domain().equals("FINANCE") && s.state().equals("UNAVAILABLE")));
        assertTrue(snapshot.sources().stream().anyMatch(s -> s.domain().equals("TODO") && s.state().equals("EMPTY")));
        assertTrue(snapshot.facts().stream().noneMatch(f -> f.domain().equals("FINANCE")));
        when(daily.getTodoOccurrences(eq(owner), any())).thenThrow(new IllegalStateException("unavailable"));
        when(study.getStatistics(eq(owner), any(), any(), any())).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> facts.capture(owner, capture()));
        assertEquals(1, facts.list(owner, 20).size());
    }

    @Test void userGoalsRequireOwnershipRevisionAndAtMostThreeActiveGoals() throws Exception {
        String owner = "goals-user";
        var first = goals.save(owner, null, goal(0, "规律学习", "ACTIVE"));
        goals.save(owner, null, goal(0, "运动", "ACTIVE"));
        goals.save(owner, null, goal(0, "休息", "ACTIVE"));
        assertThrows(IllegalArgumentException.class, () -> goals.save(owner, null, goal(0, "第四个", "ACTIVE")));
        assertThrows(IllegalArgumentException.class, () -> goals.save("other", first.id(), goal(1, "覆盖", "ACTIVE")));
        var archived = goals.save(owner, first.id(), goal(first.revision(), "规律学习", "ARCHIVED"));
        assertEquals(2, archived.revision());
        assertThrows(IllegalArgumentException.class, () -> goals.save(owner, first.id(), goal(1, "覆盖", "ACTIVE")));
        goals.save(owner, null, goal(0, "新的重点", "ACTIVE"));
        mvc.perform(post("/agent/reviews/goals").contentType("application/json")
                .content("{\"expectedRevision\":0,\"title\":\"HTTP目标\",\"constraintText\":\"不牺牲休息\",\"successCriterion\":\"每周一次\",\"state\":\"ACTIVE\"}"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.title").value("HTTP目标"))
                .andExpect(jsonPath("$.data.owner").doesNotExist());
    }

    @Test void explicitEraseRevokesConsentAndDeletesOnlyCurrentUserData() {
        String owner = "erase-user", other = "erase-other";
        permit(owner, Set.of("STUDY"));
        permit(other, Set.of("STUDY"));
        var snapshot = facts.capture(owner, capture());
        facts.capture(other, capture());
        goals.save(owner, null, goal(0, "明确目标", "ACTIVE"));
        goals.save(other, null, goal(0, "其他用户目标", "ACTIVE"));
        assertThrows(IllegalArgumentException.class, () -> data.erase(owner, new EraseRequest(1, false)));
        assertThrows(IllegalArgumentException.class, () -> data.erase(owner, new EraseRequest(0, true)));
        var erased = data.erase(owner, new EraseRequest(1, true));
        assertEquals(1, erased.deletedSnapshots());
        assertEquals(1, erased.deletedGoals());
        assertFalse(authorization.settings(owner).enabled());
        assertFalse(authorization.settings(owner).authorized());
        assertTrue(goals.list(owner).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> facts.get(owner, snapshot.id()));
        assertThrows(IllegalArgumentException.class, () -> facts.capture(owner, capture()));
        assertEquals(1, facts.list(other, 20).size());
        assertEquals(1, goals.list(other).size());
        assertTrue(authorization.settings(other).authorized());
    }

    private SaveSettings settings(int revision, boolean enabled, Set<String> domains, int days) {
        return new SaveSettings(revision, enabled, Set.of("DAILY_REPORT", "WEEKLY_REPORT", "MONTHLY_REPORT"), domains, days, 3, "provider-a", "model-a");
    }
    private SettingsView configure(String owner, Set<String> domains, int days) { return authorization.save(owner, settings(0, true, domains, days)); }
    private GrantView permit(String owner, Set<String> domains) {
        var saved = configure(owner, domains, 180);
        return authorization.grant(owner, new GrantRequest(saved.revision(), ReviewPolicy.VERSION, true));
    }
    private CaptureRequest capture() { return new CaptureRequest(DAY, DAY, "Asia/Shanghai"); }
    private StudyStatistics stat(long seconds) { return new StudyStatistics(DAY, DAY, seconds, seconds, 1, 1, List.of()); }
    private SaveGoal goal(int revision, String title, String state) { return new SaveGoal(revision, title, "不牺牲休息", "每周一次", state); }
    private FinanceTransaction transaction(String type, String currency, String amount) {
        return new FinanceTransaction(type, "opaque-account", "PRIVATE_ACCOUNT_NAME", DAY, LocalTime.NOON,
                type, "类别", "PRIVATE_ORDER_NOTE", new BigDecimal(amount), currency, "manual", NOW);
    }
    private static Path temporaryDatabase() {
        try { return Files.createTempDirectory("review-foundation-").resolve("test.db"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
}
