package com.butvan.recordagent.service.calendar.impl;

import com.butvan.recordagent.dto.calendar.ScheduleDtos;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.calendar.ScheduleRepository;
import com.butvan.recordagent.service.calendar.ScheduleService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 日程的日期、时区与写入规则。 */
@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {
    private final ScheduleRepository store;

    @Override
    @Transactional
    public ScheduleDtos.Created create(UUID owner, ScheduleDtos.Create request) {
        ZoneId zone;
        try { zone = ZoneId.of(request.timeZone()); }
        catch (Exception exception) { throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid time zone"); }
        var start = request.startAt().atZoneSameInstant(zone);
        var end = request.endAt() == null ? null : request.endAt().atZoneSameInstant(zone);
        if (end != null && (!end.toLocalDate().equals(start.toLocalDate()) || !end.isAfter(start))) {
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid schedule end");
        }
        UUID id = UUID.randomUUID();
        store.create(id, owner, request.title().strip(), request.detail(), start.toLocalDate(),
                start.toLocalTime(), end == null ? null : end.toLocalTime(), zone.getId());
        return new ScheduleDtos.Created(id, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScheduleDtos.Schedule> day(UUID owner, LocalDate date) {
        return store.between(owner, date, date.plusDays(1), 200);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScheduleDtos.Schedule> month(UUID owner, YearMonth month) {
        return store.between(owner, month.atDay(1), month.plusMonths(1).atDay(1), 1000);
    }
}
