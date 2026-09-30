package com.butvan.recordagent.dto.query;

import java.util.List;

/** 明细分页响应；nextOffset 为空表示已结束，分页期间新增数据需从第一页刷新。 */
public record Page<T>(List<T> items, Integer nextOffset, boolean hasMore) {}
