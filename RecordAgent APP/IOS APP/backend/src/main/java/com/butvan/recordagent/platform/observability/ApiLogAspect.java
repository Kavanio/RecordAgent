package com.butvan.recordagent.platform.observability;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** 仅记录安全元数据；请求参数和响应正文可能含凭据或业务隐私，不写入日志。 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class ApiLogAspect {
    private final HttpServletRequest request;

    /** 使用毫秒耗时记录接口开始、完成和失败。 */
    @Around("@annotation(apiLog)")
    public Object logApi(ProceedingJoinPoint joinPoint, ApiLog apiLog) throws Throwable {
        String requestId = RequestIdFilter.currentRequestId();
        String endpoint = request.getMethod() + " " + joinPoint.getSignature().toShortString();
        long started = System.nanoTime();
        log.info("[API-LOG] START RequestId={} Description={} Endpoint={} ClientIp={} Params=[redacted]",
                requestId, apiLog.value(), endpoint, request.getRemoteAddr());
        try {
            Object result = joinPoint.proceed();
            log.info("[API-LOG] END RequestId={} Description={} Status=SUCCESS Cost={}ms",
                    requestId, apiLog.value(), (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (Throwable error) {
            log.warn("[API-LOG] ERROR RequestId={} Description={} Status=ERROR Cost={}ms ErrorType={}",
                    requestId, apiLog.value(), (System.nanoTime() - started) / 1_000_000,
                    error.getClass().getSimpleName());
            throw error;
        }
    }
}
