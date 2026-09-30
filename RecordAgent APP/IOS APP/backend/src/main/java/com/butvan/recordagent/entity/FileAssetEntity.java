package com.butvan.recordagent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 文件领域的 FileAsset 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record FileAssetEntity(
        UUID id,
        UUID ownerId,
        String originalName,
        String mediaType,
        Long sizeBytes,
        String sha256,
        String backendType,
        String storageKey,
        String status,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt) {}
