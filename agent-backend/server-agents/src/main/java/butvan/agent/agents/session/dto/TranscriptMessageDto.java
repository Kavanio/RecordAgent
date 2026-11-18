package butvan.agent.agents.session.dto;

import butvan.agent.agents.material.ReplyImages;
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
        boolean proactive,
        List<MessageBlockDto> blocks,
        List<String> imageFileIds,
        List<ReplyImages.Attachment> replyImages
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
        this(id, turnId, role, content, createdAt, status, durationMillis, tools, thinking, usage, false, List.of(), List.of());
    }

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
            TurnTokenUsage usage,
            boolean proactive
    ) {
        this(id, turnId, role, content, createdAt, status, durationMillis, tools, thinking, usage, proactive, List.of(), List.of());
    }

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
            TurnTokenUsage usage,
            boolean proactive,
            List<MessageBlockDto> blocks
    ) {
        this(id, turnId, role, content, createdAt, status, durationMillis, tools, thinking, usage, proactive, blocks, List.of());
    }

    public TranscriptMessageDto(
            String id, String turnId, MessageRole role, String content, Instant createdAt, MessageStatus status,
            Long durationMillis, List<ToolExecutionDto> tools, String thinking, TurnTokenUsage usage,
            boolean proactive, List<MessageBlockDto> blocks, List<String> imageFileIds
    ) {
        this(id, turnId, role, content, createdAt, status, durationMillis, tools, thinking, usage,
                proactive, blocks, imageFileIds, List.of());
    }

    public TranscriptMessageDto {
        content = content == null ? "" : content;
        tools = tools == null ? List.of() : List.copyOf(tools);
        thinking = (thinking == null || thinking.isBlank()) ? null : thinking;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        imageFileIds = imageFileIds == null ? List.of() : List.copyOf(imageFileIds);
        replyImages = replyImages == null ? List.of() : List.copyOf(replyImages);
    }

    public enum BlockKind {
        THINKING,
        TEXT,
        TOOL_GROUP
    }

    /** 按事件产生顺序记录的消息分段块（思考、过程/结论正文、工具执行组）。 */
    public record MessageBlockDto(
            BlockKind kind,
            String content,
            List<ToolExecutionDto> tools
    ) {
        public MessageBlockDto {
            content = content == null ? "" : content;
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        public static MessageBlockDto thinking(String content) {
            return new MessageBlockDto(BlockKind.THINKING, content, List.of());
        }

        public static MessageBlockDto text(String content) {
            return new MessageBlockDto(BlockKind.TEXT, content, List.of());
        }

        public static MessageBlockDto toolGroup(List<ToolExecutionDto> tools) {
            return new MessageBlockDto(BlockKind.TOOL_GROUP, "", tools);
        }
    }

    /** 仅当存在多段内容、思考块或工具组时才需要持久化有序 blocks，避免纯文本轮次冗余存储。 */
    public static boolean hasStructuredBlocks(List<MessageBlockDto> blocks) {
        if (blocks == null || blocks.isEmpty()) return false;
        return blocks.size() > 1 || blocks.get(0).kind() != BlockKind.TEXT;
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
