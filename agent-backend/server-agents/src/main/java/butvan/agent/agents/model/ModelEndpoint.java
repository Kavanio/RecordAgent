package butvan.agent.agents.model;

import java.net.URI;
import java.util.Locale;

/** 自定义模型的协议与地址边界；仅接受无凭据、查询参数和片段的 HTTP(S) 基础地址。 */
public final class ModelEndpoint {
    private ModelEndpoint() {}

    /** 校验并规范化基础地址；允许本地模型服务，禁止把密钥放入 URL。 */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            String result = value.trim().replaceAll("/+$", "");
            if (result.endsWith("/chat/completions") || result.endsWith("/messages")) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("API 地址须为 HTTP(S) 基础地址，不含凭据、查询参数或 /chat/completions、/messages 后缀");
        }
    }

    /** 自定义请求仅支持 OpenAI Chat Completions 与 Anthropic Messages 协议。 */
    public static String protocol(String value) {
        String protocol = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!protocol.equals("openai") && !protocol.equals("anthropic")) {
            throw new IllegalArgumentException("请选择 OpenAI 或 Anthropic 请求格式");
        }
        return protocol;
    }

    /** Anthropic SDK 自行追加 /v1/messages，避免用户填写 /v1 后重复拼接。 */
    public static String anthropicBaseUrl(String value) {
        String baseUrl = normalize(value);
        return baseUrl != null && baseUrl.endsWith("/v1")
                ? baseUrl.substring(0, baseUrl.length() - 3) : baseUrl;
    }
}
