package butvan.agent.network.service.impl;

import butvan.agent.agents.config.LocalConfigService;
import butvan.agent.agents.model.ModelEndpoint;
import butvan.agent.agents.model.ModelFactory;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.model.ModelSelector;
import butvan.agent.agents.usage.ModelIdentity;
import butvan.agent.agents.usage.SystemUsageLedger;
import butvan.agent.agents.usage.UsagePurpose;
import butvan.agent.network.dto.ModelConnectionResult;
import butvan.agent.network.dto.SetModel;
import butvan.agent.network.properties.ModelVendorProperties;
import butvan.agent.network.service.ModelService;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 模型网络服务接口实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelServiceImpl implements ModelService {

    /**
     * 本地配置持久化服务
     */
    private final LocalConfigService localConfigService;

    /**
     * 内存模型持有者组件
     */
    private final ModelHolder modelHolder;

    /**
     * 模型供应商属性配置
     */
    private final ModelVendorProperties modelVendorProperties;

    /** 连接测试与聊天轮次分开记账。 */
    private final SystemUsageLedger systemUsageLedger;

    /**
     * 动态更新并保存模型配置
     *
     * @param model 模型设置对象
     */
    @Override
    public void updateModelConfig(SetModel model) {
        if (model == null) {
            throw new IllegalArgumentException("设置模型配置参数 SetModel 不能为 null");
        }

        ModelSelector currentSelector = modelHolder.getCurrentSelector();
        Double temperature = currentSelector != null ? currentSelector.temperature() : 0.7;
        Boolean stream = currentSelector != null ? currentSelector.stream() : true;

        ModelSelector newSelector = new ModelSelector(
                model.vendor(),
                model.modelName(),
                model.apiKey(),
                temperature,
                stream,
                model.baseUrl(),
                model.protocol()
        );
        validate(newSelector);

        // 1. 持久化到 ~/.butvan-agent/config.json
        localConfigService.saveConfig(newSelector);

        // 2. 刷新内存模型
        modelHolder.updateModel(newSelector);

        log.info("成功动态更新模型配置: vendor={}, modelName={}", model.vendor(), model.modelName());
    }

    /** 连接测试会产生少量供应商用量；禁用流式，最多等待 20 秒且不修改当前模型。 */
    @Override
    public ModelConnectionResult testConnection(SetModel request) {
        ModelSelector selector = new ModelSelector(request.vendor(), request.modelName(), request.apiKey(),
                0.7, false, request.baseUrl(), request.protocol());
        validate(selector);
        ChatResponse usageResponse = null;
        try {
            var responses = ModelFactory.create(selector).stream(
                            List.of(UserMessage.builder().textContent("Reply OK").build()), List.of(),
                            GenerateOptions.builder().stream(false).maxTokens(16).build())
                    .collectList().block(Duration.ofSeconds(20));
            if (responses == null || responses.isEmpty()) {
                return new ModelConnectionResult(false, "模型未返回有效响应，请检查模型 ID 和协议");
            }
            usageResponse = responses.stream().filter(r -> r.getUsage() != null)
                    .reduce((first, last) -> last).orElse(null);
            return new ModelConnectionResult(true, "连接成功，模型请求已通过");
        } catch (RuntimeException exception) {
            // 供应商异常可能包含请求 URL、密钥或响应正文，只记录类型。
            log.warn("模型连接测试失败: errorType={}", exception.getClass().getSimpleName());
            return new ModelConnectionResult(false, "模型请求失败或超时，请检查 API 地址、密钥、模型 ID 和请求格式");
        } finally {
            try {
                systemUsageLedger.append(null, UsagePurpose.MODEL_CONNECTION_TEST,
                        usageResponse == null ? null : usageResponse.getId(),
                        new ModelIdentity(selector.vendor(), selector.name()),
                        usageResponse == null ? null : usageResponse.getUsage());
            } catch (RuntimeException exception) {
                log.error("模型连接测试用量记账失败: errorType={}", exception.getClass().getSimpleName());
            }
        }
    }

    /** 在写盘前校验地址、协议和必要字段，避免错误配置污染重启路径。 */
    private void validate(ModelSelector selector) {
        if (selector.vendor() == null || selector.vendor().isBlank()
                || selector.name() == null || selector.name().isBlank()) {
            throw new IllegalArgumentException("请选择供应商并填写模型 ID");
        }
        ModelEndpoint.normalize(selector.baseUrl());
        if (selector.apiKey() == null || selector.apiKey().isBlank()) {
            throw new IllegalArgumentException("请填写 API Key；无鉴权的本地服务可填写任意占位值");
        }
        ModelFactory.create(selector);
    }

    /**
     * 获取当前加载的模型配置
     *
     * @return ModelSelector
     */
    @Override
    public ModelSelector getModelConfig() {
        ModelSelector selector = modelHolder.getCurrentSelector();
        if (selector == null) {
            selector = localConfigService.loadOrInitializeConfig();
        }
        return selector;
    }

    /**
     * 获取系统支持的模型供应商列表
     *
     * @return 供应商列表
     */
    @Override
    public List<String> getSupportedVendors() {
        if (modelVendorProperties != null && modelVendorProperties.getVendor() != null) {
            return modelVendorProperties.getVendor();
        }
        return Collections.emptyList();
    }

    /**
     * 获取全量多厂商模型配置数据
     *
     * @return LocalConfigService.ModelConfigData
     */
    @Override
    public LocalConfigService.ModelConfigData getFullModelConfig() {
        return localConfigService.loadFullConfigData();
    }

    /**
     * 保存全量多厂商模型配置数据并实时刷新 ModelHolder
     *
     * @param fullData 全量配置对象
     */
    @Override
    public void saveFullModelConfig(LocalConfigService.ModelConfigData fullData) {
        if (fullData == null) {
            throw new IllegalArgumentException("全量模型配置对象 fullData 不能为 null");
        }
        // 未激活的自定义连接也要校验，确保重新选择与重启后仍可使用。
        if (fullData.getProviders() != null) {
            for (var provider : fullData.getProviders()) {
                ModelEndpoint.normalize(provider.getBaseUrl());
                if ("custom".equals(provider.getType())) {
                    if (ModelEndpoint.normalize(provider.getBaseUrl()) == null) {
                        throw new IllegalArgumentException("自定义模型必须填写 API 基础地址");
                    }
                    ModelEndpoint.protocol(provider.getProtocol());
                    if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
                        throw new IllegalArgumentException("请填写 API Key；无鉴权的本地服务可填写任意占位值");
                    }
                }
            }
        }
        ModelSelector selector = fullData.toSelector();
        if (selector.vendor() != null && !selector.vendor().isBlank()) validate(selector);
        localConfigService.saveFullConfigData(fullData, false);

        // 删除最后一个模型时也清理运行态，避免继续使用已删除的连接。
        modelHolder.updateModel(selector);
        log.info("成功保存并应用全量多厂商模型配置: activeVendor={}, activeModel={}",
                fullData.getActiveVendor(), fullData.getActiveModel());
    }
}
