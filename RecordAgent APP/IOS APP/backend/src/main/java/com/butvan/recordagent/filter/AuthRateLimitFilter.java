package com.butvan.recordagent.filter;

import com.butvan.recordagent.config.BusinessProperties;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.service.auth.AuthenticationRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 认证入口来源限流；仅信任 Servlet 客户端 IP，反向代理转发信任由部署层另行配置。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+1)
@RequiredArgsConstructor
@Slf4j
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private final AuthenticationRateLimiter limiter;
    private final BusinessProperties properties;
    private final ObjectMapper mapper;

    /** 非认证写入入口直接交给安全链；限流和数据库错误均返回统一安全错误体。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        if (!"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/v1/auth/")) {
            chain.doFilter(request,response);
            return;
        }
        ApiErrorCode error=null;
        try {
            if (!limiter.consume("auth_ip",request.getRemoteAddr(),properties.getAuth().getRequestWindowSeconds(),properties.getAuth().getRequestAttempts()))
                error=ApiErrorCode.RATE_LIMITED;
        } catch (DataAccessException failure) {
            log.error("Authentication limiter unavailable: requestId={} type={}",RequestIdFilter.currentRequestId(),failure.getClass().getSimpleName());
            error=ApiErrorCode.SERVICE_UNAVAILABLE;
        }
        if (error==null) { chain.doFilter(request,response); return; }
        response.setStatus(error.status().value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (error==ApiErrorCode.RATE_LIMITED) response.setHeader("Retry-After",Integer.toString(properties.getAuth().getRequestWindowSeconds()));
        mapper.writeValue(response.getWriter(),error.body());
    }
}
