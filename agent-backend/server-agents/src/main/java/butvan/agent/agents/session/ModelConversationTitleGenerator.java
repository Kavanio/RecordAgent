package butvan.agent.agents.session;

import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.model.ModelSelector;
import butvan.agent.agents.usage.ModelIdentity;
import butvan.agent.agents.usage.SystemUsageLedger;
import butvan.agent.agents.usage.UsagePurpose;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 使用当前激活模型生成会话标题的生产适配器。 */
@Component
@RequiredArgsConstructor
public class ModelConversationTitleGenerator implements ConversationTitleGenerator {

    private final ModelHolder modelHolder;
    private final SystemUsageLedger systemUsageLedger;

    @Override
    public String generate(String sessionId, String firstQuestion) {
        GenerateOptions options = GenerateOptions.builder()
                .stream(false)
                .temperature(0.2)
                .maxTokens(32)
                .additionalBodyParam("thinking", java.util.Map.of("type", "disabled"))
                .build();
        var model = modelHolder.getModel();
        List<ChatResponse> responses;
        try {
            responses = model.stream(
                            List.of(
                                    SystemMessage.builder().textContent(
                                            "你负责给聊天会话命名。请根据用户问题输出一个准确、简洁的中文标题，最多20个汉字；不要引号、句号、序号或解释。"
                                    ).build(),
                                    UserMessage.builder().textContent(firstQuestion).build()
                            ),
                            List.of(),
                            options
                    )
                    .collectList()
                    .block();
        } catch (RuntimeException exception) {
            recordUsage(sessionId, model, null);
            throw exception;
        }
        ChatResponse usageResponse = responses == null ? null : responses.stream()
                .filter(response -> response.getUsage() != null)
                .reduce((first, last) -> last)
                .orElse(null);
        recordUsage(sessionId, model, usageResponse);
        if (responses == null) return "";
        return responses.stream()
                .flatMap(response -> response.getContent() == null
                        ? java.util.stream.Stream.empty()
                        : response.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(ContentBlock::toString)
                .reduce("", String::concat);
    }

    private void recordUsage(String sessionId, io.agentscope.core.model.Model model, ChatResponse response) {
        ModelSelector selector = modelHolder.getCurrentSelector();
        systemUsageLedger.append(
                sessionId,
                UsagePurpose.SESSION_TITLE,
                response == null ? null : response.getId(),
                new ModelIdentity(selector == null ? null : selector.vendor(), model.getModelName()),
                response == null ? null : response.getUsage()
        );
    }
}
