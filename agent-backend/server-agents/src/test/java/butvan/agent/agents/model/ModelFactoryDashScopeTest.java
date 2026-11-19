package butvan.agent.agents.model;

import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 DashScope 端点选择与基础地址透传，规避 SDK 2.0.0 对多模态 qwen3.7-max 快照的误判。 */
class ModelFactoryDashScopeTest {

    @Test
    void selectsMultimodalEndpointForMultimodalQwen37MaxSnapshots() {
        var juneSnapshot = new ModelSelector("dashscope", "qwen3.7-max-2026-06-08", "test-key", 0.7, true);
        var laterSnapshot = new ModelSelector("dashscope", "QWEN3.7-MAX-2026-09-01", "test-key", 0.7, true);
        var mayTextSnapshot = new ModelSelector("dashscope", "qwen3.7-max-2026-05-20", "test-key", 0.7, true);

        assertEquals(EndpointType.MULTIMODAL, ModelFactory.DashScope.resolveEndpointType(juneSnapshot));
        assertEquals(EndpointType.MULTIMODAL, ModelFactory.DashScope.resolveEndpointType(laterSnapshot));
        assertEquals(EndpointType.AUTO, ModelFactory.DashScope.resolveEndpointType(mayTextSnapshot));
    }

    @Test
    void selectsMultimodalForRollingQwen37MaxOnlyWhenVisionEnabledAndLeavesOtherModelsAuto() {
        var rollingText = new ModelSelector("dashscope", "qwen3.7-max", "test-key", 0.7, true, null, null, false);
        var rollingVision = new ModelSelector("dashscope", "qwen3.7-max", "test-key", 0.7, true, null, null, true);
        var otherModel = new ModelSelector("dashscope", "qwen-plus", "test-key", 0.7, true, null, null, true);

        assertEquals(EndpointType.AUTO, ModelFactory.DashScope.resolveEndpointType(rollingText));
        assertEquals(EndpointType.MULTIMODAL, ModelFactory.DashScope.resolveEndpointType(rollingVision));
        assertEquals(EndpointType.AUTO, ModelFactory.DashScope.resolveEndpointType(otherModel));
    }

    @Test
    void appliesEndpointTypeAndOptionalBaseUrlWhenCreatingDashScopeModel() throws Exception {
        var selector = new ModelSelector(
                "dashscope",
                "qwen3.7-max-2026-06-08",
                "test-key",
                0.7,
                true,
                "https://dashscope-intl.aliyuncs.com/",
                null,
                false
        );

        var model = assertInstanceOf(DashScopeChatModel.class, ModelFactory.create(selector));

        Field endpointTypeField = DashScopeChatModel.class.getDeclaredField("endpointType");
        endpointTypeField.setAccessible(true);
        assertEquals(EndpointType.MULTIMODAL, endpointTypeField.get(model));

        Field httpClientField = DashScopeChatModel.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        Object httpClient = httpClientField.get(model);
        Field baseUrlField = httpClient.getClass().getDeclaredField("baseUrl");
        baseUrlField.setAccessible(true);
        assertEquals("https://dashscope-intl.aliyuncs.com", baseUrlField.get(httpClient));
    }
}
