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
    private MemorySourcePolicy() { }

    /** 原文必须独立存在，不能从模型 context、附件占位或资料中补出用户事实。 */
    public static boolean eligible(AgentUserCall request) {
        if (request == null || request.memoryChannel() == null || !request.images().isEmpty()
                || !request.ragContexts().isEmpty() || !eligibleText(request.content())) return false;
        return request.context() == null || request.context().isBlank()
                || request.context().strip().equals(request.content().strip());
    }
    /** 外发前复核文本仍满足首期范围；过长或无法确定的内容直接跳过。 */
    public static boolean eligibleText(String text) {
        return text != null && !text.isBlank() && text.length() <= MAX_CHARACTERS
                && !text.stripLeading().startsWith("/") && !EXCLUDED.matcher(text).find();
    }
    /** 与 transcript 用户原文一致的摘要；不摘要模型展开内容。 */
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("系统缺少摘要算法"); }
    }
}
