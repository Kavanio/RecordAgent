package butvan.agent.agents.model;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 DashScope 供应商统一走百炼 OpenAI 兼容端点，并支持自定义基础地址覆盖。 */
class ModelFactoryDashScopeTest {

    @ParameterizedTest
    @ValueSource(strings = {"qwen3.7-max-2026-06-08", "deepseek-v4.1-flash-1017", "qwen-plus"})
    void usesDashScopeCompatibleModeEndpointForAllModels(String modelName) throws Exception {
        var selector = new ModelSelector("dashscope", modelName, "test-key", 0.7, true);

        var model = assertInstanceOf(OpenAIChatModel.class, ModelFactory.create(selector));

        assertEquals(modelName, model.getModelName());
        assertEquals(ModelFactory.DashScope.DEFAULT_BASE_URL, extractBaseUrl(model));
    }

    @Test
    void respectsCustomDashScopeBaseUrl() throws Exception {
        var selector = new ModelSelector(
                "dashscope",
                "qwen3.7-max-2026-06-08",
                "test-key",
                0.7,
                true,
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/",
                null,
                true
        );

        var model = assertInstanceOf(OpenAIChatModel.class, ModelFactory.create(selector));

        assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1", extractBaseUrl(model));
    }

    private static String extractBaseUrl(OpenAIChatModel model) throws Exception {
        Field optionsField = OpenAIChatModel.class.getDeclaredField("configuredOptions");
        optionsField.setAccessible(true);
        GenerateOptions options = (GenerateOptions) optionsField.get(model);
        return options.getBaseUrl();
    }
}
