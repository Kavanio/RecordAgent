package butvan.agent.agents.model;

import io.agentscope.core.model.Model;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时模型持有者组件
 * 用于在内存中保存当前激活的 Model 实例和 ModelSelector 配置，并支持动态更新热刷新
 */
@Slf4j
@Component
public class ModelHolder {

    /**
     * 当前激活的 AgentScope Model 原子引用（保证多线程线程安全）
     */
    private final AtomicReference<Model> currentModel = new AtomicReference<>();

    /**
     * 当前激活的模型选择器配置属性
     */
    @Getter
    private volatile ModelSelector currentSelector;

    /**
     * 动态更新并重新创建模型实例
     *
     * @param selector 新的模型选择器配置
     */
    public void updateModel(ModelSelector selector) {
        if (selector == null) {
            throw new IllegalArgumentException("模型选择器参数 ModelSelector 不能为 null");
        }
        // 如果 vendor 或 name 为空，说明用户尚未完成初次配置，暂不触发模型实例化
        if (selector.vendor() == null || selector.vendor().isBlank() ||
            selector.name() == null || selector.name().isBlank()) {
            this.currentSelector = selector;
            this.currentModel.set(null);
            log.info("本地模型配置尚未完成初始化（包含空字段），等待用户在前端进行界面配置...");
            return;
        }

        // 使用工厂方法重新实例化 Model
        Model newModel = ModelFactory.create(selector);
        this.currentModel.set(newModel);
        this.currentSelector = selector;
        log.info("ModelHolder 成功更新激活模型: Vendor=[{}], Name=[{}]", selector.vendor(), selector.name());
    }

    /**
     * 判断当前 ModelHolder 是否已成功初始化 Model 实例
     *
     * @return 是否初始化
     */
    public boolean isInitialized() {
        return currentModel.get() != null;
    }

    /**
     * 判断当前模型是否被标注为具备图片识别能力。
     *
     * <p>未初始化或未标注时一律视为不支持：渠道必须把图片当作暂不支持的能力处理，
     * 不能先发给模型再等供应商报错。</p>
     *
     * @return 是否支持图片识别
     */
    public boolean supportsVision() {
        ModelSelector selector = currentSelector;
        return selector != null && currentModel.get() != null && selector.visionCapable();
    }

    /**
     * 获取当前激活的模型实例
     *
     * @return Model 实例
     */
    public Model getModel() {
        Model model = currentModel.get();
        if (model == null) {
            throw new IllegalStateException("ModelHolder 中的 Model 尚未完成初始化，请先在前端配置模型信息");
        }
        return model;
    }
}
