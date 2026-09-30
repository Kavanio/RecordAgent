package com.butvan.recordagent.entity;

import java.util.UUID;

/** 资料领域的 RecordEntryTag 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record RecordEntryTagEntity(UUID ownerId, UUID recordId, UUID tagId) {}
