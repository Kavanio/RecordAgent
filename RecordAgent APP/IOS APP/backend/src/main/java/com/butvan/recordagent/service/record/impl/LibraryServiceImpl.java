package com.butvan.recordagent.service.record.impl;

import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.dto.record.LibraryDtos;
import com.butvan.recordagent.handler.*;
import com.butvan.recordagent.repository.record.LibraryRepository;
import com.butvan.recordagent.service.query.ReadBounds;
import com.butvan.recordagent.service.record.LibraryService;
import com.butvan.recordagent.service.time.BusinessTimeZone;
import java.time.temporal.WeekFields;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 资料正文、手记和收藏保持同一权威事实，不创建客户端副本。 */
@Service
@RequiredArgsConstructor
public class LibraryServiceImpl implements LibraryService {
    private final LibraryRepository store;
    private final ReadBounds bounds;
    private static final Set<String> TYPES =
            Set.of("quick", "learning", "weekly_review", "reading", "journal");

    @Transactional(readOnly = true)
    public Page<LibraryDtos.Item> page(
            UUID owner,
            String type,
            boolean favorite,
            boolean trash,
            String query,
            int offset,
            int limit) {
        bounds.validatePage(offset, limit);
        if (!type.isEmpty() && !TYPES.contains(type) || query.length() > 200)
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid filter");
        return bounds.page(
                store.page(owner, type, favorite, trash, query.strip(), offset, limit + 1),
                offset,
                limit);
    }

    @Transactional(readOnly = true)
    public LibraryDtos.Document document(UUID owner, UUID id) {
        return store.find(owner, id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "document not found"));
    }

    @Transactional
    public LibraryDtos.Document create(UUID owner, LibraryDtos.Save s) {
        validate(s);
        var year = year(s);
        var week = week(s);
        if (store.create(owner, s, year, week) == 0) {
            var old = document(owner, s.id());
            if (!same(old, s))
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "draft reused");
            return old;
        }
        return document(owner, s.id());
    }

    @Transactional
    public LibraryDtos.Document update(UUID owner, UUID id, LibraryDtos.Save s) {
        validate(s);
        document(owner, id);
        if (!id.equals(s.id()) || store.update(owner, id, s, year(s), week(s)) == 0)
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "document changed");
        return document(owner, id);
    }

    @Transactional
    public LibraryDtos.Document flags(UUID owner, UUID id, LibraryDtos.Flags s) {
        document(owner, id);
        if (store.flags(owner, id, s) == 0)
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "document changed");
        return document(owner, id);
    }

    private void validate(LibraryDtos.Save s) {
        BusinessTimeZone.parse(s.timeZone());
        if (!TYPES.contains(s.type()) || s.title().isBlank())
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid document");
    }

    private Integer year(LibraryDtos.Save s) {
        return s.type().equals("weekly_review")
                ? s.date().get(WeekFields.ISO.weekBasedYear())
                : null;
    }

    private Integer week(LibraryDtos.Save s) {
        return s.type().equals("weekly_review")
                ? s.date().get(WeekFields.ISO.weekOfWeekBasedYear())
                : null;
    }

    private boolean same(LibraryDtos.Document d, LibraryDtos.Save s) {
        return d.date().equals(s.date())
                && d.type().equals(s.type())
                && d.title().equals(s.title().strip())
                && d.body().equals(s.body())
                && !d.trashed();
    }
}
