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
}
