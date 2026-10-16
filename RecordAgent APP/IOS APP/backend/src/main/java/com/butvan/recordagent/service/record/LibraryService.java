package com.butvan.recordagent.service.record;

import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.dto.record.LibraryDtos;
import java.util.UUID;

/** 原生资料工作台的本人查询、写作和回收站边界。 */
public interface LibraryService {
    /** 分页按分类、收藏、回收站与关键词筛选，正文不随列表加载。 */
    Page<LibraryDtos.Item> page(
            UUID owner,
            String type,
            boolean favorite,
            boolean trash,
            String query,
            int offset,
            int limit);

    /** 越权与不存在均返回 404。 */
    LibraryDtos.Document document(UUID owner, UUID id);

    /** 客户端 UUID 对同一未确认草稿提供幂等重试。 */
    LibraryDtos.Document create(UUID owner, LibraryDtos.Save draft);

    /** 更新日期/正文/类型；手记立即用于日历投影，版本冲突返回 409。 */
    LibraryDtos.Document update(UUID owner, UUID id, LibraryDtos.Save draft);

    /** 收藏、移入回收站和恢复，均校验版本和所有权。 */
    LibraryDtos.Document flags(UUID owner, UUID id, LibraryDtos.Flags draft);
}
