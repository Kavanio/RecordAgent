package com.butvan.recordagent.service.study.impl;

import com.butvan.recordagent.dto.study.StudyDtos;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.study.StudyRepository;
import com.butvan.recordagent.service.study.StudyService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
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

    @Override
    @Transactional(readOnly = true)
    public List<StudyDtos.Session> day(UUID owner, LocalDate date, String timeZone) {
        ZoneId zone;
        try { zone = ZoneId.of(timeZone); }
        catch (Exception exception) { throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid time zone"); }
        var start = date.atStartOfDay(zone);
        var end = date.plusDays(1).atStartOfDay(zone);
        return store.overlapping(owner, start.toOffsetDateTime(), end.toOffsetDateTime(), 200).stream().map(row -> {
            var clippedStart = row.start().toInstant().isAfter(start.toInstant())
                    ? row.start().toInstant() : start.toInstant();
            var clippedEnd = row.end().toInstant().isBefore(end.toInstant())
                    ? row.end().toInstant() : end.toInstant();
            return new StudyDtos.Session(row.id(), row.title(), date,
                    clippedStart.atZone(zone).toLocalTime(), Duration.between(clippedStart, clippedEnd).toMinutes());
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StudyDtos.Session> month(UUID owner, YearMonth month, String timeZone) {
        ZoneId zone;
        try { zone = ZoneId.of(timeZone); }
        catch (Exception exception) { throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid time zone"); }
        var start = month.atDay(1).atStartOfDay(zone);
        var end = month.plusMonths(1).atDay(1).atStartOfDay(zone);
        List<StudyDtos.Session> result = new ArrayList<>();
        for (var row : store.overlapping(owner, start.toOffsetDateTime(), end.toOffsetDateTime(), 1000)) {
            var first = row.start().toInstant().isAfter(start.toInstant())
                    ? row.start().toInstant() : start.toInstant();
            var last = row.end().toInstant().isBefore(end.toInstant())
                    ? row.end().toInstant() : end.toInstant();
            if (!last.isAfter(first)) continue;
            LocalDate day = first.atZone(zone).toLocalDate();
            LocalDate finalDay = last.minusNanos(1).atZone(zone).toLocalDate();
            while (!day.isAfter(finalDay)) {
                var dayStart = day.atStartOfDay(zone).toInstant();
                var dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
                var clippedStart = first.isAfter(dayStart) ? first : dayStart;
                var clippedEnd = last.isBefore(dayEnd) ? last : dayEnd;
                result.add(new StudyDtos.Session(row.id(), row.title(), day,
                        clippedStart.atZone(zone).toLocalTime(), Duration.between(clippedStart, clippedEnd).toMinutes()));
                day = day.plusDays(1);
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public long totalMinutes(UUID owner) { return store.totalMinutes(owner); }
}
