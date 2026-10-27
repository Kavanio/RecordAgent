package butvan.agent.agents.model;

/** 运行时模型配置；连接身份在持久化 Provider 中维护，协议与地址交由统一工厂适配。 */
public record ModelSelector(
        String vendor,
        String name,
        String apiKey,
        Double temperature,
        Boolean stream,
        String baseUrl,
        String protocol,
        Boolean supportsVision) {

    /** 兼容已有内置供应商调用，未指定地址时沿用 SDK 默认端点。 */
    public ModelSelector(String vendor, String name, String apiKey, Double temperature, Boolean stream) {
        this(vendor, name, apiKey, temperature, stream, null, null, false);
    }

    /** 兼容只关心地址与协议的调用，未声明图片识别能力。 */
    public ModelSelector(String vendor, String name, String apiKey, Double temperature, Boolean stream,
                         String baseUrl, String protocol) {
        this(vendor, name, apiKey, temperature, stream, baseUrl, protocol, false);
    }

    /**
     * 当前模型是否被用户标注为具备图片识别能力。
     *
     * <p>该标记只表达能力声明，不改变普通文本请求；渠道侧据此决定是否接收图片，
     * 未标注时一律按纯文本模型处理，避免把图片发给不支持视觉的模型。</p>
     *
     * @return 是否支持图片识别
     */
    public boolean visionCapable() {
        return Boolean.TRUE.equals(supportsVision);
    }

    public static ModelSelector of(String vendor, String name, String apiKey) {
        return new ModelSelector(vendor, name, apiKey, 0.7, true);
    }
}
