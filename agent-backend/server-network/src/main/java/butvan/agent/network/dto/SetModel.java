package butvan.agent.network.dto;

/** 模型设置及连接测试请求；baseUrl 为可选基础地址，custom 必须指定 protocol。 */
public record SetModel(
        String modelName,
        String vendor,
        String apiKey,
        String baseUrl,
        String protocol
) {
    /** 兼容内置供应商的原有调用。 */
    public SetModel(String modelName, String vendor, String apiKey) {
        this(modelName, vendor, apiKey, null, null);
    }
}
