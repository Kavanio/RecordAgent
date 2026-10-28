package butvan.agent.network.review.service;

import butvan.agent.network.review.model.ReviewModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 复盘范围、版本及指纹策略；不包含任何可执行模型调用。 */
public final class ReviewPolicy {
    public static final String VERSION = "report-review-v1";
    public static final Set<String> PERIODS = Set.of("DAILY_REPORT", "WEEKLY_REPORT", "MONTHLY_REPORT");
    public static final Set<String> DOMAINS = Set.of("FINANCE", "TODO", "STUDY");
    private ReviewPolicy() {}

    /** HTTP 与后台入口都必须使用明确用户，不能接受空用户。 */
    public static void requireOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 128) throw new IllegalArgumentException("用户标识无效");
    }
    /** 校验允许的枚举范围并拒绝空集合。 */
    public static Set<String> scope(Set<String> value, Set<String> allowed) {
        if (value == null || value.isEmpty() || !allowed.containsAll(value))
            throw new IllegalArgumentException("请选择有效的复盘周期和数据范围");
        return Set.copyOf(value);
    }
    /** 规范化集合供正式文本列保存，顺序不会改变授权比较。 */
    public static String encode(Set<String> values) { return String.join(",", new TreeSet<>(values)); }
    public static Set<String> decode(String value) { return Set.copyOf(Arrays.asList(value.split(","))); }

    /** 范围扩大、接收方变化或授权撤销后均不可继续使用。 */
    public static boolean covers(Grant grant, Settings settings, String currentFingerprint) {
        return grant != null && "ACTIVE".equals(grant.state()) && VERSION.equals(grant.policyVersion())
                && grant.owner().equals(settings.owner()) && grant.fingerprint().equals(currentFingerprint)
                && settings.fingerprint().equals(currentFingerprint)
                && grant.periods().containsAll(settings.periods()) && grant.domains().containsAll(settings.domains())
                && grant.historyDays() >= settings.historyDays() && grant.dailyLimit() >= settings.dailyLimit();
    }
    /** 使用 SHA-256 记录非敏感身份／事实，不将原文写入日志。 */
    public static String fingerprint(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("系统不支持 SHA-256", exception);
        }
    }
}
