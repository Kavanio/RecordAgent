package com.butvan.recordagent.service.study.impl;

import com.butvan.recordagent.dto.query.DailySummary;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.dto.study.StudyDtos;
import com.butvan.recordagent.repository.study.StudyRepository;
import com.butvan.recordagent.service.query.ReadBounds;
import com.butvan.recordagent.service.study.StudyService;
import com.butvan.recordagent.service.time.BusinessTimeZone;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将绝对时间区间按请求时区切成自然日记录。 */
@Service
@RequiredArgsConstructor
public class StudyServiceImpl implements StudyService {
    private final StudyRepository store;
    private final ReadBounds bounds;

    @Override
    @Transactional(readOnly = true)
    public List<StudyDtos.Session> day(UUID owner, LocalDate date, String timeZone) {
        ZoneId zone = BusinessTimeZone.parse(timeZone);
        var start = date.atStartOfDay(zone);
        var end = date.plusDays(1).atStartOfDay(zone);
        return bounds
                .requireComplete(
                        store.overlapping(
                                owner,
                                start.toOffsetDateTime(),
                                end.toOffsetDateTime(),
                                bounds.dayLimit() + 1),
                        bounds.dayLimit())
                .stream()
                .map(
                        row -> {
                            var clippedStart =
                                    row.start().toInstant().isAfter(start.toInstant())
                                            ? row.start().toInstant()
                                            : start.toInstant();
                            var clippedEnd =
                                    row.end().toInstant().isBefore(end.toInstant())
                                            ? row.end().toInstant()
                                            : end.toInstant();
                            return new StudyDtos.Session(
                                    row.id(),
                                    row.title(),
                                    date,
                                    clippedStart.atZone(zone).toLocalTime(),
                                    Duration.between(clippedStart, clippedEnd).toMinutes());
                        })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailySummary> month(UUID owner, YearMonth month, String timeZone) {
        ZoneId zone = BusinessTimeZone.parse(timeZone);
        return store.monthSummary(
                owner, month.atDay(1), month.plusMonths(1).atDay(1), zone.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public long totalMinutes(UUID owner) {
        return store.totalMinutes(owner);
    }

    /** 按自然日裁剪全部时段后聚合，不受明细上限影响。 */
    @Override
    @Transactional(readOnly = true)
    public long minutesOn(UUID owner, LocalDate date, String timeZone) {
        ZoneId zone = BusinessTimeZone.parse(timeZone);
        return store.minutesBetween(
                owner,
                date.atStartOfDay(zone).toOffsetDateTime(),
                date.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
    }

    /** 按自然日裁剪一页时段；午夜和夏令时边界由 ZoneId 处理。 */
    @Override
    @Transactional(readOnly = true)
    public Page<StudyDtos.Session> page(
            UUID owner, LocalDate date, String timeZone, int offset, int limit) {
        bounds.validatePage(offset, limit);
        ZoneId zone = BusinessTimeZone.parse(timeZone);
        var start = date.atStartOfDay(zone).toInstant();
        var end = date.plusDays(1).atStartOfDay(zone).toInstant();
        var rows =
                store.overlapping(
                        owner,
                        start.atOffset(ZoneOffset.UTC),
                        end.atOffset(ZoneOffset.UTC),
                        limit + 1,
                        offset);
        var mapped =
                rows.stream()
                        .map(
                                row -> {
                                    var first =
                                            row.start().toInstant().isAfter(start)
                                                    ? row.start().toInstant()
                                                    : start;
                                    var last =
                                            row.end().toInstant().isBefore(end)
                                                    ? row.end().toInstant()
                                                    : end;
                                    return new StudyDtos.Session(
                                            row.id(),
                                            row.title(),
                                            date,
                                            first.atZone(zone).toLocalTime(),
                                            Duration.between(first, last).toMinutes());
                                })
                        .toList();
        return bounds.page(mapped, offset, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StudyDtos.Entry> entries(
            UUID owner, YearMonth month, String zone, String query, int offset, int limit) {
        bounds.validatePage(offset, limit);
        if (query.length() > 200)
            throw new com.butvan.recordagent.handler.ApiException(
                    com.butvan.recordagent.handler.ApiErrorCode.INVALID_ARGUMENT, "invalid search");
        var z = BusinessTimeZone.parse(zone);
        return bounds.page(
                store.entries(
                        owner,
                        month.atDay(1).atStartOfDay(z).toOffsetDateTime(),
                        month.plusMonths(1).atDay(1).atStartOfDay(z).toOffsetDateTime(),
                        query.strip(),
                        offset,
                        limit + 1),
                offset,
                limit);
    }

    @Override
    @Transactional(readOnly = true)
    public StudyDtos.Overview overview(UUID owner, YearMonth month, String zone) {
        var z = BusinessTimeZone.parse(zone);
        return store.overview(owner, month.atDay(1), month.plusMonths(1).atDay(1), z.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public StudyDtos.Entry active(UUID owner) {
        return store.active(owner).orElse(null);
    }

    @Override
    @Transactional
    public StudyDtos.Entry create(UUID owner, StudyDtos.Save s) {
        BusinessTimeZone.parse(s.timeZone());
        if (s.title().isBlank()
                || s.startedAt().toInstant().isAfter(java.time.Instant.now().plusSeconds(60)))
            throw invalid();
        if (s.endedAt() != null) validateInterval(s.startedAt(), s.endedAt());
        store.lockOwner(owner);
        var previous = store.find(owner, s.id());
        if (previous.isPresent()) {
            var p = previous.get();
            if (p.title().equals(s.title().strip())
                    && p.startedAt().toInstant().equals(s.startedAt().toInstant())
                    && java.util.Objects.equals(
                            p.endedAt() == null ? null : p.endedAt().toInstant(),
                            s.endedAt() == null ? null : s.endedAt().toInstant())
                    && p.timeZone().equals(s.timeZone())) return p;
            throw conflict();
        }
        if (s.endedAt() == null && store.active(owner).isPresent()) throw conflict();
        store.create(owner, s);
        return store.find(owner, s.id()).orElseThrow();
    }

    @Override
    @Transactional
    public StudyDtos.Entry finish(UUID owner, UUID id, StudyDtos.Finish s) {
        var p =
                store.find(owner, id)
                        .orElseThrow(
                                () ->
                                        new com.butvan.recordagent.handler.ApiException(
                                                com.butvan.recordagent.handler.ApiErrorCode
                                                        .NOT_FOUND,
                                                "session not found"));
        if (p.endedAt() != null && p.endedAt().toInstant().equals(s.endedAt().toInstant()))
            return p;
        validateInterval(p.startedAt(), s.endedAt());
        if (store.finish(owner, id, s.version(), s.endedAt()) == 0) throw conflict();
        return store.find(owner, id).orElseThrow();
    }

    @Override
    @Transactional
    public void delete(UUID owner, UUID id, long version) {
        if (store.find(owner, id).isEmpty())
            throw new com.butvan.recordagent.handler.ApiException(
                    com.butvan.recordagent.handler.ApiErrorCode.NOT_FOUND, "session not found");
        if (store.delete(owner, id, version) == 0) throw conflict();
    }

    private void validateInterval(java.time.OffsetDateTime start, java.time.OffsetDateTime end) {
        var seconds = Duration.between(start, end).getSeconds();
        if (seconds < 60
                || seconds > 7 * 24 * 60 * 60
                || end.toInstant().isAfter(java.time.Instant.now().plusSeconds(60)))
            throw invalid();
    }

    private com.butvan.recordagent.handler.ApiException invalid() {
        return new com.butvan.recordagent.handler.ApiException(
                com.butvan.recordagent.handler.ApiErrorCode.INVALID_ARGUMENT, "invalid interval");
    }

    private com.butvan.recordagent.handler.ApiException conflict() {
        return new com.butvan.recordagent.handler.ApiException(
                com.butvan.recordagent.handler.ApiErrorCode.VERSION_CONFLICT, "session changed");
    }
}
