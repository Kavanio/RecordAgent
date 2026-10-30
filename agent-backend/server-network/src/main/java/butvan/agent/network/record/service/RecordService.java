package butvan.agent.network.record.service;

import butvan.agent.network.record.model.RecordModels.DaySummary;
import butvan.agent.network.record.model.RecordModels.RecordCommand;
import butvan.agent.network.record.model.RecordModels.RecordEntry;
import butvan.agent.network.record.model.RecordModels.RecordType;
import butvan.agent.network.record.model.RecordModels.RecordReference;
import butvan.agent.network.record.model.RecordModels.RecordReferencePage;
import butvan.agent.network.record.repository.RecordRepository;
import butvan.agent.network.daily.service.DailyEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.UUID;

/** 资料库的领域入口，集中维护类型、自然周、标签和乐观锁规则。 */
@Service
@RequiredArgsConstructor
public class RecordService {
    private static final int MAX_CONTENT_LENGTH = 2_000_000;
    private final RecordRepository repository;
    private final RecordTabService tabService;
    private final DailyEventService dailyEventService;

    /** 独立授权的生活复盘只读手记片段；单次最多七日、八条，每条六百字符。 */
    public List<butvan.agent.network.record.model.RecordModels.JournalFragment> journalFragments(
            String owner, LocalDate from, LocalDate to) {
        requireRange(from, to);
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 6)
            throw new IllegalArgumentException("手记片段最多读取七日");
        return repository.journalFragments(owner, from, to);
    }

    /** 查询记录列表。 */
    public List<RecordEntry> search(String ownerId, LocalDate from, LocalDate to,
                                    String type, String tag, String query, String tabId) {
        requireRange(from, to);
        if (type != null && !type.isBlank()) RecordType.parse(type);
        return repository.search(ownerId, from, to, type, tag, query, tabId);
    }

    /** 查询 Slash Command 可引用的资料；上限保护避免一次加载完整资料库。 */
    public RecordReferencePage searchReferences(String ownerId, String query, int limit, int offset) {
        if (query != null && query.length() > 100) throw new IllegalArgumentException("资料搜索词不能超过 100 个字符");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("资料候选数量必须在 1 到 100 之间");
        if (offset < 0) throw new IllegalArgumentException("资料候选偏移量不能小于零");
        List<RecordReference> matches = repository.searchReferences(ownerId, query, limit + 1, offset);
        boolean hasMore = matches.size() > limit;
        List<RecordReference> items = hasMore ? List.copyOf(matches.subList(0, limit)) : List.copyOf(matches);
        return new RecordReferencePage(items, hasMore, offset + items.size());
    }

    /** 查询日历摘要。 */
    public List<DaySummary> summarizeDays(String ownerId, LocalDate from, LocalDate to) {
        requireRange(from, to);
        return repository.summarizeDays(ownerId, from, to);
    }

    /** 查询一条记录。 */
    public RecordEntry get(String ownerId, String id) {
        return repository.find(ownerId, id).orElseThrow(() -> new IllegalArgumentException("资料不存在"));
    }

    /** 返回可发送给模型的资料；归档或回收状态不会被旁路引用。 */
    public RecordEntry getReference(String ownerId, String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("资料引用不能为空");
        RecordEntry record = get(ownerId, id);
        if (record.archived() || record.trashedAt() != null) {
            throw new IllegalArgumentException("资料已归档或进入回收站，当前不可引用");
        }
        return record;
    }

    /** 创建记录并原子写入标签。 */
    @Transactional
    public RecordEntry create(String ownerId, RecordCommand command) {
        validate(command);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        WeekBinding week = weekBinding(command);
        String tabId = tabService.resolve(ownerId, command.tabId(), command.type());
        repository.insert(id, ownerId, command.recordDate(), command.type(), cleanTitle(command.title()),
                command.contentHtml(), command.contentText(), tabId, week.year(), week.number(), now);
        repository.replaceTags(ownerId, id, normalizedTags(command.tags()), now);
        syncJournal(ownerId, id, command);
        return get(ownerId, id);
    }

    /** 以版本号保护方式覆盖记录与标签。 */
    @Transactional
    public RecordEntry update(String ownerId, String id, int expectedVersion, RecordCommand command) {
        validate(command);
        RecordEntry current = get(ownerId, id);
        WeekBinding week = weekBinding(command);
        String tabId = tabService.resolve(ownerId, command.tabId(), command.type());
        Instant now = Instant.now();
        if (!repository.update(ownerId, id, expectedVersion, command.recordDate(), command.type(),
                cleanTitle(command.title()), command.contentHtml(), command.contentText(), tabId, week.year(), week.number(), now)) {
            throw new IllegalArgumentException("资料已被修改或不存在，请刷新后重试");
        }
        repository.replaceTags(ownerId, id, normalizedTags(command.tags()), now);
        if (command.type() == RecordType.JOURNAL) syncJournal(ownerId, id, command, current);
        else if (current.type() == RecordType.JOURNAL) removeLinkedJournal(ownerId, current);
        return get(ownerId, id);
    }

    /** 修改置顶、收藏或归档状态。 */
    @Transactional
    public RecordEntry updateFlags(String ownerId, String id, int expectedVersion,
                                   Boolean pinned, Boolean favorite, Boolean archived) {
        RecordEntry current = get(ownerId, id);
        boolean nextPinned = pinned == null ? current.pinned() : pinned;
        boolean nextFavorite = favorite == null ? current.favorite() : favorite;
        boolean nextArchived = archived == null ? current.archived() : archived;
        if (!repository.updateFlags(ownerId, id, expectedVersion, nextPinned, nextFavorite, nextArchived, Instant.now())) {
            throw new IllegalArgumentException("资料已被修改，请刷新后重试");
        }
        return get(ownerId, id);
    }

    /** 将记录移入回收站。 */
    @Transactional
    public void trash(String ownerId, String id, int expectedVersion) {
        RecordEntry current = get(ownerId, id);
        if (!repository.setTrashed(ownerId, id, expectedVersion, Instant.now(), Instant.now())) {
            throw new IllegalArgumentException("资料已被修改或不存在，请刷新后重试");
        }
        if (current.type() == RecordType.JOURNAL) removeLinkedJournal(ownerId, current);
    }

    /** 从回收站恢复记录。 */
    @Transactional
    public RecordEntry restore(String ownerId, String id, int expectedVersion) {
        if (!repository.setTrashed(ownerId, id, expectedVersion, null, Instant.now())) {
            throw new IllegalArgumentException("资料已被修改或不存在，请刷新后重试");
        }
        RecordEntry restored = get(ownerId, id);
        if (restored.type() == RecordType.JOURNAL) {
            syncJournal(ownerId, id, new RecordCommand(restored.recordDate(), restored.type(), restored.title(),
                    restored.contentHtml(), restored.contentText(), restored.tags(), restored.tabId()), restored);
        }
        return restored;
    }

    public List<RecordEntry> trashEntries(String ownerId) { return repository.findTrash(ownerId); }
    @Transactional
    public int clearTrash(String ownerId) {
        repository.findTrash(ownerId).stream().filter(record -> record.type() == RecordType.JOURNAL)
                .forEach(record -> removeLinkedJournal(ownerId, record));
        return repository.clearTrash(ownerId);
    }

    /** 导入备份前清除资料及其日历同步投影。 */
    @Transactional
    public int clearAllForImport(String ownerId) {
        repository.findAll(ownerId).stream().filter(record -> record.type() == RecordType.JOURNAL)
                .forEach(record -> removeLinkedJournal(ownerId, record));
        return repository.clearAll(ownerId);
    }

    private void validate(RecordCommand command) {
        if (command == null || command.recordDate() == null || command.type() == null) {
            throw new IllegalArgumentException("资料日期和类型不能为空");
        }
        String html = command.contentHtml() == null ? "" : command.contentHtml();
        String text = command.contentText() == null ? "" : command.contentText();
        if (html.length() > MAX_CONTENT_LENGTH || text.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("资料正文过长");
        }
        if ((command.title() == null || command.title().isBlank()) && text.isBlank()) {
            throw new IllegalArgumentException("标题和正文不能同时为空");
        }
        if (command.tags() != null && command.tags().size() > 30) throw new IllegalArgumentException("每条资料最多添加 30 个标签");
    }

    private void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("日期范围不合法");
        if (from.plusYears(2).isBefore(to)) throw new IllegalArgumentException("单次查询日期范围不能超过两年");
    }

    private WeekBinding weekBinding(RecordCommand command) {
        if (command.type() != RecordType.WEEKLY_REVIEW) return new WeekBinding(null, null);
        WeekFields fields = WeekFields.ISO;
        return new WeekBinding(command.recordDate().get(fields.weekBasedYear()), command.recordDate().get(fields.weekOfWeekBasedYear()));
    }

    private String cleanTitle(String title) {
        String value = title == null ? "" : title.trim();
        if (value.length() > 200) throw new IllegalArgumentException("标题不能超过 200 个字符");
        return value.isEmpty() ? null : value;
    }

    private List<String> normalizedTags(List<String> tags) {
        return (tags == null ? List.<String>of() : tags).stream().filter(java.util.Objects::nonNull)
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().peek(value -> {
                    if (value.length() > 40) throw new IllegalArgumentException("标签不能超过 40 个字符");
                }).toList();
    }

    private void syncJournal(String ownerId, String recordId, RecordCommand command) {
        if (command.type() == RecordType.JOURNAL) {
            dailyEventService.syncRecordJournal(ownerId, recordId, command.recordDate(),
                    cleanTitle(command.title()), command.contentText());
        }
    }

    private void syncJournal(String ownerId, String recordId, RecordCommand command, RecordEntry current) {
        if ("calendar".equals(current.source()) && current.sourceReference() != null) {
            dailyEventService.syncCalendarJournalFromRecord(ownerId, current.sourceReference(), command.recordDate(),
                    cleanTitle(command.title()), command.contentText());
            return;
        }
        syncJournal(ownerId, recordId, command);
    }

    private void removeLinkedJournal(String ownerId, RecordEntry record) {
        if ("calendar".equals(record.source()) && record.sourceReference() != null) {
            dailyEventService.removeCalendarJournalFromRecord(ownerId, record.sourceReference());
            return;
        }
        dailyEventService.removeRecordJournal(ownerId, record.id());
    }

    private record WeekBinding(Integer year, Integer number) { }
}
