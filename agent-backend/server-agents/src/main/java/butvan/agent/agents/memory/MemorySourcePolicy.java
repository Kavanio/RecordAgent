package butvan.agent.agents.memory;

import butvan.agent.agents.agent.AgentUserCall;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 首期普通用户文本筛选；是保守范围门槛，不宣称识别所有代码或敏感信息。 */
public final class MemorySourcePolicy {
    public static final int MAX_CHARACTERS = 4000;
    private static final Pattern EXCLUDED = Pattern.compile(
            "(?i)(```|api[_ -]?key|access[_ -]?token|password|passwd|secret|密码|密钥|令牌|身份证|银行卡|账户余额|病历)"
                    + "|(?m)^\\s*(package |import |public class |function |const |def |class )");
    /** 本轮明确要求不记住的表述；只拒绝本轮上传，不代表历史来源已删除。 */
    private static final Pattern OPT_OUT = Pattern.compile(
            "(不要|别|不用|不需|无需)(记住|记住这件事|保存|记录|存下来)|忘掉这|别记这");
    private MemorySourcePolicy() { }

    /** 上传前的确定性结论；ELIGIBLE 以外都只保留无正文的原因计数。 */
    public enum Decision {
        ELIGIBLE(null), OPT_OUT("USER_OPT_OUT"), SENSITIVE_OR_CODE("SENSITIVE_OR_CODE"),
        TOO_LONG("TOO_LONG"), EMPTY("EMPTY"), UNTRUSTED_CONTEXT("UNTRUSTED_CONTEXT");
        private final String reason;
        Decision(String reason) { this.reason = reason; }
        /** 用于运行状态聚合的稳定原因码；合格来源返回 null。 */
        public String reason() { return reason; }
    }

    /** 原文必须独立存在，不能从模型 context、附件占位或资料中补出用户事实。 */
    public static boolean eligible(AgentUserCall request) {
        return decide(request) == Decision.ELIGIBLE;
    }
    /** 外发前复核文本仍满足首期范围；过长或无法确定的内容直接跳过。 */
    public static boolean eligibleText(String text) {
        return decideText(text) == Decision.ELIGIBLE;
    }
    /** 先判断来源是否可信，再判断正文本身；不读取模型展开内容。 */
    public static Decision decide(AgentUserCall request) {
        if (request == null || request.memoryChannel() == null) return Decision.UNTRUSTED_CONTEXT;
        if (!request.images().isEmpty() || !request.ragContexts().isEmpty()) return Decision.UNTRUSTED_CONTEXT;
        String content = request.content() == null ? "" : request.content().strip();
        String context = request.context();
        if (context != null && !context.isBlank() && !context.strip().equals(content)) return Decision.UNTRUSTED_CONTEXT;
        return decideText(request.content());
    }
    /** 排除顺序固定：空文本、明确不记住、过长、命令与敏感内容，最后才允许上传。 */
    public static Decision decideText(String text) {
        if (text == null || text.isBlank()) return Decision.EMPTY;
        if (OPT_OUT.matcher(text).find()) return Decision.OPT_OUT;
        if (text.length() > MAX_CHARACTERS) return Decision.TOO_LONG;
        if (text.stripLeading().startsWith("/")) return Decision.SENSITIVE_OR_CODE;
        if (EXCLUDED.matcher(text).find()) return Decision.SENSITIVE_OR_CODE;
        return Decision.ELIGIBLE;
    }
    /** 与 transcript 用户原文一致的摘要；不摘要模型展开内容。 */
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("系统缺少摘要算法"); }
    }
}
