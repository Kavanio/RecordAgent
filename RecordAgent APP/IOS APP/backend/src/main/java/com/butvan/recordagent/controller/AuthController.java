package com.butvan.recordagent.controller;

import com.butvan.recordagent.dto.AuthDtos;

import com.butvan.recordagent.service.AccountService;
import com.butvan.recordagent.annotation.ApiLog;
import com.butvan.recordagent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 账户 HTTP 协议适配。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuthController {
    private final AccountService accounts;

    @ApiLog("发送邮箱验证码")
    @PostMapping("/auth/codes")
    public AuthDtos.CodeResponse sendCode(@Valid @RequestBody AuthDtos.CodeRequest request) {
        return accounts.sendCode(request);
    }

    @ApiLog("注册账户")
    @PostMapping("/auth/register")
    public AuthDtos.Session register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return accounts.register(request);
    }

    @ApiLog("密码登录")
    @PostMapping("/auth/password-login")
    public AuthDtos.Session passwordLogin(@Valid @RequestBody AuthDtos.PasswordLoginRequest request) {
        return accounts.passwordLogin(request);
    }

    @ApiLog("验证码登录")
    @PostMapping("/auth/code-login")
    public AuthDtos.Session codeLogin(@Valid @RequestBody AuthDtos.CodeLoginRequest request) {
        return accounts.codeLogin(request);
    }

    @ApiLog("刷新会话")
    @PostMapping("/auth/refresh")
    public AuthDtos.Session refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
        return accounts.refresh(request.refreshToken());
    }

    @ApiLog("重置账户密码")
    @PostMapping("/auth/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody AuthDtos.ResetPasswordRequest request) {
        accounts.resetPassword(request);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @ApiLog("退出当前会话")
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser user) {
        accounts.logout(user.sessionId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @ApiLog("查询当前用户")
    @GetMapping("/me")
    public AuthDtos.User me(@AuthenticationPrincipal AuthenticatedUser user) {
        return accounts.user(user.userId());
    }
}
