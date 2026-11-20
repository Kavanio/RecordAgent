package butvan.agent.network.record.dto;

import butvan.agent.network.record.model.RecordModels.DaySummary;
import butvan.agent.network.record.model.RecordModels.RecordEntry;
import butvan.agent.network.record.model.RecordModels.RecordAttachment;
import butvan.agent.network.record.model.RecordModels.RecordTab;
import butvan.agent.network.record.model.RecordModels.RecordReference;
import butvan.agent.network.record.model.RecordModels.RecordReferencePage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 记录模块 HTTP 请求与响应对象。 */
public final class RecordDtos {
    private RecordDtos() {
    }

    /** 新建或编辑记录请求。 */
    public record SaveRecordRequest(LocalDate recordDate, String type, String title,
                                    String contentHtml, String contentText, List<String> tags, String tabId) {
    }

    /** 置顶、收藏和归档状态修改请求；空字段表示保持原值。 */
    public record UpdateFlagsRequest(Boolean pinned, Boolean favorite, Boolean archived, int expectedVersion) {
    }

    /** 记录响应。 */
    public record RecordResponse(String id, LocalDate recordDate, String type, String title,
                                 String contentHtml, String contentText, List<String> tags,
                                 String tabId, String source, String sourceReference,
                                 boolean pinned, boolean favorite, boolean archived, Instant trashedAt,
                                 Integer weekYear, Integer weekNumber, int version,
                                 Instant createdAt, Instant updatedAt) {
    }

    /** 日历单日摘要。 */
    public record DaySummaryResponse(LocalDate date, int count, boolean weeklyReviewCompleted, long charCount) {
    }

    /** 资料引用选择器响应，不暴露完整正文。 */
    public record ReferenceResponse(String id, LocalDate recordDate, String type, String title,
                                    String summary, List<String> tags, Instant updatedAt) { }

    public record ReferencePageResponse(List<ReferenceResponse> items, boolean hasMore, int nextOffset) { }

    /** 附件响应；下载地址由记录 ID 与附件 ID 稳定定位。 */
    public record AttachmentResponse(String id, String recordId, String originalName,
                                     String mediaType, long sizeBytes, Instant createdAt) { }

    public static RecordResponse from(RecordEntry entry) {
        return new RecordResponse(entry.id(), entry.recordDate(), entry.type().value(), entry.title(),
                entry.contentHtml(), entry.contentText(), entry.tags(), entry.tabId(), entry.source(), entry.sourceReference(),
                entry.pinned(), entry.favorite(),
                entry.archived(), entry.trashedAt(), entry.weekYear(), entry.weekNumber(), entry.version(),
                entry.createdAt(), entry.updatedAt());
    }

    public static DaySummaryResponse from(DaySummary summary) {
        return new DaySummaryResponse(summary.date(), summary.count(), summary.weeklyReviewCompleted(), summary.charCount());
    }

    public static ReferenceResponse from(RecordReference reference) {
        return new ReferenceResponse(reference.id(), reference.recordDate(), reference.type().value(),
                reference.title(), reference.summary(), reference.tags(), reference.updatedAt());
    }

    public static ReferencePageResponse from(RecordReferencePage page) {
        return new ReferencePageResponse(page.items().stream().map(RecordDtos::from).toList(),
                page.hasMore(), page.nextOffset());
    }

    public static AttachmentResponse from(RecordAttachment attachment) {
        return new AttachmentResponse(attachment.id(), attachment.recordId(), attachment.originalName(),
                attachment.mediaType(), attachment.sizeBytes(), attachment.createdAt());
    }

    public record CreateTabRequest(String name) { }
    /** Tab 排序请求；必须包含当前用户的全部 Tab ID，顺序即展示顺序。 */
    public record ReorderTabsRequest(List<String> tabIds) { }
    public record TabResponse(String id, String name, String systemKey, int sortOrder) { }
    public static TabResponse from(RecordTab tab) { return new TabResponse(tab.id(), tab.name(), tab.systemKey(), tab.sortOrder()); }
}
