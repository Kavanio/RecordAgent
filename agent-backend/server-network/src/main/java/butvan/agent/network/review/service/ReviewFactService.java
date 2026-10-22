package butvan.agent.network.review.service;

import butvan.agent.network.review.dto.ReviewDtos.*;
import butvan.agent.network.review.model.ReviewModels.FactSnapshot;
import butvan.agent.network.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** 有界历史摘要入口：采集在写事务外执行，提交前再次核查配置与授权版本。 */
@Service
@RequiredArgsConstructor
public class ReviewFactService {
    private final ReviewRepository repository;
    private final ReviewAuthorizationService authorization;
    private final ReviewFactCollector collector;
    private final PlatformTransactionManager transactions;
    private final Clock automationClock;

    /** 首阶段只接受最多七个已结束自然日，不允许偷偷读取今天或无限历史。 */
    public SnapshotView capture(String owner, CaptureRequest input) {
        ReviewPolicy.requireOwner(owner);
        var allowed = authorization.requireEnabled(owner);
        if (input == null || input.from() == null || input.to() == null || input.timezone() == null)
            throw new IllegalArgumentException("请提供有效的日期与时区");
        ZoneId zone;
        try { zone = ZoneId.of(input.timezone()); }
        catch (DateTimeException exception) { throw new IllegalArgumentException("时区无效"); }
        LocalDate today = automationClock.instant().atZone(zone).toLocalDate();
        if (input.from().isAfter(input.to()) || !input.to().isBefore(today)
                || input.from().isBefore(today.minusDays(allowed.settings().historyDays()))
                || ChronoUnit.DAYS.between(input.from(), input.to()) >= 7)
            throw new IllegalArgumentException("摘要仅支持授权窗口内最多 7 个已结束自然日");
        var data = collector.collect(owner, input.from(), input.to(), zone, allowed.settings().domains());
        if (data.sources().stream().allMatch(source -> "UNAVAILABLE".equals(source.state())))
            throw new IllegalStateException("复盘数据来源暂不可用，请稍后重试");
        Instant now = automationClock.instant();
        String fingerprint = ReviewPolicy.fingerprint("metric-v1\n" + ReviewPolicy.encode(allowed.settings().domains())
                + "\n" + data.facts() + "\n" + data.sources());
        var value = new FactSnapshot(UUID.randomUUID().toString(), owner, allowed.grant().id(), input.from(), input.to(),
                zone.getId(), now, 1, fingerprint, now.plus(Duration.ofDays(180)), data.facts(), data.sources());
        return new TransactionTemplate(transactions).execute(status -> {
            var current = authorization.requireEnabled(owner);
            if (!current.grant().id().equals(allowed.grant().id())
                    || current.grant().revision() != allowed.grant().revision()
                    || current.settings().revision() != allowed.settings().revision())
                throw new IllegalArgumentException("采集期间授权或配置已变化，请重新操作");
            String id = repository.insertSnapshot(value);
            return SnapshotView.from(repository.snapshot(owner, id, now)
                    .orElseThrow(() -> new IllegalStateException("摘要保存失败")));
        });
    }

    /** 历史读取不触发采集／模型，不要求保留自动执行授权才能查看已有本地数据。 */
    @Transactional(readOnly = true)
    public List<SnapshotSummaryView> list(String owner, int limit) {
        ReviewPolicy.requireOwner(owner);
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("摘要查询数量必须为 1–50");
        Instant now = automationClock.instant();
        return repository.snapshotHeaders(owner, limit, now).stream().map(SnapshotSummaryView::from).toList();
    }

    @Transactional(readOnly = true)
    public SnapshotView get(String owner, String id) {
        ReviewPolicy.requireOwner(owner);
        return SnapshotView.from(repository.snapshot(owner, id, automationClock.instant())
                .orElseThrow(() -> new IllegalArgumentException("摘要不存在或已过期")));
    }
}
