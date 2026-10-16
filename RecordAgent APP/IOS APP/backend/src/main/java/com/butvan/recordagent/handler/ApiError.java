package com.butvan.recordagent.handler;

import com.butvan.recordagent.filter.RequestIdFilter;

/** 面向客户端的稳定错误结构；不包含异常栈或敏感请求内容。 */
public record ApiError(String code, String message, String requestId) {
    /** 构造与当前 HTTP 请求关联的错误。 */
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, RequestIdFilter.currentRequestId());
    }
}
