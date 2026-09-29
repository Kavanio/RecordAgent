package com.butvan.recordagent.service.time;

import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import java.time.DateTimeException;
import java.time.ZoneId;

/** 将客户端时区标识统一解析为业务计算使用的时区。 */
public final class BusinessTimeZone {
    private BusinessTimeZone() {
    }

    /**
     * 校验客户端提供的时区标识；错误响应保持稳定，不回显原始输入。
     *
     * @param value 客户端传入的时区标识
     * @return 可用于自然日边界计算的时区
     */
    public static ZoneId parse(String value) {
        try {
            return ZoneId.of(value);
        } catch (DateTimeException | NullPointerException exception) {
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid time zone");
        }
    }
}
