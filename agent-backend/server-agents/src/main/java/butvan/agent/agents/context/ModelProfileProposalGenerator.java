package butvan.agent.agents.context;

import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.model.ModelSelector;
import butvan.agent.agents.usage.ModelIdentity;
import butvan.agent.agents.usage.SystemUsageLedger;
import butvan.agent.agents.usage.UsagePurpose;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 使用当前激活模型生成结构化画像提案的生产适配器。 */
@Component
@RequiredArgsConstructor
public class ModelProfileProposalGenerator implements ProfileProposalGenerator {

    private static final String SYSTEM_PROMPT = """
            你负责维护一份简洁、长期稳定的用户画像。只依据给出的 evidence 提议变更，不得猜测，
            不得记录密码、密钥、令牌、身份号码、银行卡、余额或病历等敏感内容。
            只保留长期偏好、沟通方式、稳定工作习惯和明确的长期背景；临时任务、一次性状态和设备扫描信息不要写入。
            完整画像必须保持精炼，控制在约 300 tokens 以内。
            返回且只返回 JSON：
            {"summary":"一句话摘要","proposedProfile":"完整 Markdown 画像","changes":[
              {"operation":"ADD|UPDATE|DELETE","section":"栏目","before":"原文或空串","after":"新文或空串",
               "reason":"简短理由","sourceIds":["必须来自 evidence 的 sourceId"],"confidence":0.0}
            ]}
            没有可靠变化时，proposedProfile 原样返回且 changes 为空。每项变化 confidence 必须至少 0.7。
            """;

    private final ModelHolder modelHolder;
    private final SystemUsageLedger systemUsageLedger;
    private final ObjectMapper objectMapper;

    @Override
    public ProfileGenerationResult generate(ProfileGenerationRequest request) {
        if (!modelHolder.isInitialized()) throw new IllegalStateException("请先完成模型配置");
        var model = modelHolder.getModel();
        GenerateOptions options = GenerateOptions.builder()
                .stream(false)
                .temperature(0.1)
                .maxTokens(900)
                .additionalBodyParam("thinking", java.util.Map.of("type", "disabled"))
                .build();
        List<ChatResponse> responses;
        try {
            responses = model.stream(
                            List.of(
                                    SystemMessage.builder().textContent(SYSTEM_PROMPT).build(),
                                    UserMessage.builder().textContent(serializeRequest(request)).build()),
                            List.of(),
                            options)
                    .collectList()
                    .block();
        } catch (RuntimeException exception) {
            recordUsage(model, null);
            throw exception;
        }
        ChatResponse usageResponse = responses == null ? null : responses.stream()
                .filter(response -> response.getUsage() != null)
                .reduce((first, last) -> last)
                .orElse(null);
        recordUsage(model, usageResponse);
        String content = responses == null ? "" : responses.stream()
                .flatMap(response -> response.getContent() == null
                        ? java.util.stream.Stream.empty()
                        : response.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
        return parseResult(content);
    }

    private String serializeRequest(ProfileGenerationRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("序列化画像维护输入失败", exception);
        }
    }

    ProfileGenerationResult parseResult(String raw) {
        int start = raw == null ? -1 : raw.indexOf('{');
        int end = raw == null ? -1 : raw.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("模型未返回有效的画像提案 JSON");
        try {
            return objectMapper.readValue(raw.substring(start, end + 1), ProfileGenerationResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("模型返回的画像提案格式无效", exception);
        }
    }

    private void recordUsage(io.agentscope.core.model.Model model, ChatResponse response) {
        ModelSelector selector = modelHolder.getCurrentSelector();
        systemUsageLedger.append(
                null,
                UsagePurpose.PROFILE_MAINTENANCE,
                response == null ? null : response.getId(),
                new ModelIdentity(selector == null ? null : selector.vendor(), model.getModelName()),
                response == null ? null : response.getUsage());
    }
}
