package butvan.agent.agents.session.dto;

import butvan.agent.agents.usage.TurnTokenUsage;

import java.time.Instant;
import java.util.List;

/** 用户界面能够稳定展示的一条完整消息。 */
public record TranscriptMessageDto(
        String id,
        String turnId,
        MessageRole role,
        String content,
        Instant createdAt,
        MessageStatus status,
        Long durationMillis,
        List<ToolExecutionDto> tools,
        String thinking,
        TurnTokenUsage usage,
        boolean proactive
) {

    public TranscriptMessageDto(
            String id,
            String turnId,
            MessageRole role,
            String content,
            Instant createdAt,
            MessageStatus status,
            Long durationMillis,
            List<ToolExecutionDto> tools,
            String thinking,
            TurnTokenUsage usage
    ) {
        this(id, turnId, role, content, createdAt, status, durationMillis, tools, thinking, usage, false);
    }

    public TranscriptMessageDto {
        content = content == null ? "" : content;
        tools = tools == null ? List.of() : List.copyOf(tools);
        thinking = (thinking == null || thinking.isBlank()) ? null : thinking;
    }

    /** 消息角色。工具详情后续可扩展为单独事件，不和普通消息混用。 */
    public enum MessageRole {
        USER,
        ASSISTANT
    }

    /** assistant 消息是否完整、失败或因客户端断开被取消。 */
    public enum MessageStatus {
        COMPLETED,
        FAILED,
        CANCELLED
    }


    public record ToolExecutionDto(
            String toolCallId,
            String toolName,
            String command,
            String output,
            ToolStatus status
    ) {
        public ToolExecutionDto {
            toolCallId = toolCallId == null ? "" : toolCallId;
            toolName = toolName == null ? "" : toolName;
            command = command == null ? "" : command;
            output = output == null ? "" : output;
        }
    }

    public enum ToolStatus {
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

}
