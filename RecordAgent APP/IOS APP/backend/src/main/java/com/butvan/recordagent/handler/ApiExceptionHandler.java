package com.butvan.recordagent.handler;

import com.butvan.recordagent.filter.RequestIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 统一 HTTP 状态和安全错误体，不把底层异常或用户输入返回客户端。 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 领域服务显式给出的预期错误。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleBusiness(ApiException exception) {
        log.warn("API business error: requestId={} code={} type={}", RequestIdFilter.currentRequestId(),
                exception.errorCode().code(), exception.getClass().getSimpleName());
        return ResponseEntity.status(exception.errorCode().status()).body(exception.errorCode().body());
    }

    /** JSON、字段校验及上传大小等客户端输入错误。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, MaxUploadSizeExceededException.class, IllegalArgumentException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    public ResponseEntity<ApiError> handleInvalidInput(Exception exception) {
        log.warn("API invalid input: requestId={} type={}", RequestIdFilter.currentRequestId(),
                exception.getClass().getSimpleName());
        return response(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_ARGUMENT);
    }

    /** 不存在的 URL 不应返回默认 HTML 或内部路径。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleMissingResource(NoResourceFoundException exception) {
        return response(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND);
    }

    /** 协议错误保持实际 HTTP 状态；不进入未预期异常的 500 分支。 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(org.springframework.web.HttpRequestMethodNotSupportedException error) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(error.getSupportedHttpMethods() == null ? new org.springframework.http.HttpMethod[0]
                        : error.getSupportedHttpMethods().toArray(org.springframework.http.HttpMethod[]::new))
                .body(ApiErrorCode.METHOD_NOT_ALLOWED.body());
    }

    /** 请求的 Content-Type 无法消费。 */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(Exception error) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /** 客户端 Accept 与接口输出不匹配。 */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleAccept(Exception error) {
        return response(HttpStatus.NOT_ACCEPTABLE, ApiErrorCode.NOT_ACCEPTABLE);
    }

    /** 唯一约束、外键及并发冲突是业务冲突，不伪装成数据库宕机。 */
    @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class,
            org.springframework.dao.ConcurrencyFailureException.class})
    public ResponseEntity<ApiError> handleConflict(Exception error) {
        log.warn("API persistence conflict: requestId={} type={}", RequestIdFilter.currentRequestId(),
                error.getClass().getSimpleName());
        return response(HttpStatus.CONFLICT, ApiErrorCode.VERSION_CONFLICT);
    }

    /** 数据库不可用时返回可识别的临时故障。 */
    @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.dao.QueryTimeoutException.class,
            org.springframework.jdbc.CannotGetJdbcConnectionException.class})
    public ResponseEntity<ApiError> handleDatabaseFailure(DataAccessException exception) {
        log.error("API database failure: requestId={} type={}", RequestIdFilter.currentRequestId(),
                exception.getClass().getSimpleName());
        return response(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.SERVICE_UNAVAILABLE);
    }

    /** 未预期异常只公开通用错误和关联 ID。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("API unexpected failure: requestId={} type={}", RequestIdFilter.currentRequestId(),
                exception.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, ApiErrorCode code) {
        return ResponseEntity.status(status).body(code.body());
    }
}
