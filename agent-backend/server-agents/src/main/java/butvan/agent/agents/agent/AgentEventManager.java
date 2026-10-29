package butvan.agent.agents.agent;

import butvan.agent.agents.agent.event.AgentStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.*;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 将 AgentScope 原始事件翻译为项目 SSE 业务事件。
 *
 * <p>纯翻译模块：不持有会话状态、不落库、不打业务日志以外的副作用。
 * 工具参数增量缓冲由调用方传入（缓冲本身属于 AgentRun）。</p>
 */
@Component
public class AgentEventManager {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentStreamEvent map(AgentEvent event, Map<String, StringBuilder> toolArgsBuffer) {
        return map(event, toolArgsBuffer, new java.util.HashMap<>());
    }

    /** 使用当前 run 的前缀缓冲纠正已知框架技术错误，不依据正文中间出现的 Error 字样推断失败。 */
    public AgentStreamEvent map(AgentEvent event, Map<String, StringBuilder> toolArgsBuffer,
                                Map<String, StringBuilder> resultPrefixes) {

        if (event.getSource() != null) {
            String source = event.getSource();
            String agentId = parseAgentId(source);
            if (event instanceof AgentStartEvent) {
                return new AgentStreamEvent.SubagentProgress(source, agentId, "start", "");
            }
            if (event instanceof AgentEndEvent) {
                return new AgentStreamEvent.SubagentProgress(source, agentId, "end", "");
            }
            if (event instanceof TextBlockDeltaEvent text) {
                return new AgentStreamEvent.SubagentProgress(source, agentId, "text", text.getDelta());
            }
            if (event instanceof ToolCallStartEvent tool) {
                return new AgentStreamEvent.SubagentProgress(
                        source, agentId, "tool", tool.getToolCallName());
            }
            return null; // 其余子事件（thinking/结果增量等）暂不展示
        }

        // 文本增量：直接透传为业务事件
        if (event instanceof TextBlockDeltaEvent textEvent) {
            return new AgentStreamEvent.TextDelta(textEvent.getDelta());
        }
        // 思考增量：直接透传为业务事件
        if (event instanceof ThinkingBlockDeltaEvent thinkingEvent) {
            return new AgentStreamEvent.ThinkingDelta(thinkingEvent.getDelta());
        }
        // Agent 正常结束
        if (event instanceof AgentEndEvent) {
            return new AgentStreamEvent.Completed();
        }
        // 工具参数增量：只累积进缓冲，不单独产出业务事件
        if (event instanceof ToolCallDeltaEvent deltaEvent) {
            toolArgsBuffer.computeIfAbsent(deltaEvent.getToolCallId(), ignored -> new StringBuilder())
                    .append(deltaEvent.getDelta() == null ? "" : deltaEvent.getDelta());
            return null;
        }
        // 工具开始：command 未知，先给空串
        if (event instanceof ToolCallStartEvent startEvent) {
            return new AgentStreamEvent.ToolCall(
                    startEvent.getToolCallId(), startEvent.getToolCallName(), "");
        }
        // 工具结束：取走累积参数并解析 command（原 payload() 的发起日志可收敛到这里）
        if (event instanceof ToolCallEndEvent endEvent) {
            String rawArguments = String.valueOf(toolArgsBuffer.remove(endEvent.getToolCallId()));
            return new AgentStreamEvent.ToolCall(
                    endEvent.getToolCallId(),
                    endEvent.getToolCallName(),
                    parseCommandFromArguments(rawArguments));
        }
        // 工具结果增量：追加到业务事件（原 payload() 的结果日志可收敛到这里）
        if (event instanceof ToolResultTextDeltaEvent resultEvent) {
            StringBuilder prefix = resultPrefixes.computeIfAbsent(resultEvent.getToolCallId(), ignored -> new StringBuilder());
            String delta = resultEvent.getDelta() == null ? "" : resultEvent.getDelta();
            if (prefix.length() < 128) prefix.append(delta, 0, Math.min(delta.length(), 128 - prefix.length()));
            return new AgentStreamEvent.ToolResult(
                    resultEvent.getToolCallId(),
                    resultEvent.getToolCallName(),
                    resultEvent.getDelta(),
                    AgentStreamEvent.ToolStatus.RUNNING);
        }
        // 工具结果终态：单独传递真实状态，避免错误结果在 UI 中显示为成功。
        if (event instanceof ToolResultEndEvent endEvent) {
            StringBuilder buffered = resultPrefixes.remove(endEvent.getToolCallId());
            String prefix = buffered == null ? "" : buffered.toString().stripLeading();
            boolean knownError = prefix.startsWith("Error: Unauthorized tool call:")
                    || prefix.startsWith("Error: Parameter validation failed for tool")
                    || prefix.startsWith("Error: Tool not found:");
            return new AgentStreamEvent.ToolResult(
                    endEvent.getToolCallId(),
                    endEvent.getToolCallName(),
                    "",
                    switch (endEvent.getState()) {
                        case SUCCESS -> knownError ? AgentStreamEvent.ToolStatus.FAILED : AgentStreamEvent.ToolStatus.COMPLETED;
                        case INTERRUPTED -> AgentStreamEvent.ToolStatus.CANCELLED;
                        case ERROR, DENIED -> AgentStreamEvent.ToolStatus.FAILED;
                        case RUNNING -> AgentStreamEvent.ToolStatus.RUNNING;
                    });
        }
        // 其余尚未接入 UI 的事件：显式忽略
        return null;
    }

    /** 尝试从工具参数 JSON 取 command；非 JSON 参数则原样返回供 UI 展示。 */
    private String parseCommandFromArguments(String rawArguments) {
        // 空参数或 "null" 字面量：没有可展示的 command
        if (rawArguments == null || rawArguments.isBlank() || "null".equals(rawArguments)) {
            return "";
        }
        try {
            // 参数是合法 JSON：优先取 command 字段
            JsonNode node = objectMapper.readTree(rawArguments);
            return node.has("command") ? node.get("command").asText() : rawArguments;
        } catch (JsonProcessingException exception) {
            // 参数不是合法 JSON：原样返回供 UI 展示
            return rawArguments;
        }
    }

    /**
     * 从 source 路径取最后一段 agentId
     * @param source
     * @return
     */
    private static String parseAgentId(String source) {
        int idx = source == null ? -1 : source.lastIndexOf('/');
        return idx >= 0 ? source.substring(idx + 1) : source;
    }
}
