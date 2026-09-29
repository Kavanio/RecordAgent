package com.butvan.recordagent.security;

import com.butvan.recordagent.handler.ApiErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.servlet.http.HttpServletResponse;

/** 公开认证入口，其余业务接口必须先验证 Bearer 会话。 */
@Configuration
public class SecurityConfiguration {

    /** 未匹配的业务请求仍默认要求认证。 */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
            BearerAuthenticationFilter bearerFilter) throws Exception {
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
                        .requestMatchers("/api/v1/auth/codes", "/api/v1/auth/register",
                                "/api/v1/auth/password-login", "/api/v1/auth/code-login",
                                "/api/v1/auth/refresh", "/api/v1/auth/reset-password").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** BCrypt 提供带随机盐的自适应密码与验证码摘要。 */
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    private void writeError(HttpServletResponse response, HttpStatus status, ApiErrorCode code,
            ObjectMapper objectMapper) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), code.body());
    }
}
