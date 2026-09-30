package com.butvan.recordagent.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 账户接口的请求与响应契约。 */
public final class AuthDtos {
    private AuthDtos() {}

    public record CodeRequest(@Email @NotBlank @Size(max=254) String email, @NotBlank @Size(max=16) String purpose) {}
    public record CodeResponse(long retryAfterSeconds) {}
    public record RegisterRequest(@Email @NotBlank @Size(max=254) String email, @NotBlank @jakarta.validation.constraints.Pattern(regexp="[0-9]{6}") String code,
            @NotBlank @Size(min = 8, max = 128) String password) {}
    public record PasswordLoginRequest(@Email @NotBlank @Size(max=254) String email, @NotBlank @Size(max=128) String password) {}
    public record CodeLoginRequest(@Email @NotBlank @Size(max=254) String email, @NotBlank @jakarta.validation.constraints.Pattern(regexp="[0-9]{6}") String code) {}
    public record RefreshRequest(@NotBlank @Size(max=128) String refreshToken) {}
    public record ResetPasswordRequest(@Email @NotBlank @Size(max=254) String email, @NotBlank @jakarta.validation.constraints.Pattern(regexp="[0-9]{6}") String code,
            @NotBlank @Size(min = 8, max = 128) String newPassword) {}
    public record User(UUID id, String email, String nickname, OffsetDateTime createdAt, long version) {}
    public record Session(String token, String refreshToken, OffsetDateTime expiresAt, User user) {}
}
