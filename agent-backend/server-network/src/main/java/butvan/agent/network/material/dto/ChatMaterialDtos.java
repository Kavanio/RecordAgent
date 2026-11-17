package butvan.agent.network.material.dto;

import butvan.agent.network.material.model.ChatMaterial;
import butvan.agent.network.material.repository.ChatMaterialRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 对话档案（素材）HTTP 响应 DTO。 */
public final class ChatMaterialDtos {

    private ChatMaterialDtos() {
    }

    public static ChatMaterialResponse from(ChatMaterial material) {
        return new ChatMaterialResponse(
                material.id(),
                material.fileId(),
                material.channel(),
                material.sessionKind(),
                material.sessionId(),
                material.projectId(),
                material.sessionTitleSnapshot(),
                material.turnId(),
                material.category(),
                material.subType(),
                material.originalName(),
                material.mediaType(),
                material.sizeBytes(),
                material.sha256(),
                material.archivePath(),
                material.createdDate(),
                material.createdAt()
        );
    }

    public static ChatMaterialPageResponse from(ChatMaterialRepository.PageResult page) {
        return new ChatMaterialPageResponse(
                page.items().stream().map(ChatMaterialDtos::from).toList(),
                page.total()
        );
    }

    public static ChatMaterialSummaryResponse from(ChatMaterialRepository.SummaryResult summary) {
        return new ChatMaterialSummaryResponse(
                summary.totalCount(),
                summary.totalBytes(),
                summary.byCategory(),
                summary.byChannel(),
                summary.bySessionKind(),
                summary.bySubType(),
                summary.sessions().stream()
                        .map(bucket -> new SessionBucketResponse(
                                bucket.sessionId(),
                                bucket.sessionTitle(),
                                bucket.channel(),
                                bucket.sessionKind(),
                                bucket.count(),
                                bucket.latestCreatedAt()
                        ))
                        .toList()
        );
    }

    public record ChatMaterialResponse(
            String id,
            String fileId,
            String channel,
            String sessionKind,
            String sessionId,
            String projectId,
            String sessionTitle,
            String turnId,
            String category,
            String subType,
            String originalName,
            String mediaType,
            long sizeBytes,
            String sha256,
            String archivePath,
            String createdDate,
            Instant createdAt
    ) {
    }

    public record ChatMaterialPageResponse(
            List<ChatMaterialResponse> items,
            long total
    ) {
    }

    public record SessionBucketResponse(
            String sessionId,
            String sessionTitle,
            String channel,
            String sessionKind,
            long count,
            Instant latestCreatedAt
    ) {
    }

    public record ChatMaterialSummaryResponse(
            long totalCount,
            long totalBytes,
            Map<String, Long> byCategory,
            Map<String, Long> byChannel,
            Map<String, Long> bySessionKind,
            Map<String, Long> bySubType,
            List<SessionBucketResponse> sessions
    ) {
    }
}
