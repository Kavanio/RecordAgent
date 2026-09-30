package com.butvan.recordagent.security;

import com.butvan.recordagent.filter.RequestIdFilter;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.HttpErrorWriter;
import com.butvan.recordagent.service.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 只接受服务端会话表中未过期、未撤销的 Bearer 凭据。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BearerAuthenticationFilter extends OncePerRequestFilter {
    private final AuthService accounts;
    private final HttpErrorWriter errors;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && header.length() <= 256) {
            String token = header.substring(7);
            try {
                accounts.accessSession(token)
                        .ifPresent(
                                session -> {
                                    var principal =
                                            new AuthenticatedUser(session.userId(), session.id());
                                    var authentication =
                                            new UsernamePasswordAuthenticationToken(
                                                    principal, null, List.of());
                                    SecurityContextHolder.getContext()
                                            .setAuthentication(authentication);
                                });
            } catch (DataAccessException failure) {
                log.error(
                        "Bearer verification failed: requestId={} type={}",
                        RequestIdFilter.currentRequestId(),
                        failure.getClass().getSimpleName());
                var code =
                        failure instanceof DataAccessResourceFailureException
                                        || failure instanceof QueryTimeoutException
                                ? ApiErrorCode.SERVICE_UNAVAILABLE
                                : ApiErrorCode.INTERNAL_ERROR;
                errors.write(response, code);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
