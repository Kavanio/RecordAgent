package com.butvan.recordagent.account.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 账户接口的请求与响应契约。 */
public final class AuthDtos {
    private AuthDtos() {}

    public record CodeRequest(@Email @NotBlank String email, @NotBlank String purpose) {}
    public record CodeResponse(long retryAfterSeconds) {}
    public record RegisterRequest(@Email @NotBlank String email, @NotBlank String code,
            @NotBlank @Size(min = 8, max = 128) String password) {}
    public record PasswordLoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    public record CodeLoginRequest(@Email @NotBlank String email, @NotBlank String code) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record User(UUID id, String email, String nickname, OffsetDateTime createdAt, long version) {}
    public record Session(String token, String refreshToken, OffsetDateTime expiresAt, User user) {}
}
