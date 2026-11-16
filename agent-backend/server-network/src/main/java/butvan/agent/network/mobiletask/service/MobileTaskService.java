package butvan.agent.network.mobiletask.service;

import butvan.agent.network.mobiletask.dto.MobileTaskDtos.SaveMobileTaskRequest;
import butvan.agent.network.mobiletask.model.MobileTaskModels.MobileTask;
import butvan.agent.network.mobiletask.model.MobileTaskModels.MobileTaskDelivery;
import butvan.agent.network.mobiletask.model.MobileTaskModels.PageResult;
import butvan.agent.network.mobiletask.repository.MobileTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 手机端通知任务业务逻辑与校验。 */
@Service
@RequiredArgsConstructor
public class MobileTaskService {

    private static final Set<String> RECURRENCES = Set.of("once", "daily", "weekly", "monthly");
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_BODY_LENGTH = 500;
    private static final int MAX_PAGE_LIMIT = 100;

    private final MobileTaskRepository repository;

    @Transactional(readOnly = true)
    public PageResult<MobileTask> list(String ownerId, String query, Boolean enabled, int offset, int limit) {
        requireOwner(ownerId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(Math.max(1, limit), MAX_PAGE_LIMIT);
        List<MobileTask> rows = repository.findPage(ownerId, query, enabled, safeOffset, safeLimit + 1);
        boolean hasMore = rows.size() > safeLimit;
        List<MobileTask> items = hasMore ? rows.subList(0, safeLimit) : rows;
        return new PageResult<>(items, hasMore ? safeOffset + safeLimit : null, hasMore);
    }

    @Transactional(readOnly = true)
    public List<MobileTask> listEnabled(String ownerId) {
        requireOwner(ownerId);
        return repository.findEnabled(ownerId);
    }

    @Transactional(readOnly = true)
    public MobileTask get(String ownerId, String id) {
        requireOwner(ownerId);
        return repository.findById(ownerId, requireId(id))
                .orElseThrow(() -> new IllegalArgumentException("提醒任务不存在"));
    }

    @Transactional
    public MobileTask create(String ownerId, SaveMobileTaskRequest request) {
        requireOwner(ownerId);
        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString()
                : request.id().trim();
        MobileTask task = validateAndBuild(id, request, 1L);
        repository.insert(ownerId, task, System.currentTimeMillis());
        return task;
    }

    @Transactional
    public MobileTask update(String ownerId, String id, SaveMobileTaskRequest request) {
        requireOwner(ownerId);
        String taskId = requireId(id);
        MobileTask existing = repository.findById(ownerId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("提醒任务不存在"));
        MobileTask next = validateAndBuild(taskId, request, request.version() + 1);
        int updated = repository.update(ownerId, next, request.version(), System.currentTimeMillis());
        if (updated == 0) {
            throw new IllegalArgumentException("提醒任务已被其他设备更新（当前版本 " + existing.version() + "），请刷新后重试");
        }
        return get(ownerId, taskId);
    }

    @Transactional
    public void delete(String ownerId, String id, long version) {
        requireOwner(ownerId);
        String taskId = requireId(id);
        if (repository.findById(ownerId, taskId).isEmpty()) {
            throw new IllegalArgumentException("提醒任务不存在");
        }
        int deleted = repository.delete(ownerId, taskId, version);
        if (deleted == 0) {
            throw new IllegalArgumentException("提醒任务版本冲突，请刷新后重试");
        }
    }

    @Transactional(readOnly = true)
    public PageResult<MobileTaskDelivery> deliveries(String ownerId, String taskId, int offset, int limit) {
        requireOwner(ownerId);
        get(ownerId, taskId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(Math.max(1, limit), MAX_PAGE_LIMIT);
        List<MobileTaskDelivery> rows = repository.findDeliveries(ownerId, taskId, safeOffset, safeLimit + 1);
        boolean hasMore = rows.size() > safeLimit;
        List<MobileTaskDelivery> items = hasMore ? rows.subList(0, safeLimit) : rows;
        return new PageResult<>(items, hasMore ? safeOffset + safeLimit : null, hasMore);
    }

    @Transactional
    public void recordDelivery(String ownerId, String taskId, String eventId, Instant deliveredAt) {
        requireOwner(ownerId);
        get(ownerId, taskId);
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("通知事件 ID 不能为空");
        }
        Instant timestamp = deliveredAt == null ? Instant.now() : deliveredAt;
        repository.insertDeliveryIgnoreDuplicate(
                UUID.randomUUID().toString(), ownerId, taskId, eventId.trim(), timestamp);
    }

    private MobileTask validateAndBuild(String id, SaveMobileTaskRequest req, long version) {
        if (req == null) throw new IllegalArgumentException("请求不能为空");
        String title = req.title() == null ? "" : req.title().trim();
        if (title.isEmpty() || title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("提醒标题需在 1 到 120 个字符之间");
        }
        String body = req.body() == null ? "" : req.body().trim();
        if (body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("提醒正文不能超过 500 个字符");
        }
        String recurrence = req.recurrence() == null ? "" : req.recurrence().trim().toLowerCase(Locale.ROOT);
        if (!RECURRENCES.contains(recurrence)) {
            throw new IllegalArgumentException("不支持的重复周期");
        }
        if (req.hour() < 0 || req.hour() > 23 || req.minute() < 0 || req.minute() > 59) {
            throw new IllegalArgumentException("提醒时间不合法");
        }
        String tz = req.timeZone() == null || req.timeZone().isBlank() ? "Asia/Shanghai" : req.timeZone().trim();
        try {
            ZoneId.of(tz);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("时区不合法");
        }
        Integer weekday = null;
        Integer dayOfMonth = null;
        String onceDate = null;
        switch (recurrence) {
            case "weekly" -> {
                if (req.weekday() == null || req.weekday() < 1 || req.weekday() > 7) {
                    throw new IllegalArgumentException("周提醒星期需在 1 到 7 之间");
                }
                weekday = req.weekday();
            }
            case "monthly" -> {
                if (req.dayOfMonth() == null || req.dayOfMonth() < 1 || req.dayOfMonth() > 28) {
                    throw new IllegalArgumentException("月提醒日期需在 1 到 28 日之间");
                }
                dayOfMonth = req.dayOfMonth();
            }
            case "once" -> {
                if (req.onceDate() == null || req.onceDate().isBlank()) {
                    throw new IllegalArgumentException("一次性提醒必须提供日期");
                }
                try {
                    LocalDate.parse(req.onceDate().trim());
                    onceDate = req.onceDate().trim();
                } catch (DateTimeParseException exception) {
                    throw new IllegalArgumentException("一次性提醒日期格式应为 YYYY-MM-DD");
                }
            }
            default -> {
            }
        }
        return new MobileTask(id, title, body, recurrence, req.hour(), req.minute(),
                weekday, dayOfMonth, onceDate, tz, req.enabled(), version);
    }

    private void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("用户不能为空");
    }

    private String requireId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("任务 ID 不能为空");
        return id.trim();
    }
}
