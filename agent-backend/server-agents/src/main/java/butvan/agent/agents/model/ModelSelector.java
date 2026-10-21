package butvan.agent.agents.model;

/** 运行时模型配置；连接身份在持久化 Provider 中维护，协议与地址交由统一工厂适配。 */
public record ModelSelector(
        String vendor,
        String name,
        String apiKey,
        Double temperature,
        Boolean stream,
        String baseUrl,
        String protocol) {

    /** 兼容已有内置供应商调用，未指定地址时沿用 SDK 默认端点。 */
    public ModelSelector(String vendor, String name, String apiKey, Double temperature, Boolean stream) {
        this(vendor, name, apiKey, temperature, stream, null, null);
    }

    public static ModelSelector of(String vendor, String name, String apiKey) {
        return new ModelSelector(vendor, name, apiKey, 0.7, true);
    }
}
