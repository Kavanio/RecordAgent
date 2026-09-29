package com.butvan.recordagent.account.application;

import com.butvan.recordagent.account.api.AuthDtos;
import com.butvan.recordagent.account.persistence.AccountStore;
import com.butvan.recordagent.platform.http.ApiErrorCode;
import com.butvan.recordagent.platform.http.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 邮箱身份、验证码和可撤销的随机令牌会话。 */
@Service
@RequiredArgsConstructor
public class AccountService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;
    private final AccountStore store;
    private final PasswordEncoder encoder;
    private final VerificationMailPort mail;
    private final TransactionTemplate transactions;

    /** 不向日志或响应泄露验证码；未配置 SMTP 时明确拒绝。 */
    public AuthDtos.CodeResponse sendCode(AuthDtos.CodeRequest request) {
        String email = normalize(request.email());
        String purpose = request.purpose();
        if (!purpose.equals("register") && !purpose.equals("login") && !purpose.equals("reset_password")) {
            throw error(ApiErrorCode.INVALID_ARGUMENT);
        }
        if (store.lastChallengeAt(email, purpose).filter(time -> time.isAfter(now().minusMinutes(1))).isPresent()) {
            throw error(ApiErrorCode.RATE_LIMITED);
        }
        byte[] digits = new byte[4];
        RANDOM.nextBytes(digits);
        int number = (int) (Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(digits).getInt()) % 1_000_000);
        String code = "%06d".formatted(number);
        mail.send(email, code);
        store.createChallenge(UUID.randomUUID(), email, purpose, encoder.encode(code), now().plusMinutes(10));
        return new AuthDtos.CodeResponse(60);
    }

    /** 邮箱验证后创建账户；重复邮箱或昵称由数据库约束兜底。 */
    public AuthDtos.Session register(AuthDtos.RegisterRequest request) {
        String email = normalize(request.email());
        AuthDtos.Session result = transactions.execute(status -> {
            if (!verify(email, "register", request.code())) return null;
            if (store.findByEmail(email).isPresent()) throw error(ApiErrorCode.VERSION_CONFLICT);
            UUID id = UUID.randomUUID();
            String nickname = "用户" + id.toString().substring(0, 8);
            try {
                store.createUser(id, email, encoder.encode(request.password()), nickname);
            } catch (DuplicateKeyException exception) {
                throw error(ApiErrorCode.VERSION_CONFLICT);
            }
            return issue(store.user(id).orElseThrow());
        });
        if (result == null) throw error(ApiErrorCode.INVALID_ARGUMENT);
        return result;
    }

    /** 密码登录统一返回泛化错误，避免探测邮箱是否存在。 */
    public AuthDtos.Session passwordLogin(AuthDtos.PasswordLoginRequest request) {
        var account = store.findByEmail(normalize(request.email()));
        if (account.isEmpty() || !encoder.matches(request.password(), account.get().passwordHash())) {
            throw error(ApiErrorCode.UNAUTHENTICATED);
        }
        return issue(account.get().user());
    }

    /** 验证码只消费一次。 */
    public AuthDtos.Session codeLogin(AuthDtos.CodeLoginRequest request) {
        String email = normalize(request.email());
        AuthDtos.Session result = transactions.execute(status -> {
            if (!verify(email, "login", request.code())) return null;
            return store.findByEmail(email).map(row -> issue(row.user())).orElse(null);
        });
        if (result == null) throw error(ApiErrorCode.UNAUTHENTICATED);
        return result;
    }

    /** 轮换刷新令牌并撤销旧会话。 */
    public AuthDtos.Session refresh(String refreshToken) {
        return transactions.execute(status -> {
            var session = store.refreshSession(hash(refreshToken)).orElseThrow(() -> error(ApiErrorCode.UNAUTHENTICATED));
            var user = store.user(session.userId()).orElseThrow(() -> error(ApiErrorCode.UNAUTHENTICATED));
            store.revokeSession(session.id());
            return issue(user);
        });
    }

    public void logout(UUID sessionId) { store.revokeSession(sessionId); }
    public AuthDtos.User user(UUID userId) {
        return store.user(userId).orElseThrow(() -> error(ApiErrorCode.UNAUTHENTICATED));
    }
    public java.util.Optional<AccountStore.SessionRow> accessSession(String token) {
        return store.accessSession(hash(token));
    }

    private boolean verify(String email, String purpose, String code) {
        var challenge = store.latestChallenge(email, purpose);
        if (challenge.isEmpty()) return false;
        var row = challenge.get();
        if (row.expiresAt().isBefore(now()) || row.attempts() >= MAX_ATTEMPTS) return false;
        if (!encoder.matches(code, row.hash())) {
            store.failedChallenge(row.id());
            return false;
        }
        store.consumeChallenge(row.id());
        return true;
    }

    private AuthDtos.Session issue(AuthDtos.User user) {
        String access = token();
        String refresh = token();
        OffsetDateTime expires = now().plusMinutes(15);
        store.createSession(UUID.randomUUID(), user.id(), hash(access), hash(refresh), expires, now().plusDays(30));
        return new AuthDtos.Session(access, refresh, expires, user);
    }

    private static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
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
    private static ApiException error(ApiErrorCode code) { return new ApiException(code, code.name()); }
}
