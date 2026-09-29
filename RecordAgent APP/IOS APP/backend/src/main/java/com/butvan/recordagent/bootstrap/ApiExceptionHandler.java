package com.butvan.recordagent.bootstrap;

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
            HttpMessageNotReadableException.class, MaxUploadSizeExceededException.class, IllegalArgumentException.class})
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

    /** 数据库不可用时返回可识别的临时故障。 */
    @ExceptionHandler(DataAccessException.class)
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
