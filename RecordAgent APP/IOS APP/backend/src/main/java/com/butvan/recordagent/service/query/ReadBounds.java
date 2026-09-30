package com.butvan.recordagent.service.query;

import com.butvan.recordagent.config.BusinessProperties;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 明细查询统一读取上限加一条，检测溢出，避免把不完整结果当完整列表。 */
@Component
@RequiredArgsConstructor
public class ReadBounds {
    private final BusinessProperties properties;

    /** 当日明细上限；调用 Repository 时加一条探测溢出。 */
    public int dayLimit() {
        return properties.getReading().getDayLimit();
    }

    /** 账户列表上限。 */
    public int accountLimit() {
        return properties.getReading().getAccountLimit();
    }

    /** 仅接受完整结果，超限由客户端分页读取，绝不静默裁剪。 */
    public <T> List<T> requireComplete(List<T> rows, int limit) {
        if (rows.size() > limit)
            throw new ApiException(ApiErrorCode.RESULT_LIMIT_EXCEEDED, "read limit exceeded");
        return rows;
    }

    /** 分页参数由客户端提供，页大小最大为日明细上限。 */
    public void validatePage(int offset, int limit) {
        if (offset < 0
                || offset > Integer.MAX_VALUE - dayLimit()
                || limit < 1
                || limit > dayLimit())
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid page");
    }

    /** 用额外一条记录判断下一页，结果保持稳定的分页结构。 */
    public <T> Page<T> page(List<T> rows, int offset, int limit) {
        boolean more = rows.size() > limit;
        return new Page<>(
                List.copyOf(rows.subList(0, Math.min(rows.size(), limit))),
                more ? offset + limit : null,
                more);
    }
}
