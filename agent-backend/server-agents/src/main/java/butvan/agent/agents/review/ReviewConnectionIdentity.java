package butvan.agent.agents.review;

import butvan.agent.agents.model.ModelEndpoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 授权与模型创建共用的无凭据接收方身份；同一次配置读取必须在发出正文前核对。 */
public final class ReviewConnectionIdentity {
    private ReviewConnectionIdentity() {}
    public static String fingerprint(String providerId, String modelName, String vendor, String protocolValue, String baseUrl) {
        String type = vendor == null ? "" : vendor.trim().toLowerCase(Locale.ROOT);
        String protocol = "custom".equals(type) ? ModelEndpoint.protocol(protocolValue)
                : switch (type) { case "deepseek", "openai" -> "openai"; default -> type; };
        String endpoint = ModelEndpoint.normalize(baseUrl);
        if ("custom".equals(type) && endpoint == null) throw new IllegalArgumentException("自定义模型缺少 API 地址");
        if ("anthropic".equals(protocol) && endpoint != null) endpoint = ModelEndpoint.anthropicBaseUrl(endpoint);
        String identity = providerId + "\n" + modelName + "\n" + type + "\n" + protocol + "\n"
                + (endpoint == null ? "sdk-default" : endpoint);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("系统不支持SHA-256"); }
    }
}
