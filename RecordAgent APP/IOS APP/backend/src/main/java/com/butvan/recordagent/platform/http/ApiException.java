package com.butvan.recordagent.platform.http;

/** 由领域服务抛出的预期业务错误；公开响应只使用稳定错误码。 */
public class ApiException extends RuntimeException {
    private final ApiErrorCode errorCode;

    /** 保留内部上下文以供服务端诊断，客户端仅接收错误码和安全文案。 */
    public ApiException(ApiErrorCode errorCode, String internalMessage) {
        super(internalMessage);
        this.errorCode = errorCode;
    }

    /** 稳定错误码。 */
    public ApiErrorCode errorCode() {
        return errorCode;
    }
}
