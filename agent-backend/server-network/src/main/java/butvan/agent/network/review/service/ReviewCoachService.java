package butvan.agent.network.review.service;

import butvan.agent.agents.review.*;
import butvan.agent.network.review.dto.ReviewCoachDtos.*;
import butvan.agent.network.review.dto.ReviewDtos.SnapshotView;
import butvan.agent.network.review.model.ReviewModels.*;
import butvan.agent.network.review.repository.ReviewCoachRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 复盘生成入口：事实采集与模型调用都在写事务外，短事务只负责 CAS 终态与保存。
 *
 * <p>失败、超时、撤销与超预算一律降级；已提交模型的工作不自动重放。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewCoachService {
    private static final Map<String, Limits> LIMITS = Map.of(
            "DAILY_REPORT", new Limits(5_000, 600, 15, 90),
            "WEEKLY_REPORT", new Limits(6_500, 1_200, 25, 120),
            "MONTHLY_REPORT", new Limits(8_000, 1_800, 40, 150));

    private final ReviewCoachRepository repository;
    private final ReviewAuthorizationService authorization;
    private final ReviewFactService facts;
    private final ReviewGoalService goalService;
    private final ReviewGenerator generator;
    private final ReviewMemoryReader memoryReader;
    private final PlatformTransactionManager transactions;
    private final Clock automationClock;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    private record Limits(int inputTokens, int outputTokens, int requestSeconds, int totalSeconds) {}

    /** 生成范围内的证据与覆盖说明；采集合规且仅使用已授权域。 */
    private record EvidenceSet(List<Evidence> values, String coverage) {}

    /** 用户明确确认付费后同步生成一次临时预览；不写长期建议与邮件。 */
    public ReviewView preview(String owner, PreviewRequest input) {
        ReviewPolicy.requireOwner(owner);
        if (input == null || !Boolean.TRUE.equals(input.confirmedPaidCall()))
            throw new IllegalArgumentException("预览会调用模型，请明确确认");
        if (!Set.of("DAILY_REPORT", "WEEKLY_REPORT", "MONTHLY_REPORT").contains(input.period() == null ? "" : input.period()))
            throw new IllegalArgumentException("复盘周期无效");
        var allowed = authorization.requireEnabled(owner);
        if (!allowed.settings().periods().contains(input.period()))
            throw new IllegalArgumentException("当前授权未包含该复盘周期");
        LocalDate from = input.from(), to = input.to();
        ZoneId zone;
        try { zone = ZoneId.of(input.timezone()); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("时区无效"); }
        LocalDate today = automationClock.instant().atZone(zone).toLocalDate();
        if (from == null || to == null || from.isAfter(to) || !to.isBefore(today)
                || from.isBefore(today.minusDays(allowed.settings().historyDays()))
                || ChronoUnit.DAYS.between(from, to) > 31)
            throw new IllegalArgumentException("预览范围无效，最多 32 个已结束自然日");
        Job job = create(owner, input.period(), from, to, zone, allowed, true);
        return runNow(job);
    }

    /** 自动报告入口：去重后排队，立即返回；不阻塞原任务调度线程。 */
    @Transactional
    public Optional<String> schedule(String owner, String period, LocalDate from, LocalDate to, String timezone) {
        ReviewPolicy.requireOwner(owner);
        var allowed = authorization.requireEnabled(owner);
        if (!allowed.settings().periods().contains(period)) return Optional.empty();
        ZoneId zone;
        try { zone = ZoneId.of(timezone); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("时区无效"); }
        if (from == null || to == null || from.isAfter(to)
                || from.isBefore(to.minusDays(allowed.settings().historyDays()))
                || ChronoUnit.DAYS.between(from, to) > 31) throw new IllegalArgumentException("复盘范围超出授权窗口");
        return Optional.of(create(owner, period, from, to, zone, allowed, false).id());
    }

    private Job create(String owner, String period, LocalDate from, LocalDate to, ZoneId zone,
                       ReviewAuthorizationService.Authorization allowed, boolean preview) {
        String goalsFingerprint = ReviewPolicy.fingerprint(goals(owner).stream()
                .map(goal -> goal.id() + ":" + goal.revision()).reduce("", (a, b) -> a + "|" + b));
        var existing = repository.existing(owner, period, from, to, zone.getId(), allowed.grant().id(),
                allowed.settings().revision(), goalsFingerprint, preview);
        if (existing.isPresent()) return existing.get();
        Limits limits = LIMITS.getOrDefault(period, LIMITS.get("WEEKLY_REPORT"));
        Instant now = automationClock.instant();
        var job = new Job(UUID.randomUUID().toString(), owner, period, from, to, zone.getId(), allowed.grant().id(),
                allowed.grant().revision(), allowed.settings().revision(), allowed.settings().fingerprint(),
                goalsFingerprint, "PENDING", preview, now, now.plusSeconds(limits.totalSeconds()), null, null, "", 1);
        repository.insert(job);
        return repository.existing(owner, period, from, to, zone.getId(), allowed.grant().id(),
                allowed.settings().revision(), goalsFingerprint, preview).orElse(job);
    }

    /** 后台领取并执行一个待处理工作项；授权失效直接跳过。 */
    public void tick() {
        for (Job job : repository.pending()) {
            if (job.deadline().isBefore(automationClock.instant())) {
                repository.skip(job.id(), "复盘排队已超时，已使用基础报告");
                continue;
            }
            if (runOne(job) == null) continue;
        }
    }

    /** 同步执行预览；超时或失败返回降级说明而不是抛出 500。 */
    private ReviewView runNow(Job job) {
        Job done = runOne(job);
        return view(ownerOf(job), done == null ? repository.find(ownerOf(job), job.id()).orElse(job) : done);
    }

    private String ownerOf(Job job) { return job.owner(); }

    private Job runOne(Job job) {
        try {
            var allowed = authorization.requireEnabled(job.owner());
            if (!allowed.grant().id().equals(job.grantId()) || allowed.settings().revision() != job.settingsRevision()) {
                repository.skip(job.id(), "授权或配置已变化，未执行模型复盘");
                return null;
            }
            Limits limits = LIMITS.getOrDefault(job.period(), LIMITS.get("WEEKLY_REPORT"));
            if (!job.preview()) {
                String day = automationClock.instant().atZone(ZoneId.of(job.timezone())).toLocalDate().toString();
                if (!repository.reserveBudget(job.owner(), day, allowed.settings().dailyLimit())) {
                    repository.skip(job.id(), "已达到当日自动复盘次数上限，已使用基础报告");
                    return null;
                }
            }
            if (!repository.claimValid(job.id(), automationClock.instant())) return null;
            EvidenceSet evidence = evidence(job);
            if (evidence.values().isEmpty()) {
                finish(job, "SKIPPED", null, "本期没有可用证据，已使用基础报告");
                return null;
            }
            ReviewGenerationRequest request = request(job, allowed, evidence, limits);
            ReviewDraft draft;
            try {
                draft = generator.generate(request);
            } catch (RuntimeException exception) {
                log.warn("复盘模型生成失败：job={} type={}", job.id(), exception.getClass().getSimpleName());
                finish(job, "DEGRADED", null, "本次 AI 复盘未生成，数据报告正常");
                return null;
            }
            var validated = ReviewDraftValidator.validate(request, draft);
            if (validated.draft().observations().isEmpty() && validated.draft().actions().isEmpty()
                    && validated.draft().followUps().isEmpty()) {
                finish(job, "DEGRADED", null, "本次 AI 复盘没有通过证据校验，数据报告正常");
                return null;
            }
            save(job, request, evidence, validated.draft());
            return repository.findById(job.id()).orElse(job);
        } catch (IllegalArgumentException exception) {
            repository.skip(job.id(), "授权或配置已失效，未执行模型复盘");
            return null;
        }
    }

    /** 结果与建议在同一短事务落库；证据引用只允许本次白名单。 */
    private void save(Job job, ReviewGenerationRequest request, EvidenceSet evidence, ReviewDraft draft) {
        Instant now = automationClock.instant();
        String json = json(draft);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            // 外部生成期间可能撤销或清除；提交前在同一事务内复核，禁止迟到结果恢复记忆。
            var current = authorization.requireEnabled(job.owner());
            if (!current.grant().id().equals(job.grantId())
                    || current.grant().revision() != job.grantRevision()
                    || current.settings().revision() != job.settingsRevision()
                    || !current.settings().fingerprint().equals(job.fingerprint())
                    || !now.isBefore(job.deadline())) {
                repository.skip(job.id(), "授权、配置或期限已变化，已丢弃生成结果");
                return;
            }
            if (!repository.finish(job.id(), "SUCCEEDED", json, "", now)) return;
            for (Evidence value : evidence.values()) {
                repository.evidence(job.id(), value);
            }
            // 临时预览保留可查看结果与依据，但不得成为后续召回的长期建议或观察。
            if (job.preview()) return;
            for (ReviewDraft.Action item : draft.actions()) {
                String id = UUID.randomUUID().toString();
                LocalDate check = now.atZone(ZoneId.of(job.timezone())).toLocalDate().plusDays(item.checkAfterDays());
                repository.action(job.id(), now, id, job.owner(), item.topicKey(), item.goalId(), item.text(),
                        item.rationale(), item.successCriterion(), item.methodId(), check, null);
                item.evidenceIds().forEach(evidenceId -> repository.linkEvidence(job.id(), id, evidenceId));
            }
            for (ReviewDraft.Observation item : draft.observations()) {
                String id = UUID.randomUUID().toString();
                repository.observation(job.id(), id, job.owner(), item.topicKey(), item.text(), item.uncertainty(), now);
                item.evidenceIds().forEach(evidenceId -> repository.linkEvidence(job.id(), id, evidenceId));
            }
        });
    }

    private void finish(Job job, String state, String json, String message) {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                repository.finish(job.id(), state, json, message, automationClock.instant()));
    }

    private ReviewGenerationRequest request(Job job, ReviewAuthorizationService.Authorization allowed,
                                            EvidenceSet evidence, Limits limits) {
        var memory = memoryReader.readForAuthorizedReview(job.owner());
        var methods = ReviewMethodCards.select(job.period(), evidence.values().stream().map(Evidence::domain).toList());
        return new ReviewGenerationRequest(job.period(), job.period() + " " + job.from() + "~" + job.to(),
                evidence.coverage(), evidence.values().stream().map(value -> new ReviewGenerationRequest.Evidence(
                value.id(), value.domain(), value.metricKey(), display(value), value.unit(), value.currency(),
                job.from() + "~" + job.to(), evidence.coverage(), job.createdAt().toString())).toList(),
                memory.goals(), memory.actions(), memory.observations(), methods, limits.inputTokens(), limits.outputTokens(),
                limits.requestSeconds() * 1_000L, allowed.settings().providerId(), allowed.settings().modelName());
    }

    /** 金额以元展示，其他单位保持原值；只由程序格式化，模型不参与计算。 */
    private String display(Evidence value) {
        return "MONEY_MINOR".equals(value.unit())
                ? BigDecimal.valueOf(value.value(), 2).toPlainString() : Long.toString(value.value());
    }

    /** 采集授权窗口内最多 31 天，按 7 日分片；单源失败只标记覆盖不足。 */
    private EvidenceSet evidence(Job job) {
        List<Evidence> values = new ArrayList<>();
        Set<String> unavailable = new TreeSet<>();
        LocalDate cursor = job.from();
        int chunk = 0;
        while (!cursor.isAfter(job.to())) {
            LocalDate end = cursor.plusDays(6);
            if (end.isAfter(job.to())) end = job.to();
            SnapshotView snapshot;
            try {
                snapshot = facts.capture(job.owner(), new butvan.agent.network.review.dto.ReviewDtos.CaptureRequest(
                        cursor, end, job.timezone()));
            } catch (RuntimeException exception) {
                unavailable.add(cursor + "~" + end);
                cursor = end.plusDays(1);
                continue;
            }
            for (var fact : snapshot.facts()) {
                values.add(new Evidence("ev-" + (++chunk) + "-" + fact.domain() + "-" + fact.metricKey()
                        + "-" + fact.subjectId() + "-" + fact.currency(), snapshot.id(), fact.domain(),
                        fact.metricKey(), fact.subjectId(), fact.currency(), fact.unit(),
                        Long.parseLong(fact.value())));
            }
            cursor = end.plusDays(1);
        }
        String coverage = "统计范围 " + job.from() + " 至 " + job.to() + "（" + job.timezone() + "）；"
                + (unavailable.isEmpty() ? "全部授权来源可用。" : "部分日期来源不可用：" + String.join("、", unavailable) + "。")
                + " 金额按币种分列，资产余额为当前账本回溯，不代表不可变历史。";
        return new EvidenceSet(values, coverage);
    }

    private List<butvan.agent.network.review.dto.ReviewDtos.GoalView> goals(String owner) {
        return goalService.list(owner);
    }

    /** 公开历史摘要；不触发生成。 */
    @Transactional(readOnly = true)
    public List<ReviewSummary> history(String owner, int limit) {
        ReviewPolicy.requireOwner(owner);
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("复盘历史数量必须为 1–50");
        return repository.history(owner, limit).stream().map(job -> new ReviewSummary(job.id(), job.period(), job.from(),
                job.to(), job.state(), job.preview(), job.message(), job.createdAt(), job.finishedAt())).toList();
    }

    /** 读取详情；结果缺失时返回降级说明而不是重新调用模型。 */
    @Transactional(readOnly = true)
    public ReviewView get(String owner, String id) {
        ReviewPolicy.requireOwner(owner);
        Job job = repository.find(owner, id).orElseThrow(() -> new IllegalArgumentException("复盘不存在"));
        return view(owner, job);
    }

    private ReviewView view(String owner, Job job) {
        ReviewDraft draft = null;
        if (job.resultJson() != null && !job.resultJson().isBlank()) {
            try { draft = mapper.readValue(job.resultJson(), ReviewDraft.class); }
            catch (Exception exception) { log.warn("复盘结果读取失败：job={}", job.id()); }
        }
        List<Item> observations = List.of(), actions = List.of(), followUps = List.of();
        if (draft != null) {
            observations = draft.observations().stream().map(item -> new Item(null, item.topicKey(), item.kind(),
                    item.text(), "", "", "", "SUCCEEDED", item.uncertainty(), item.evidenceIds())).toList();
            actions = draft.actions().stream().map(item -> new Item(null, item.topicKey(), "ACTION", item.text(),
                    item.rationale(), item.successCriterion(), item.methodId(), "PROPOSED", "", item.evidenceIds())).toList();
            followUps = draft.followUps().stream().map(item -> new Item(item.actionId(), "", "FOLLOW_UP", item.text(),
                    "", "", "", "SUCCEEDED", "", item.evidenceIds())).toList();
        }
        return new ReviewView(job.id(), job.period(), job.from(), job.to(), job.timezone(), job.state(), job.preview(),
                job.message(), job.createdAt(), job.finishedAt(), draft == null ? "" : draft.coverageNote(),
                observations, actions, followUps, List.of());
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { return "{}"; }
    }
}
