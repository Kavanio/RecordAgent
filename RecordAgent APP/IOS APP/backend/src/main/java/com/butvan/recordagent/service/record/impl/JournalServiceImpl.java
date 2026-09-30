package com.butvan.recordagent.service.record.impl;

import com.butvan.recordagent.dto.query.DailySummary;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.dto.record.JournalDtos;
import com.butvan.recordagent.repository.record.JournalRepository;
import com.butvan.recordagent.service.query.ReadBounds;
import com.butvan.recordagent.service.record.JournalService;
import com.butvan.recordagent.service.time.BusinessTimeZone;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 手记创建与全部资料的按日投影。 */
@Service
@RequiredArgsConstructor
public class JournalServiceImpl implements JournalService {
    private final JournalRepository store;
    private final ReadBounds bounds;

    @Override
    @Transactional
    public JournalDtos.Created create(UUID owner, JournalDtos.Create request) {
        BusinessTimeZone.parse(request.timeZone());
        UUID id = UUID.randomUUID();
        store.create(
                id,
                owner,
                request.date(),
                request.title().strip(),
                request.body() == null ? "" : request.body().strip());
        return new JournalDtos.Created(id, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalDtos.Journal> day(UUID owner, LocalDate date) {
        return bounds.requireComplete(
                store.between(owner, date, date.plusDays(1), bounds.dayLimit() + 1),
                bounds.dayLimit());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailySummary> month(UUID owner, YearMonth month) {
        return store.monthSummary(owner, month.atDay(1), month.plusMonths(1).atDay(1));
    }

    @Override
    @Transactional(readOnly = true)
    public Summary summary(UUID owner) {
        var result = store.summary(owner);
        return new Summary(result.documentCount(), result.characterCount());
    }

    /** 单页只读取 limit+1 条以识别是否还有记录。 */
    @Override
    @Transactional(readOnly = true)
    public Page<JournalDtos.Journal> page(UUID owner, LocalDate date, int offset, int limit) {
        bounds.validatePage(offset, limit);
        return bounds.page(
                store.between(owner, date, date.plusDays(1), limit + 1, offset), offset, limit);
    }
}
