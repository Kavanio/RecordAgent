package com.butvan.recordagent.service.query;

import java.time.LocalDate;
import java.util.List;

/** 领域公开的轻量月历摘要；计数覆盖全部记录，标题最多三条，不包含正文。 */
public record DailySummary(LocalDate date, int count, List<String> titles, String kind) {}
