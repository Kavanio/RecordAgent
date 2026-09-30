package com.butvan.recordagent.service.task.impl;

import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.dto.task.TaskDtos;
import com.butvan.recordagent.handler.*;
import com.butvan.recordagent.repository.task.TaskRepository;
import com.butvan.recordagent.service.query.ReadBounds;
import com.butvan.recordagent.service.task.TaskService;
import com.butvan.recordagent.service.time.BusinessTimeZone;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 手机提醒配置事务；通知权限及实际注册结果由手机单独报告。 */
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {
    private final TaskRepository store;
    private final ReadBounds bounds;

    @Transactional(readOnly = true)
    public Page<TaskDtos.Task> page(
            UUID owner, String query, Boolean enabled, int offset, int limit) {
        bounds.validatePage(offset, limit);
        if (query.length() > 200) throw invalid();
        return bounds.page(
                store.page(owner, query.strip(), enabled, offset, limit + 1), offset, limit);
    }

    @Transactional(readOnly = true)
    public TaskDtos.Task detail(UUID owner, UUID id) {
        return find(owner, id);
    }

    @Transactional(readOnly = true)
    public List<TaskDtos.Task> enabled(UUID owner) {
        return store.enabled(owner);
    }

    @Transactional
    public TaskDtos.Task create(UUID owner, TaskDtos.Save s) {
        store.lockOwner(owner);
        var old = store.find(owner, s.id());
        if (old.isPresent()) {
            if (!same(old.get(), s)) throw conflict();
            return old.get();
        }
        validate(s);
        ensureCapacity(owner, s, null);
        if (store.create(owner, s) == 0) throw conflict();
        return find(owner, s.id());
    }

    @Transactional
    public TaskDtos.Task update(UUID owner, UUID id, TaskDtos.Save s) {
        validate(s);
        store.lockOwner(owner);
        find(owner, id);
        ensureCapacity(owner, s, id);
        if (!id.equals(s.id()) || store.update(owner, id, s) == 0) throw conflict();
        return find(owner, id);
    }

    @Transactional
    public void delete(UUID owner, UUID id, long version) {
        find(owner, id);
        if (store.delete(owner, id, version) == 0) throw conflict();
    }

    @Transactional
    public void observe(UUID owner, UUID id, TaskDtos.Observation s) {
        find(owner, id);
        if (s.deliveredAt().toInstant().isAfter(Instant.now().plusSeconds(60))) throw invalid();
        store.observe(owner, id, s);
    }

    @Transactional(readOnly = true)
    public Page<TaskDtos.Delivery> history(UUID owner, UUID id, int offset, int limit) {
        find(owner, id);
        bounds.validatePage(offset, limit);
        return bounds.page(store.history(owner, id, offset, limit + 1), offset, limit);
    }

    private TaskDtos.Task find(UUID owner, UUID id) {
        return store.find(owner, id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "task not found"));
    }

    private void ensureCapacity(UUID owner, TaskDtos.Save s, UUID replaced) {
        if (s.enabled()
                && store.enabled(owner).stream().filter(t -> !t.id().equals(replaced)).count()
                        >= 32)
            throw new ApiException(ApiErrorCode.RESULT_LIMIT_EXCEEDED, "notification limit");
    }

    /** 月提醒限定 1–28 日，保证每个月都有该自然日；每周使用 ISO 1–7。 */
    private void validate(TaskDtos.Save s) {
        var zone = BusinessTimeZone.parse(s.timeZone());
        if (s.title().isBlank()
                || !Set.of("once", "daily", "weekly", "monthly").contains(s.recurrence()))
            throw invalid();
        boolean weekly = s.recurrence().equals("weekly"),
                monthly = s.recurrence().equals("monthly"),
                once = s.recurrence().equals("once");
        if (weekly
                ? (s.weekday() == null || s.weekday() < 1 || s.weekday() > 7)
                : s.weekday() != null) throw invalid();
        if (monthly
                ? (s.dayOfMonth() == null || s.dayOfMonth() < 1 || s.dayOfMonth() > 28)
                : s.dayOfMonth() != null) throw invalid();
        if (once ? s.onceDate() == null : s.onceDate() != null) throw invalid();
        if (once
                && s.enabled()
                && !s.onceDate()
                        .atTime(s.hour(), s.minute())
                        .atZone(zone)
                        .toInstant()
                        .isAfter(Instant.now())) throw invalid();
    }

    private boolean same(TaskDtos.Task p, TaskDtos.Save s) {
        return p.title().equals(s.title().strip())
                && p.body().equals(s.body())
                && p.recurrence().equals(s.recurrence())
                && p.hour() == s.hour()
                && p.minute() == s.minute()
                && Objects.equals(p.weekday(), s.weekday())
                && Objects.equals(p.dayOfMonth(), s.dayOfMonth())
                && Objects.equals(p.onceDate(), s.onceDate())
                && p.timeZone().equals(s.timeZone())
                && p.enabled() == s.enabled();
    }

    private ApiException invalid() {
        return new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid schedule");
    }

    private ApiException conflict() {
        return new ApiException(ApiErrorCode.VERSION_CONFLICT, "task changed");
    }
}
