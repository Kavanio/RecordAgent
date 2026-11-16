package butvan.agent.network.service.account;

import butvan.agent.agents.config.LocalConfigService;
import butvan.agent.network.dto.account.AccountStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 本机账户绑定服务。
 *
 * <p>账户资料持久化到 config.json 的 account 节点；验证码只驻留内存，重启即失效。</p>
 */
@Service
@RequiredArgsConstructor
public class AccountService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int CODE_EXPIRY_SECONDS = 600;
    private static final int CODE_COOLDOWN_SECONDS = 60;
    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int HASH_BYTES = 32;

    private final LocalConfigService localConfigService;
    private final EmailVerificationSender emailVerificationSender;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, VerificationRecord> verifications = new ConcurrentHashMap<>();

    /** 获取当前设备的账户绑定状态。 */
    public AccountStatusResponse getStatus() {
        AccountConfigData account = getAccountConfig();
        boolean bound = account != null && account.isEmailVerified() && account.getEmail() != null && !account.getEmail().isBlank();
        AccountStatusResponse status = new AccountStatusResponse(bound, bound ? maskEmail(account.getEmail()) : null,
                bound && account.isEmailNotificationsEnabled(), null);
        return status;
    }

    /** 读取个人昵称与外观版本号。 */
    public synchronized AppearanceState getAppearanceState() {
        AccountConfigData account = getAccountConfig();
        if (account == null) return new AppearanceState(null, 0L);
        return new AppearanceState(account.getNickname(), account.getAppearanceVersion());
    }

    /** 更新个人昵称并递增外观版本号；乐观锁校验不通过时拒绝覆盖。 */
    public synchronized AppearanceState updateAppearance(String rawNickname, long expectedVersion) {
        LocalConfigService.ModelConfigData config = localConfigService.loadFullConfigData();
        Object rawAccount = config.getExtraFields().get("account");
        AccountConfigData account = rawAccount == null
                ? new AccountConfigData()
                : objectMapper.convertValue(rawAccount, AccountConfigData.class);
        if (expectedVersion != account.getAppearanceVersion()) {
            throw new IllegalStateException("个人资料已被其他客户端更新，请刷新后重试");
        }
        String normalized = rawNickname == null ? null : rawNickname.trim();
        if (normalized != null && normalized.length() > 32) {
            throw new IllegalArgumentException("昵称不能超过 32 个字符");
        }
        account.setNickname(normalized == null || normalized.isEmpty() ? null : normalized);
        account.setAppearanceVersion(account.getAppearanceVersion() + 1);
        config.setExtraField("account", account);
        localConfigService.saveFullConfigData(config);
        return new AppearanceState(account.getNickname(), account.getAppearanceVersion());
    }

    /** 外观昵称与版本号快照。 */
    public record AppearanceState(String nickname, long version) {}

    /** 生成并发送绑定验证码；同步保证同一邮箱的发送冷却判定不并发穿透。 */
    public synchronized void sendVerificationCode(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        VerificationRecord previous = verifications.get(email);
        if (previous != null && Instant.now().isBefore(previous.sentAt.plusSeconds(CODE_COOLDOWN_SECONDS))) {
            throw new IllegalArgumentException("验证码已发送，请稍后再试");
        }
        String code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        emailVerificationSender.send(email, code);
        verifications.put(email, new VerificationRecord(hashSecret(code), Instant.now(), 0));
    }

    /** 校验验证码并持久化账户绑定资料。 */
    public synchronized AccountStatusResponse bindEmail(String rawEmail, String password, String verificationCode) {
        String email = normalizeEmail(rawEmail);
        validatePassword(password);
        VerificationRecord record = verifications.get(email);
        if (record == null || Instant.now().isAfter(record.sentAt.plusSeconds(CODE_EXPIRY_SECONDS))) {
            throw new IllegalArgumentException("验证码不存在或已失效，请重新获取");
        }
        if (record.attempts >= MAX_VERIFY_ATTEMPTS) {
            verifications.remove(email);
            throw new IllegalArgumentException("验证码错误次数过多，请重新获取");
        }
        if (!verifySecret(verificationCode, record.codeHash)) {
            record.attempts++;
            throw new IllegalArgumentException("验证码错误");
        }

        AccountConfigData account = new AccountConfigData();
        account.setEmail(email);
        account.setPasswordHash(hashSecret(password));
        account.setEmailVerified(true);
        account.setBoundAt(Instant.now().toString());
        account.setEmailNotificationsEnabled(true);
        LocalConfigService.ModelConfigData config = localConfigService.loadFullConfigData();
        config.setExtraField("account", account);
        localConfigService.saveFullConfigData(config);
        verifications.remove(email);
        return getStatus();
    }

    private AccountConfigData getAccountConfig() {
        Object rawAccount = localConfigService.loadFullConfigData().getExtraFields().get("account");
        return rawAccount == null ? null : objectMapper.convertValue(rawAccount, AccountConfigData.class);
    }

    private String normalizeEmail(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(email).matches()) throw new IllegalArgumentException("请输入有效的邮箱地址");
        return email;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH) {
            throw new IllegalArgumentException("密码至少需要 8 位");
        }
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        String local = email.substring(0, atIndex);
        String prefix = local.substring(0, Math.min(2, local.length()));
        return prefix + "***" + email.substring(atIndex);
    }

    private String hashSecret(String secret) {
        try {
            byte[] salt = new byte[16];
            secureRandom.nextBytes(salt);
            byte[] hash = pbkdf2(secret.toCharArray(), salt);
            return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("无法安全处理账户凭据", exception);
        }
    }

    private boolean verifySecret(String secret, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] actual = Base64.getDecoder().decode(parts[3]);
            byte[] expected = pbkdf2(secret.toCharArray(), salt, iterations);
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (Exception exception) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] secret, byte[] salt) throws Exception {
        return pbkdf2(secret, salt, PBKDF2_ITERATIONS);
    }

    private byte[] pbkdf2(char[] secret, byte[] salt, int iterations) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(secret, salt, iterations, HASH_BYTES * 8)).getEncoded();
    }

    private static class VerificationRecord {
        private final String codeHash;
        private final Instant sentAt;
        private int attempts;

        private VerificationRecord(String codeHash, Instant sentAt, int attempts) {
            this.codeHash = codeHash;
            this.sentAt = sentAt;
            this.attempts = attempts;
        }
    }
}
