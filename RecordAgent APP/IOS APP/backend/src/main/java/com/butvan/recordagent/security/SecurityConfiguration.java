package com.butvan.recordagent.security;

import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.HttpErrorWriter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** 公开认证入口，其余业务接口必须先验证 Bearer 会话。 */
@Configuration
public class SecurityConfiguration {

    /** 未匹配的业务请求仍默认要求认证。 */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, HttpErrorWriter errors, BearerAuthenticationFilter bearerFilter)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(
                                                (request, response, error) ->
                                                        errors.write(
                                                                response,
                                                                ApiErrorCode.UNAUTHENTICATED))
                                        .accessDeniedHandler(
                                                (request, response, error) ->
                                                        errors.write(
                                                                response, ApiErrorCode.FORBIDDEN)))
                .authorizeHttpRequests(
                        requests ->
                                requests.requestMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers(
                                                "/api/v1/auth/codes",
                                                "/api/v1/auth/register",
                                                "/api/v1/auth/password-login",
                                                "/api/v1/auth/code-login",
                                                "/api/v1/auth/refresh",
                                                "/api/v1/auth/reset-password")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** BCrypt 提供带随机盐的自适应密码与验证码摘要。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Bearer 过滤器只由 Spring Security 执行，避免容器独立注册导致认证边界重复。 */
    @Bean
    public FilterRegistrationBean<BearerAuthenticationFilter> bearerRegistration(
            BearerAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
