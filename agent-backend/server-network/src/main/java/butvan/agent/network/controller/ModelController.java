package butvan.agent.network.controller;

import butvan.agent.agents.model.ModelSelector;
import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.dto.SetModel;
import butvan.agent.network.dto.ModelConnectionResult;
import butvan.agent.network.service.ModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 模型配置 REST 控制器
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/agent/model")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    /**
     * 更新保存模型配置
     *
     * @param model 模型配置参数
     * @return 状态响应
     */
    @ApiLog("更新与保存模型配置")
    @PostMapping
    public Result<String> updateModelConfig(@RequestBody SetModel model) {
        modelService.updateModelConfig(model);
        return Result.success("更新模型配置成功！");
    }

    /** 测试用户提供的模型连接；沿用模型配置接口的 API Token 鉴权边界。 */
    @ApiLog("测试模型 API 请求")
    @PostMapping("/test-connection")
    public Result<ModelConnectionResult> testConnection(@RequestBody SetModel request) {
        return Result.success(modelService.testConnection(request));
    }

    /**
     * 查询当前运行及本地保存的模型配置信息
     *
     * @return 当前 ModelSelector 配置
     */
    @ApiLog("查询当前本地模型配置")
    @GetMapping("/config")
    public Result<ModelSelector> getModelConfig() {
        ModelSelector selector = modelService.getModelConfig();
        return Result.success(selector);
    }

    /**
     * 获取系统支持的模型供应商列表（从 application-vendor.yml 中配置导出）
     *
     * @return 支持的 vendor 列表
     */
    @ApiLog("获取系统支持的模型供应商列表")
    @GetMapping("/vendors")
    public Result<List<String>> getSupportedVendors() {
        List<String> vendors = modelService.getSupportedVendors();
        return Result.success(vendors);
    }

    /**
     * 获取全量多厂商模型配置数据
     *
     * @return 全量模型配置对象
     */
    @ApiLog("获取全量多厂商模型配置")
    @GetMapping("/full-config")
    public Result<butvan.agent.agents.config.LocalConfigService.ModelConfigData> getFullModelConfig() {
        butvan.agent.agents.config.LocalConfigService.ModelConfigData fullConfig = modelService.getFullModelConfig();
        return Result.success(fullConfig);
    }

    /**
     * 保存全量多厂商模型配置数据
     *
     * @param fullData 全量配置对象
     * @return 响应消息
     */
    @ApiLog("保存全量多厂商模型配置")
    @PostMapping("/full-config")
    public Result<String> saveFullModelConfig(@RequestBody butvan.agent.agents.config.LocalConfigService.ModelConfigData fullData) {
        modelService.saveFullModelConfig(fullData);
        return Result.success("全量多厂商模型配置保存成功！");
    }
}