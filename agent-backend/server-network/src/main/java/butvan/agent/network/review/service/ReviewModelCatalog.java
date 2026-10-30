package butvan.agent.network.review.service;

import butvan.agent.agents.config.LocalConfigService;
import butvan.agent.agents.model.ModelEndpoint;
import butvan.agent.agents.model.ModelFactory;
import butvan.agent.network.review.model.ReviewModels.ModelIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

/** 只读解析已存在模型连接；不使用会自动初始化配置的读取入口，不创建模型或网络请求。 */
@Service
@RequiredArgsConstructor
public class ReviewModelCatalog {
    private final LocalConfigService config;
    private final ObjectMapper mapper;

    /** 精确匹配 Provider ID 与 Model ID；指纹不包含 API Key，轮换 Key 不改变接收方身份。 */
    public ModelIdentity resolve(String providerId, String modelName) {
        if (providerId == null || providerId.isBlank() || modelName == null || modelName.isBlank())
            throw new IllegalArgumentException("请选择已保存的复盘模型连接");
        LocalConfigService.ModelConfigData data;
        try {
            var path = config.getConfigPath();
            if (!Files.isRegularFile(path) || Files.size(path) > 2_000_000)
                throw new IllegalArgumentException("模型配置不可用，请先保存模型连接");
            data = mapper.readValue(Files.readAllBytes(path), LocalConfigService.ModelConfigData.class);
        } catch (IOException exception) {
            // 不带原始异常，避免 JSON 解析诊断片段包含 Key。
            throw new IllegalArgumentException("模型配置读取失败，请检查模型设置");
        }
        if (data == null || data.getProviders() == null) throw new IllegalArgumentException("模型连接不存在");
        var provider = data.getProviders().stream().filter(p -> p != null && providerId.equals(p.getId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("模型连接不存在"));
        if (!Boolean.TRUE.equals(provider.getIsEnabled()) || provider.getModels() == null
                || provider.getModels().stream().noneMatch(m -> m != null && modelName.equals(
                m.getModelName() == null || m.getModelName().isBlank() ? m.getName() : m.getModelName())))
            throw new IllegalArgumentException("模型已关闭或未保存在所选连接中");
        String type = provider.getType() == null ? "" : provider.getType().trim().toLowerCase(Locale.ROOT);
        try { ModelFactory.fromVendor(type); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("复盘模型类型不受支持"); }
        String protocol = "custom".equals(type) ? ModelEndpoint.protocol(provider.getProtocol())
                : switch (type) { case "deepseek", "openai" -> "openai"; default -> type; };
        return new ModelIdentity(providerId, modelName, protocol, butvan.agent.agents.review.ReviewConnectionIdentity.fingerprint(providerId, modelName, type, provider.getProtocol(), provider.getBaseUrl()));
    }
}
