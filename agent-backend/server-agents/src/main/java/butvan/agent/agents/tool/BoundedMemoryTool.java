package butvan.agent.agents.tool;

import butvan.agent.agents.usage.ApproximateTokenCounter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** 为框架旧记忆读取提供输出预算与来源警示，不改写原始文件。 */
final class BoundedMemoryTool implements AgentTool {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BoundedMemoryTool.class);
    private static final String NOTE = "旧记忆可能含模型摘要或过期信息；用户原话与日期请用 session_recent/session_history 核对。\n";
    private final AgentTool delegate;

    BoundedMemoryTool(AgentTool delegate) { this.delegate = delegate; }

    @Override public String getName() { return delegate.getName(); }
    @Override public String getDescription() { return delegate.getDescription() + "；结果有界，旧摘要不是用户原话。"; }
    @Override public Map<String, Object> getParameters() { return delegate.getParameters(); }
    @Override public boolean isReadOnly() { return delegate.isReadOnly(); }

    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param).map(result -> {
            StringBuilder body = new StringBuilder();
            for (var block : result.getOutput()) if (block instanceof TextBlock text) body.append(text.getText());
            String value = body.toString();
            if (value.startsWith("\"")) {
                try { value = new ObjectMapper().readValue(value, String.class); }
                catch (java.io.IOException exception) {
                    LOG.debug("旧记忆结果无法解码为JSON字符串，保留原文：type={}", exception.getClass().getSimpleName());
                }
            }
            var counter = new ApproximateTokenCounter();
            String suffix = "\n[结果已截断；请缩小查询范围]";
            // 每4个码点至少一个估算Token，先限制候选前缀，避免对巨量旧结果反复全量计数。
            String clipped = value.substring(0, value.offsetByCodePoints(0,
                    Math.min(3_200, value.codePointCount(0, value.length()))));
            while (counter.count(NOTE + clipped + suffix) > 800 && !clipped.isEmpty())
                clipped = clipped.substring(0, clipped.offsetByCodePoints(0,
                        Math.max(0, clipped.codePointCount(0, clipped.length()) - 64)));
            return ToolResultBlock.builder().id(result.getId()).name(result.getName())
                    .metadata(result.getMetadata()).state(result.getState())
                    .output(List.of(TextBlock.builder().text(NOTE + clipped + (clipped.equals(value) ? "" : suffix)).build()))
                    .build();
        });
    }
}
