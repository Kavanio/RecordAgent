package com.butvan.recordagent.service.record.impl;

import com.butvan.recordagent.dto.record.JournalDtos;
import com.butvan.recordagent.repository.record.JournalRepository;
import com.butvan.recordagent.service.record.JournalService;
import com.butvan.recordagent.service.time.BusinessTimeZone;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 手记创建与按日投影。 */
@Service
@RequiredArgsConstructor
public class JournalServiceImpl implements JournalService {
    private final JournalRepository store;

    @Override
    @Transactional
    public JournalDtos.Created create(UUID owner, JournalDtos.Create request) {
        BusinessTimeZone.parse(request.timeZone());
        UUID id = UUID.randomUUID();
        store.create(id, owner, request.date(), request.title().strip(),
                request.body() == null ? "" : request.body().strip());
        return new JournalDtos.Created(id, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalDtos.Journal> day(UUID owner, LocalDate date) {
        return store.between(owner, date, date.plusDays(1), 200);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalDtos.Journal> month(UUID owner, YearMonth month) {
        return store.between(owner, month.atDay(1), month.plusMonths(1).atDay(1), 1000);
    }

    @Override
    @Transactional(readOnly = true)
    public Summary summary(UUID owner) {
        long[] totals = store.summary(owner);
        return new Summary(totals[0], totals[1]);
    }
}
