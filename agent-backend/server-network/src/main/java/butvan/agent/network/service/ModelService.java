package butvan.agent.network.service;

import butvan.agent.agents.config.LocalConfigService;
import butvan.agent.agents.model.ModelSelector;
import butvan.agent.network.dto.SetModel;
import butvan.agent.network.dto.ModelConnectionResult;

import java.util.List;

/**
 * 模型网络接口服务层定义
 */
public interface ModelService {

    /**
     * 更新并持久化模型配置
     *
     * @param model 模型设置对象
     */
    void updateModelConfig(SetModel model);

    /** 使用与聊天一致的模型适配器发起最小请求，不保存配置。 */
    ModelConnectionResult testConnection(SetModel model);

    /**
     * 获取当前加载的模型配置选择器
     *
     * @return ModelSelector
     */
    ModelSelector getModelConfig();

    /**
     * 获取支持的模型厂商列表
     *
     * @return 厂商列表
     */
    List<String> getSupportedVendors();

    /**
     * 获取全量多厂商模型配置数据
     *
     * @return LocalConfigService.ModelConfigData
     */
    LocalConfigService.ModelConfigData getFullModelConfig();

    /**
     * 保存全量多厂商模型配置数据
     *
     * @param fullData 全量配置对象
     */
    void saveFullModelConfig(LocalConfigService.ModelConfigData fullData);
}
