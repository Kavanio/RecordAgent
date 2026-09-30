package com.butvan.recordagent.service.auth.impl;

import com.butvan.recordagent.dto.auth.AuthDtos;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.auth.AuthRepository;
import com.butvan.recordagent.service.auth.AuthService;
import com.butvan.recordagent.service.mail.MailService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 邮箱身份、验证码和可撤销的随机令牌会话实现。 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final com.butvan.recordagent.config.BusinessProperties properties;
    private final AuthRepository store;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final TransactionTemplate transactions;

    @Override
    public AuthDtos.CodeResponse sendCode(AuthDtos.CodeRequest request) {
        String email = normalize(request.email());
        String purpose = request.purpose();
        if (!"register".equals(purpose) && !"login".equals(purpose)
                && !"reset_password".equals(purpose)) {
            throw error(ApiErrorCode.INVALID_ARGUMENT);
        }
        return transactions.execute(status -> {
            // 邮件发送与数据库登记保持在同一临界区，避免并发请求同时通过 60 秒频率检查。
            // SMTP 调用期间事务仍占用连接；后续改异步发送时需由持久化队列维持同等顺序语义。
            store.lockChallengeRequest(email, purpose);
            if (store.lastChallengeAt(email, purpose)
                    .filter(time -> time.isAfter(now().minusSeconds(properties.getAuth().getCodeRetrySeconds()))).isPresent()) {
                throw error(ApiErrorCode.RATE_LIMITED);
            }
            String code = generateCode();
            mail.send(email, code);
            store.createChallenge(UUID.randomUUID(), email, purpose, encoder.encode(code),
                    now().plusMinutes(properties.getAuth().getCodeValidityMinutes()));
            return new AuthDtos.CodeResponse(properties.getAuth().getCodeRetrySeconds());
        });
    }

    @Override
    public AuthDtos.Session register(AuthDtos.RegisterRequest request) {
        String email = normalize(request.email());
        AuthDtos.Session result = transactions.execute(status -> {
            if (!verify(email, "register", request.code())) {
                return null;
            }
            if (store.findByEmail(email).isPresent()) {
                throw error(ApiErrorCode.VERSION_CONFLICT);
            }
            UUID id = UUID.randomUUID();
            String nickname = "用户" + id.toString().substring(0, 8);
            try {
                store.createUser(id, email, encoder.encode(request.password()), nickname);
            } catch (DuplicateKeyException exception) {
                throw error(ApiErrorCode.VERSION_CONFLICT);
            }
            return issue(store.user(id).orElseThrow());
        });
        if (result == null) {
            throw error(ApiErrorCode.INVALID_ARGUMENT);
        }
        return result;
    }

    @Override
    public AuthDtos.Session passwordLogin(AuthDtos.PasswordLoginRequest request) {
        var account = store.findByEmail(normalize(request.email()));
        if (account.isEmpty() || !encoder.matches(request.password(), account.get().passwordHash())) {
            throw error(ApiErrorCode.UNAUTHENTICATED);
        }
        return issue(account.get().user());
    }

    @Override
    public AuthDtos.Session codeLogin(AuthDtos.CodeLoginRequest request) {
        String email = normalize(request.email());
        AuthDtos.Session result = transactions.execute(status -> {
            if (!verify(email, "login", request.code())) {
                return null;
            }
            return store.findByEmail(email).map(row -> issue(row.user())).orElse(null);
        });
        if (result == null) {
            throw error(ApiErrorCode.UNAUTHENTICATED);
        }
        return result;
    }

    @Override
    public AuthDtos.Session refresh(String refreshToken) {
        return transactions.execute(status -> {
            var session = store.refreshSession(hash(refreshToken)).orElseThrow(() -> error(ApiErrorCode.UNAUTHENTICATED));
            var user = store.user(session.userId()).orElseThrow(() -> error(ApiErrorCode.UNAUTHENTICATED));
            store.revokeSession(session.id());
            return issue(user);
        });
    }

    @Override
    public void resetPassword(AuthDtos.ResetPasswordRequest request) {
        String email = normalize(request.email());
        Boolean changed = transactions.execute(status -> {
            if (!verify(email, "reset_password", request.code())) {
                return false;
            }
            var account = store.findByEmail(email).orElse(null);
            if (account == null) {
                return false;
            }
            store.changePassword(account.id(), encoder.encode(request.newPassword()));
            return true;
        });
        if (!Boolean.TRUE.equals(changed)) {
            throw error(ApiErrorCode.UNAUTHENTICATED);
        }
    }

    @Override
    public void logout(UUID sessionId) {
        store.revokeSession(sessionId);
    }

    @Override
    public AuthDtos.User user(UUID userId) {
        return store.user(userId).orElseThrow(() -> error(ApiErrorCode.UNAUTHENTICATED));
    }

    @Override
    public Optional<VerifiedSession> accessSession(String token) {
        return store.accessSession(hash(token)).map(row -> new VerifiedSession(row.id(), row.userId()));
    }

    private boolean verify(String email, String purpose, String code) {
        var challenge = store.latestChallenge(email, purpose);
        if (challenge.isEmpty()) {
            return false;
        }
        var row = challenge.get();
        if (!row.expiresAt().isAfter(now()) || row.attempts() >= properties.getAuth().getMaxCodeAttempts()) {
            return false;
        }
        if (!encoder.matches(code, row.hash())) {
            // 校验失败时仍提交尝试次数，达到上限后旧验证码不可再用。
            store.failedChallenge(row.id());
            return false;
        }
        store.consumeChallenge(row.id());
        return true;
    }

    private AuthDtos.Session issue(AuthDtos.User user) {
        String access = token();
        String refresh = token();
        OffsetDateTime expires = now().plusMinutes(properties.getAuth().getAccessTokenMinutes());
        store.createSession(UUID.randomUUID(), user.id(), hash(access), hash(refresh), expires,
                now().plusDays(properties.getAuth().getRefreshTokenDays()));
        return new AuthDtos.Session(access, refresh, expires, user);
    }

    private static String generateCode() {
        byte[] digits = new byte[4];
        RANDOM.nextBytes(digits);
        int number = (int) (Integer.toUnsignedLong(ByteBuffer.wrap(digits).getInt()) % 1_000_000);
        return "%06d".formatted(number);
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
    private static ApiException error(ApiErrorCode code) {
        return new ApiException(code, code.name());
    }
}
