package butvan.agent.network.review.life;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.review.*;
import butvan.agent.agents.usage.TokenCounter;
import butvan.agent.network.review.life.LifeReviewModels.*;
import butvan.agent.network.review.life.LifeReviewDtos.*;
import butvan.agent.network.review.service.ReviewMethodCards;
import butvan.agent.network.review.service.ReviewPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** 生活复盘版本入口；读取已有内容不调用模型，只有明确补充才创建下一版本。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifeReviewService {
    private final LifeReviewRepository repository;
    private final LifeAuthorizationService authorization;
    private final LifeMaterialService materials;
    private final LifePersonalMaterialService personal;
    private final ReviewGenerator generator;
    private final ResetBarrier barrier;
    private final PlatformTransactionManager transactions;
    private final Clock automationClock;
    private final TokenCounter counter;
    private final ObjectMapper mapper;

    /** 短事务建立工作项，生成在 worker 中进行；同版本重放复用结果。 */
    public ContentView prepare(String owner, Prepare input) {
        try (var lease = barrier.enter()) {
            return new TransactionTemplate(transactions).execute(tx -> {
                var s = authorization.require(owner);
                LocalDate today = automationClock.instant().atZone(ZoneId.of(s.timezone())).toLocalDate();
                if (input == null || input.day() == null || input.day().isAfter(today)
                        || input.day().isBefore(today.minusDays(s.historyDays() - 1L)))
                    throw new IllegalArgumentException("复盘日期超出已授权窗口");
                var old = repository.latest(owner, input.day());
                boolean currentGrant = old.isPresent() && old.get().grantId().equals(s.grantId());
                boolean reusable = currentGrant && Set.of("READY", "PENDING", "RUNNING").contains(old.get().state());
                if ((reusable && !input.supplement())
                        || (currentGrant && input.supplement() && old.get().version() == input.expectedVersion() + 1))
                    return view(old.get());
                int expected = (!currentGrant || !reusable) && !input.supplement()
                        ? old.map(Content::version).orElse(0) : input.expectedVersion();
                if (expected != old.map(Content::version).orElse(0))
                    throw new IllegalArgumentException("复盘版本已变化，请刷新");
                if (currentGrant && Set.of("PENDING", "RUNNING").contains(old.get().state()))
                    throw new IllegalArgumentException("当前版本尚在生成，请稍后补充");
                Instant now = automationClock.instant();
                var c = new Content(UUID.randomUUID().toString(), owner, input.day(), s.timezone(), expected + 1,
                        s.grantId(), s.revision(), "PENDING", now, availableAt(now, s.timezone()), now,
                        lease.generation(), List.of(), List.of(), "等待按授权范围采集", null, List.of(), "");
                repository.insert(c);
                return view(c);
            });
        }
    }
    /** 23:30 只用于本域顺延；凌晨仍属于前一天复盘的延后窗口。 */
    public static Instant availableAt(Instant instant, String timezone) {
        var local = instant.atZone(ZoneId.of(timezone));
        if (!local.toLocalTime().isBefore(LocalTime.of(23, 30)))
            return local.toLocalDate().plusDays(1).atTime(12, 0).atZone(local.getZone()).toInstant();
        if (local.getHour() < 12) return local.toLocalDate().atTime(12, 0).atZone(local.getZone()).toInstant();
        return instant;
    }
    /** 读取独立冻结存档，不将其作为新的上下文来源。 */
    public List<String> archives(String owner) { return repository.archives(owner); }
    /** 只读返回已保存版本，不重新采集或调用模型。 */
    public List<ContentView> history(String owner) { return repository.history(owner).stream().map(this::view).toList(); }
    /** 读取属于当前用户的单个不可变内容版本。 */
    public ContentView get(String owner, String id) { return view(requireContent(owner, id)); }
    /** 校验版本所有权，跨用户和不存在采用相同错误语义。 */
    public Content requireContent(String owner, String id) {
        return repository.content(owner, id).orElseThrow(() -> new IllegalArgumentException("复盘不存在或无权访问"));
    }
    /** 后台只处理有限工作，不在调度入口等待大模型。 */
    public void tick() {
        for (var c : repository.pending()) {
            try { generate(c); }
            catch (RuntimeException e) { log.warn("生活复盘处理失败：id={} type={}", c.id(), e.getClass().getSimpleName()); }
        }
    }
    private void generate(Content original) {
        try (var slot = butvan.agent.network.review.service.ReviewGenerationGate.tryEnter(original.owner())) {
            if (slot != null) generateClaimed(original);
        }
    }
    private void generateClaimed(Content original) {
        try (var lease = barrier.enter()) {
            Content c = original;
            try {
                var s = valid(c);
                if (!automationClock.instant().isBefore(c.createdAt().plusSeconds(180))) {
                    repository.finish(c.finish("SKIPPED", null, "排队已超时，未调用模型"), "PENDING"); return;
                }
                boolean claimed = Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(tx -> {
                    valid(original);
                    if (!repository.finish(original.finish("RUNNING", null, ""), "PENDING")) return false;
                    return true;
                }));
                if (!claimed) return;
                var sources = materials.collect(s, c.day(), c.sourceAsOf());
                var kept = new ArrayList<>(repository.replies(c.owner(), c.day(), c.grantId()));
                kept.addAll(personal.collect(s, c.sourceAsOf()));
                kept.addAll(sources.materials());
                String coverage = sources.coverage() + (s.sources().contains("GOALS") ? "；目标/反馈为用户明确表达，预算分别300/500估算Token；未反馈保持未知" : "");
                PriorContext prior = priorContext(c, s);
                ReviewGenerationRequest request = request(c, s, kept, sources.facts(), coverage, prior);
                while (!kept.isEmpty() && counter.count(json(request)) > 4000) {
                    kept.removeLast();
                    coverage = sources.coverage() + "；输入预算截断，未覆盖全部材料";
                    request = request(c, s, kept, sources.facts(), coverage, prior);
                }
                if (counter.count(json(request)) > 4000) {
                    request = request(c, s, kept, sources.facts(), coverage, new PriorContext(List.of(), List.of()));
                }
                if (counter.count(json(request)) > 4000) throw new IllegalArgumentException("输入超过生活复盘预算");
                var questions = kept.size() < 2 ? List.of("今天还有哪件事或感受值得留下？可以稍后补充，也可以跳过。") : List.<String>of();
                c = new Content(c.id(), c.owner(), c.day(), c.timezone(), c.version(), c.grantId(), c.settingsRevision(), "RUNNING",
                        c.sourceAsOf(), c.availableAt(), c.createdAt(), c.generation(), kept, sources.facts(), coverage,
                        null, questions, "");
                valid(c);
                ReviewDraft draft = null;
                String state = "READY", message = "";
                if (kept.isEmpty() && sources.facts().isEmpty()) {
                    state = "DEGRADED"; message = "现有记录不足，无法回顾未记录的时间；补问可以跳过。";
                } else if (!Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(tx -> {
                    valid(original);
                    return repository.reserve(s.owner(), automationClock.instant().atZone(ZoneId.of(s.timezone())).toLocalDate(), s.dailyLimit());
                }))) {
                    state = "DEGRADED"; message = "已达到当日模型调用额度，保留现有材料，不自动重试。";
                } else {
                    // 持有真实生产者租约直到返回；超时不是释放后台写入保护的依据。
                    barrier.markRuntimeUsed();
                    var result = ReviewDraftValidator.validate(request, generator.generate(request));
                    draft = result.draft();
                    if (draft.observations().isEmpty() && draft.followUps().isEmpty() && draft.actions().isEmpty()) {
                        state = "DEGRADED"; message = "生成内容未通过证据校验，以下保留已读取材料。";
                    }
                }
                materials.verify(s, c.materials());
                personal.verify(s, c.materials());
                publish(c.finish(state, draft, message));
            } catch (RuntimeException e) {
                log.warn("生活复盘生成未完成：id={} type={}", c.id(), e.getClass().getSimpleName());
                // 固定脱敏原因；撤权和重置期间不发布任何已读取资料。
                try {
                    var current = valid(c);
                    materials.verify(current, c.materials());
                    personal.verify(current, c.materials());
                    String reason = e instanceof ReviewGenerator.ReviewGenerationException failure ? switch (failure.kind()) {
                        case TIMEOUT -> "模型请求超时";
                        case INVALID -> "模型输入或返回格式不符合复盘要求";
                        case UNAVAILABLE -> "模型连接或服务不可用";
                        case CANCELLED -> "模型生成已取消";
                    } : "生成过程未完成";
                    publish(c.finish("DEGRADED", null, reason + "；未自动重试，参考资料仅在桌面保留。"));
                }
                catch (RuntimeException invalid) {
                    repository.finish(original.finish("SKIPPED", null, "授权、期限或代次已失效，结果已丢弃"), "RUNNING");
                    repository.finish(original.finish("SKIPPED", null, "授权、期限或代次已失效，结果已丢弃"), "PENDING");
                }
            }
        }
    }
    private void publish(Content c) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            valid(c);
            if (!automationClock.instant().isBefore(c.createdAt().plusSeconds(180)))
                throw new IllegalArgumentException("生成已超过期限");
            boolean worthPresenting = repository.settings(c.owner()).proactive() && LifeOutreachPolicy.hasFreshInformation(c);
            boolean full = worthPresenting && repository.pendingCount(c.owner()) >= 100
                    && !repository.candidateExists(c.owner(), "review-" + c.day(), "daily");
            Content output = full ? c.finish(c.state(), c.draft(), c.message() + "；主动候选积压已达上限，此版本仅桌面留档。") : c;
            if (repository.finish(output, "RUNNING")) {
                repository.delivery(new Delivery(UUID.randomUUID().toString(), c.owner(), c.id(), "DESKTOP", "AVAILABLE", "", "", 0, automationClock.instant()));
                Instant now = automationClock.instant();
                if (worthPresenting && !full) {
                    repository.candidate(new Candidate(UUID.randomUUID().toString(), c.owner(), "review-" + c.day(),
                            "daily", c.id(), c.id(), "PENDING", c.availableAt(), now.plus(Duration.ofHours(36)), now, null, ""));
                } else if (LifeOutreachPolicy.isDeliverable(c) && repository.candidateExists(c.owner(), "review-" + c.day(), "daily")) {
                    repository.candidate(new Candidate(UUID.randomUUID().toString(), c.owner(), "review-" + c.day(),
                            "daily", c.id(), c.id(), "PENDING", c.availableAt(), now.plus(Duration.ofHours(36)), now, null, ""));
                }
            }
        });
    }
    private Settings valid(Content c) {
        var s = authorization.require(c.owner());
        if (!barrier.isCurrent(c.generation()) || !s.grantId().equals(c.grantId()) || s.revision() != c.settingsRevision())
            throw new IllegalArgumentException("复盘授权或重置代次已变化");
        return s;
    }
    private record PriorContext(
            List<ReviewGenerationRequest.HistoryAction> actions,
            List<ReviewGenerationRequest.Observation> observations) {}

    private PriorContext priorContext(Content current, Settings s) {
        LocalDate earliest = current.day().minusDays(Math.max(1, s.historyDays()));
        Set<LocalDate> seenDays = new HashSet<>();
        List<ReviewGenerationRequest.HistoryAction> actions = new ArrayList<>();
        List<ReviewGenerationRequest.Observation> observations = new ArrayList<>();
        for (var item : repository.history(current.owner())) {
            if (item.id().equals(current.id()) || !"READY".equals(item.state()) || item.draft() == null) continue;
            if (item.day().isAfter(current.day()) || item.day().isBefore(earliest) || !seenDays.add(item.day())) continue;
            if (item.draft().actions() != null) {
                for (int i = 0; i < item.draft().actions().size() && actions.size() < 3; i++) {
                    var a = item.draft().actions().get(i);
                    int checkDays = a.checkAfterDays() == null ? 7 : a.checkAfterDays();
                    actions.add(new ReviewGenerationRequest.HistoryAction(
                            "act-" + item.day() + "-" + (i + 1),
                            a.topicKey(),
                            a.text(),
                            "SUGGESTED",
                            item.day().plusDays(checkDays).toString(),
                            "往期建议，未反馈不视为失败"));
                }
            }
            if (item.draft().observations() != null) {
                for (int i = 0; i < item.draft().observations().size() && observations.size() < 3; i++) {
                    var o = item.draft().observations().get(i);
                    observations.add(new ReviewGenerationRequest.Observation(
                            "obs-" + item.day() + "-" + (i + 1),
                            o.topicKey(),
                            o.text(),
                            o.uncertainty()));
                }
            }
            if (actions.size() >= 3 && observations.size() >= 3) break;
        }
        return new PriorContext(List.copyOf(actions), List.copyOf(observations));
    }

    private ReviewGenerationRequest request(Content c, Settings s, List<Material> text,
            List<ReviewGenerationRequest.Evidence> facts, String coverage, PriorContext prior) {
        List<String> topics = new ArrayList<>(List.of("study"));
        if (s.sources().contains("BUSINESS")) topics.add("todo");
        if (s.sources().contains("FINANCE")) topics.add("finance");
        return new ReviewGenerationRequest("LIFE_DAILY", c.day() + " 生活复盘 v" + c.version(), coverage, facts,
                text.stream().filter(m -> m.kind().equals("USER_GOAL")).map(m ->
                    new ReviewGenerationRequest.Goal(m.sourceId(), m.text(), "用户原始约束已包含在目标文本中", "参照原始目标文本")).toList(),
                prior.actions(), prior.observations(), ReviewMethodCards.select("DAILY_REPORT", topics),
                5000, 3000, 60000, s.providerId(), s.modelName(), text.stream().map(m ->
                new ReviewGenerationRequest.Narrative(m.id(), m.kind(), m.sourceId(), m.occurredAt(),
                    m.text(), m.truncated())).toList(), s.fingerprint());
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("生活复盘输入不可序列化"); }
    }
    private ContentView view(Content c) {
        List<ItemView> items = new ArrayList<>();
        if (c.draft() != null) {
            c.draft().observations().forEach(o -> items.add(new ItemView(o.kind(), o.text(), o.evidenceIds(), o.uncertainty())));
            if (c.draft().followUps() != null) {
                c.draft().followUps().forEach(f -> items.add(new ItemView("FOLLOW_UP", f.text(), f.evidenceIds(), "")));
            }
            c.draft().actions().forEach(a -> items.add(new ItemView("ACTION", a.text(), a.evidenceIds(), a.rationale())));
        }
        var originalGrant = repository.grantSettings(c.owner(), c.grantId());
        return new ContentView(c.id(), ReviewPolicy.fingerprint(c.owner() + ":" + c.day()), c.day(), c.timezone(), c.version(), c.state(),
                c.sourceAsOf(), c.availableAt(), originalGrant.map(Settings::providerId).orElse(""),
                originalGrant.map(Settings::modelName).orElse(""), c.coverage(), c.message(), c.materials().stream().map(m ->
                new MaterialView(m.id(), m.kind(), m.sourceId(), m.occurredAt(), m.text(), m.truncated())).toList(),
                c.facts().stream().map(f -> new FactView(f.id(), f.domain(), f.metricKey(), f.value(), f.unit(), f.currency(), f.period())).toList(),
                items, c.questions(), repository.deliveries(c.owner(), c.id()).stream()
                        .filter(d -> d.channel().equals("DESKTOP") || d.channel().equals("FEISHU"))
                        .map(d -> new DeliveryView(d.id(), d.channel(), d.state(), d.attempts())).toList());
    }
}
