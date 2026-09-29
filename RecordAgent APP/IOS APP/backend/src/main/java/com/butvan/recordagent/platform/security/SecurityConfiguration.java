package com.butvan.recordagent.platform.security;

import com.butvan.recordagent.platform.http.ApiErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import jakarta.servlet.http.HttpServletResponse;

/** 在账户认证落地前关闭全部业务入口，仅公开健康状态。 */
@Configuration
public class SecurityConfiguration {

    /** 默认拒绝业务请求，防止新增 Controller 意外公开。 */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, error) ->
                                writeError(response, HttpStatus.UNAUTHORIZED, ApiErrorCode.UNAUTHENTICATED, objectMapper))
                        .accessDeniedHandler((request, response, error) ->
                                writeError(response, HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, objectMapper)))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    private void writeError(HttpServletResponse response, HttpStatus status, ApiErrorCode code,
            ObjectMapper objectMapper) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), code.body());
    }
}
