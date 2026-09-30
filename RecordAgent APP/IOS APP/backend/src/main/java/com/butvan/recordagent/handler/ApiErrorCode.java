package com.butvan.recordagent.handler;

import org.springframework.http.HttpStatus;

/** iOS 可按稳定代码本地化的通用 HTTP 错误。 */
public enum ApiErrorCode {
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "请求参数无效"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "请先登录"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权访问"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "资源已更新，请刷新后重试"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "服务暂不可用"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容格式不支持"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "响应内容格式不支持"),
    RESULT_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "记录数量超过读取上限，请使用分页接口"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务处理失败");

    private final HttpStatus status;
    private final String message;

    ApiErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /** 与错误码绑定的 HTTP 状态。 */
    public HttpStatus status() {
        return status;
    }

    /** 客户端稳定错误代码。 */
    public String code() {
        return name();
    }

    /** 不含内部实现细节的中文默认文案。 */
    public String message() {
        return message;
    }

    /** 构造当前请求的错误响应体。 */
    public ApiError body() {
        return ApiError.of(code(), message);
    }
}
