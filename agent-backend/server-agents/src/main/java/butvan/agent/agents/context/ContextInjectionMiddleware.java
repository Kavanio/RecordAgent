package butvan.agent.agents.context;

import butvan.agent.agents.usage.TokenUsageRoundContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 在真实 Model Call 前临时注入已组装上下文，不修改 AgentState 中的消息。 */
@Component
@lombok.extern.slf4j.Slf4j
public class ContextInjectionMiddleware implements MiddlewareBase {

    private butvan.agent.agents.usage.TokenCounter tokenCounter = new butvan.agent.agents.usage.ApproximateTokenCounter();
    /** 失效过滤后沿用项目同一计数器重建信封和归因元数据。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTokenCounter(butvan.agent.agents.usage.TokenCounter counter) { this.tokenCounter = counter; }

    public static final String CONTEXT_METADATA_KEY = "butvan_managed_context";
    public static final String PROFILE_TOKENS_METADATA_KEY = "butvan_profile_tokens";
    public static final String MEMORY_TOKENS_METADATA_KEY = "butvan_memory_tokens";

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next
    ) {
        ContextEnvelope envelope = context == null ? null : context.get(ContextEnvelope.class);
        if (envelope != null) {
            envelope = revalidate(envelope);
            // 失效后永久移出本轮信封，重新开启开关也不能复活同一份旧缓存。
            context.put(ContextEnvelope.class, envelope);
        }
        if (envelope == null || envelope.isEmpty() || input == null || input.messages() == null) {
            return next.apply(input);
        }

        int currentUserIndex = findCurrentUser(input.messages(), context.get(TokenUsageRoundContext.class));
        if (currentUserIndex < 0) return next.apply(input);

        List<Msg> messages = new ArrayList<>(input.messages());
        messages.add(currentUserIndex, UserMessage.builder()
                .name("managed_context")
                .textContent(envelope.rendered())
                .metadata(Map.of(
                        Msg.METADATA_SYNTHETIC, true,
                        Msg.METADATA_REMINDER_KIND, CONTEXT_METADATA_KEY,
                        CONTEXT_METADATA_KEY, true,
                        PROFILE_TOKENS_METADATA_KEY, envelope.profileTokens(),
                        MEMORY_TOKENS_METADATA_KEY, envelope.memoryTokens()))
                .build());
        return next.apply(new ModelCallInput(messages, input.tools(), input.options(), input.model()));
    }

    private int findCurrentUser(List<Msg> messages, TokenUsageRoundContext round) {
        if (round == null) return -1;
        for (int index = 0; index < messages.size(); index++) {
            Msg message = messages.get(index);
            if (message == null || message.getRole() != MsgRole.USER || message.getMetadata() == null) continue;
            if (round.turnId().equals(
                    message.getMetadata().get(TokenUsageRoundContext.TURN_METADATA_KEY))) return index;
        }
        return -1;
    }

    private ContextEnvelope revalidate(ContextEnvelope envelope) {
        try {
            if (!envelope.stillCurrent().getAsBoolean()) return ContextEnvelope.empty();
            var kept = envelope.blocks().stream().filter(block -> {
                try { return block.stillCurrent().getAsBoolean(); }
                catch (RuntimeException exception) {
                    log.warn("上下文片段失效检查失败：errorType={}", exception.getClass().getSimpleName()); return false;
                }
            }).toList();
            return kept.size() == envelope.blocks().size() ? envelope
                    : ContextEnvelopeBuilder.build(kept, envelope.estimatedTokens(), tokenCounter, envelope.stillCurrent());
        } catch (RuntimeException exception) {
            log.warn("上下文有效性检查失败：errorType={}", exception.getClass().getSimpleName()); return ContextEnvelope.empty();
        }
    }
}
