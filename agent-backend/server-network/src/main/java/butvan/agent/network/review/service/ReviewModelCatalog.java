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

    /**
     * 解析当前已启用的首个具体模型连接（返回真实固化的 Provider ID、Model ID 与指纹，不使用动态别名）。
     */
    public ModelIdentity resolveFirstEnabled() {
        try {
            var path = config.getConfigPath();
            if (Files.isRegularFile(path) && Files.size(path) <= 2_000_000) {
                var data = mapper.readValue(Files.readAllBytes(path), LocalConfigService.ModelConfigData.class);
                if (data != null && data.getProviders() != null) {
                    for (var provider : data.getProviders()) {
                        if (provider == null || !Boolean.TRUE.equals(provider.getIsEnabled())
                                || provider.getId() == null || provider.getId().isBlank()
                                || provider.getApiKey() == null || provider.getApiKey().isBlank()
                                || provider.getModels() == null || provider.getModels().isEmpty()) continue;
                        var firstModel = provider.getModels().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
                        if (firstModel == null) continue;
                        String resolvedName = firstModel.getModelName() == null || firstModel.getModelName().isBlank()
                                ? firstModel.getName() : firstModel.getModelName();
                        if (resolvedName != null && !resolvedName.isBlank()) {
                            try {
                                return resolve(provider.getId(), resolvedName);
                            } catch (IllegalArgumentException ignored) {
                                // 跳过类型不受支持的供应商，继续检查下一个已启用连接
                            }
                        }
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("模型配置读取失败，请检查模型设置");
        }
        throw new IllegalArgumentException("当前没有可用的已启用模型连接，请先在模型设置中启用并保存");
    }
}
