package butvan.agent.network.material.model;

import java.time.Instant;

/** 对话档案（素材）元数据实体，绑定唯一文件 ID 与多级分类归档信息。 */
public record ChatMaterial(
        String id,
        String ownerId,
        String fileId,
        String channel,
        String sessionKind,
        String sessionId,
        String projectId,
        String sessionTitleSnapshot,
        String turnId,
        String category,
        String subType,
        String originalName,
        String mediaType,
        long sizeBytes,
        String sha256,
        String archivePath,
        String createdDate,
        Instant createdAt,
        Instant deletedAt
) {
}
