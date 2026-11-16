package butvan.agent.network.review.life;

import butvan.agent.agents.context.PersonalContextService;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.SessionSummaryDto;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.network.record.service.RecordService;
import butvan.agent.network.review.life.LifeReviewDtos.HabitSuggestionView;
import butvan.agent.network.review.model.ReviewModels.Fact;
import butvan.agent.network.review.service.ReviewFactCollector;
import butvan.agent.network.review.service.ReviewGoalService;
import butvan.agent.network.review.service.ReviewPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 多源认知材料采集器：只读汇总画像、全渠道普通对话、每日手记、人工目标与日历待办/学习专注/记账事实。
 */
@Slf4j
@Component
public class LifeCompanionCollector {
    private static final Set<String> FACT_DOMAINS = Set.of("FINANCE", "TODO", "STUDY");

    private final SessionCatalogService sessions;
    private final TranscriptService transcripts;
    private final PersonalContextService personalContext;
    private final RecordService records;
    private final ReviewGoalService goals;
    private final LifeActivityService activity;
    private final ReviewFactCollector factCollector;

    public LifeCompanionCollector(
            SessionCatalogService sessions,
            TranscriptService transcripts,
            @Autowired(required = false) PersonalContextService personalContext,
            @Autowired(required = false) RecordService records,
            @Autowired(required = false) ReviewGoalService goals,
            @Autowired(required = false) LifeActivityService activity,
            @Autowired(required = false) ReviewFactCollector factCollector
    ) {
        this.sessions = sessions;
        this.transcripts = transcripts;
        this.personalContext = personalContext;
        this.records = records;
        this.goals = goals;
        this.activity = activity;
        this.factCollector = factCollector;
    }

    public record CollectedMaterial(
            String profileText,
            List<String> chats,
            List<String> journals,
            List<String> activeGoals,
            List<String> todos,
            List<String> studyLogs,
            List<String> financeSummary,
            HabitSuggestionView habitSuggestion,
            String fingerprint
    ) {}

    public CollectedMaterial collect(String owner, ZoneId zone, Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate from = today.minusDays(6);

        String profileText = personalContext != null ? personalContext.get(owner).content() : "";
        List<String> chats = collectRecentUserMessages(8);
        List<String> journals = collectRecentJournals(owner, from, today);
        List<String> activeGoalTexts = collectActiveGoals(owner);

        List<String> todos = new ArrayList<>();
        List<String> studyLogs = new ArrayList<>();
        List<String> financeSummary = new ArrayList<>();
        collectStructuredFacts(owner, from, today, zone, todos, studyLogs, financeSummary);

        HabitSuggestionView habitSuggestion = activity != null
                ? activity.suggestHabit(owner, zone.getId(), now)
                : null;

        String rawMaterial = String.join("\n",
                "PROFILE:" + profileText,
                "CHATS:" + String.join(" | ", chats),
                "JOURNALS:" + String.join(" | ", journals),
                "GOALS:" + String.join(" | ", activeGoalTexts),
                "TODOS:" + String.join(" | ", todos),
                "STUDY:" + String.join(" | ", studyLogs),
                "FINANCE:" + String.join(" | ", financeSummary));

        return new CollectedMaterial(
                profileText,
                List.copyOf(chats),
                List.copyOf(journals),
                List.copyOf(activeGoalTexts),
                List.copyOf(todos),
                List.copyOf(studyLogs),
                List.copyOf(financeSummary),
                habitSuggestion,
                ReviewPolicy.fingerprint(rawMaterial)
        );
    }

    private List<String> collectRecentUserMessages(int limit) {
        if (sessions == null || transcripts == null) return List.of();
        try {
            List<String> items = new ArrayList<>();
            for (var session : sessions.listActive().stream()
                    .filter(s -> s.kind() == SessionKind.GENERAL)
                    .sorted(Comparator.comparing(SessionSummaryDto::updatedAt).reversed())
                    .limit(4)
                    .toList()) {
                for (var msg : transcripts.recent(session.id(), 16)) {
                    if (msg.role() == TranscriptMessageDto.MessageRole.USER
                            && msg.content() != null && !msg.content().isBlank()) {
                        String text = msg.content().strip();
                        items.add(text.substring(0, Math.min(text.length(), 160)));
                        if (items.size() >= limit) return items;
                    }
                }
            }
            return items;
        } catch (RuntimeException e) {
            log.debug("陪伴读取近期对话降级：{}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<String> collectRecentJournals(String owner, LocalDate from, LocalDate to) {
        if (records == null) return List.of();
        try {
            return records.journalFragments(owner, from, to).stream()
                    .limit(5)
                    .map(f -> f.recordDate() + ": " + f.text().substring(0, Math.min(f.text().length(), 160)))
                    .toList();
        } catch (RuntimeException e) {
            log.debug("陪伴读取手记降级：{}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<String> collectActiveGoals(String owner) {
        if (goals == null) return List.of();
        try {
            return goals.list(owner).stream()
                    .filter(g -> "ACTIVE".equals(g.state()))
                    .limit(4)
                    .map(g -> g.title() + (g.constraintText().isBlank() ? "" : "（" + g.constraintText() + "）"))
                    .toList();
        } catch (RuntimeException e) {
            log.debug("陪伴读取活跃目标降级：{}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private void collectStructuredFacts(
            String owner,
            LocalDate from,
            LocalDate to,
            ZoneId zone,
            List<String> todos,
            List<String> studyLogs,
            List<String> financeSummary
    ) {
        if (factCollector == null) return;
        try {
            var collected = factCollector.collect(owner, from, to, zone, FACT_DOMAINS);
            for (Fact fact : collected.facts()) {
                String unit = fact.unit() == null || fact.unit().isBlank() ? "" : " " + fact.unit();
                String currency = fact.currency() == null || fact.currency().isBlank() ? "" : " " + fact.currency();
                String line = fact.metricKey() + "=" + fact.value() + unit + currency;
                switch (fact.domain()) {
                    case "TODO" -> {
                        if (todos.size() < 6) todos.add(line);
                    }
                    case "STUDY" -> {
                        if (studyLogs.size() < 6) studyLogs.add(line);
                    }
                    case "FINANCE" -> {
                        if (financeSummary.size() < 6) financeSummary.add(line);
                    }
                    default -> {
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("陪伴读取结构化生活事实降级：{}", e.getClass().getSimpleName());
        }
    }
}
