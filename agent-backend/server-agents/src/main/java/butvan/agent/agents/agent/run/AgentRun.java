package butvan.agent.agents.agent.run;

import butvan.agent.agents.agent.event.AgentStreamEvent;
import butvan.agent.agents.agent.AgentImageAttachment;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.agents.session.AgentTurnResult;
import io.agentscope.core.event.AgentResultEvent;
import butvan.agent.agents.usage.ModelIdentity;
import butvan.agent.agents.usage.TokenUsageRoundContext;
import butvan.agent.agents.usage.TurnTokenUsage;
import butvan.agent.agents.usage.TurnUsageAccumulator;
import butvan.agent.agents.usage.UsagePurpose;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次尚未完成的用户会和，只在服务进程内存活
 */
public class AgentRun {

    private final String sessionId;
    private final String userId;
    private final String turnId;
    private final RuntimeContext runtimeContext;
    private final Instant startedAt;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    /** 仅在 transcript 写入成功后设置，权限暂停不产生最终结果。 */
    private volatile TranscriptMessageDto.MessageStatus persistedStatus;
    private final TurnUsageAccumulator usageAccumulator = new TurnUsageAccumulator();
    private final AtomicReference<CompletionState> completionState =
            new AtomicReference<>(CompletionState.READY);
    // 初始 SSE 与恢复 SSE 共用同一份工具参数和执行记录
    private final Map<String, StringBuilder> toolArgsBuffer = new ConcurrentHashMap<>();
    /** 只保留工具结果前缀，兼容框架把技术错误标为 SUCCESS 的版本；终态即移除。 */
    private final Map<String, StringBuilder> toolResultPrefixBuffer = new ConcurrentHashMap<>();
    private final Map<String, TranscriptMessageDto.ToolExecutionDto> toolExecutions = new LinkedHashMap<>();
    private final List<MutableBlock> blocks = new ArrayList<>();

    public AgentRun(String sessionId, String userId, String turnId, RuntimeContext runtimeContext) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.turnId = turnId;
        this.runtimeContext = runtimeContext;
        this.startedAt = Instant.now();
        if (runtimeContext != null) {
            runtimeContext.put(TurnUsageAccumulator.class, usageAccumulator);
            runtimeContext.put(TokenUsageRoundContext.class, new TokenUsageRoundContext(turnId));
        }
    }

    // 下面 getter 仅暴露恢复和最终落库需要的数据。
    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
    public String turnId() { return turnId; }
    public RuntimeContext runtimeContext() { return runtimeContext; }
    public Instant startedAt() { return startedAt; }
    // 收尾落库读取最终正文（只读快照，不暴露内部 StringBuilder）
    public String contentAsString() {
        return content.toString();
    }
    // 收尾落库读取最终思考（只读快照）
    public String thinkingAsString() {
        return thinking.toString();
    }
    public Map<String, StringBuilder> toolArgsBuffer() { return toolArgsBuffer; }
    public Map<String, StringBuilder> toolResultPrefixBuffer() { return toolResultPrefixBuffer; }

    /** 创建带当前轮次标记的用户消息，供 Middleware 区分历史消息。 */
    public Msg currentUserMessage(String input) {
        return currentUserMessage(input, List.of());
    }

    /** 创建当前用户消息，并登记其中由检索资料贡献的上下文片段。 */
    public Msg currentUserMessage(String input, List<String> ragContexts) {
        return currentUserMessage(input, ragContexts, List.of());
    }

    /**
     * 创建当前用户消息，并把图片作为内容块随本轮交给模型。
     *
     * <p>图片只存在于内存内容块中，不写入 transcript 或 checkpoint；
     * 是否真的发给供应商仍由模型能力与适配器决定。</p>
     *
     * @param input       用户文本；纯图片消息也要给模型一个可读的说明
     * @param ragContexts 检索资料片段
     * @param images      本轮图片附件
     * @return 当前轮次用户消息
     */
    public Msg currentUserMessage(String input, List<String> ragContexts, List<AgentImageAttachment> images) {
        if (runtimeContext != null) {
            runtimeContext.put(TokenUsageRoundContext.class, new TokenUsageRoundContext(turnId, ragContexts));
        }
        List<ContentBlock> content = new ArrayList<>();
        if (input != null && !input.isBlank()) {
            content.add(TextBlock.builder().text(input).build());
        }
        if (images != null) {
            for (AgentImageAttachment image : images) {
                content.add(ImageBlock.builder()
                        .source(new Base64Source(image.mediaType(), image.base64()))
                        .build());
            }
        }
        return UserMessage.builder()
                .content(content)
                .metadata(Map.of(TokenUsageRoundContext.TURN_METADATA_KEY, turnId))
                .build();
    }

    /** 记录一次 AgentScope 原始模型事件，避免 UI 事件映射丢失 usage。 */
    public void recordModelEvent(AgentEvent event, ModelIdentity modelIdentity) {
        usageAccumulator.record(event, modelIdentity, UsagePurpose.CHAT);
        // AgentResultEvent 携带完整回复，不依赖消费队列是否曾丢弃增量，也不混入工具前说明。
        if (event.getSource() == null && event instanceof AgentResultEvent result
                && result.getResult() != null && result.getResult().getRole() == io.agentscope.core.message.MsgRole.ASSISTANT) {
            String finalText = result.getResult().getTextContent();
            if (finalText == null) {
                finalText = "";
            }
            content.setLength(0);
            content.append(finalText);
            applyFinalResultToBlocks(finalText);
        }
    }

    /** 返回当前轮次全部模型调用的不可变用量快照。 */
    public TurnTokenUsage tokenUsage() {
        return usageAccumulator.snapshot();
    }

    /** 生成可安全落盘的运行中快照；RUNNING 工具在意外退出后按取消恢复。 */
    public AgentRunCheckpoint checkpoint() {
        return new AgentRunCheckpoint(
                sessionId,
                turnId,
                startedAt,
                Instant.now(),
                contentAsString(),
                thinkingAsString(),
                finalizeTools(TranscriptMessageDto.MessageStatus.CANCELLED),
                tokenUsage(),
                finalizeBlocks(TranscriptMessageDto.MessageStatus.CANCELLED)
        );
    }

    /** 仅允许一个终态路径进入持久化；写入失败后允许安全重试。 */
    public boolean beginCompletion() {
        return completionState.compareAndSet(CompletionState.READY, CompletionState.WRITING);
    }

    /** transcript 已成功写入，此后其他终态路径不得重复追加。 */
    public void commitCompletion(TranscriptMessageDto.MessageStatus status) {
        persistedStatus = status;
        completionState.compareAndSet(CompletionState.WRITING, CompletionState.COMPLETED);
    }

    /** 返回已经落盘的结果；暂停或持久化失败时返回 null，不伪造成功。 */
    public AgentTurnResult persistedResult(String runId) {
        return persistedStatus == null ? null
                : new AgentTurnResult(runId, sessionId, turnId, persistedStatus, contentAsString());
    }

    /** transcript 写入失败，恢复为可重试状态。 */
    public void abortCompletion() {
        completionState.compareAndSet(CompletionState.WRITING, CompletionState.READY);
    }

    /**
     * 将映射后的业务事件累计到运行态：正文/思考增量与工具执行记录
     * @param event
     */
    public void record(AgentStreamEvent event) {

        // 正文增量：直接累加到最终正文，并同步写入有序时间线片段
        if (event instanceof AgentStreamEvent.TextDelta text) {
            content.append(text.content());
            appendTextBlock(text.content());

            // 思考增量：直接累加到最终思考过程，并同步写入有序时间线片段
        } else if (event instanceof AgentStreamEvent.ThinkingDelta thinkingDelta) {
            thinking.append(thinkingDelta.content());
            appendThinkingBlock(thinkingDelta.content());

            // 工具调用发起：记录一条 RUNNING 状态
        } else if (event instanceof AgentStreamEvent.ToolCall toolCall) {
            recordToolCall(toolCall);

            // 工具结果返回：追加输出并按事件中的真实状态更新
        } else if (event instanceof AgentStreamEvent.ToolResult toolResult) {
            recordToResult(toolResult);
        }
    }

    private void recordToResult(AgentStreamEvent.ToolResult toolResult) {
        // 追加本次结果输出；command 沿用之前已记录的值
        TranscriptMessageDto.ToolExecutionDto updated = toolExecutions.compute(toolResult.toolCallId(), (key, previous) ->
                new TranscriptMessageDto.ToolExecutionDto(
                        toolResult.toolCallId(),
                        toolResult.toolName(),
                        previous == null ? "" : previous.command(),
                        (previous == null ? "" : previous.output()) + toolResult.result(),
                        switch (toolResult.status()) {
                            case RUNNING -> TranscriptMessageDto.ToolStatus.RUNNING;
                            case COMPLETED -> TranscriptMessageDto.ToolStatus.COMPLETED;
                            case FAILED -> TranscriptMessageDto.ToolStatus.FAILED;
                            case CANCELLED -> TranscriptMessageDto.ToolStatus.CANCELLED;
                        }
                ));
        recordToolInBlocks(updated);
    }

    private void recordToolCall(AgentStreamEvent.ToolCall toolCall) {
        // 同一 callId 只保留一条记录；command 为空时沿用上一次的值
        TranscriptMessageDto.ToolExecutionDto updated = toolExecutions.compute(toolCall.toolCallId(), (key, previous) ->
                new TranscriptMessageDto.ToolExecutionDto(
                        toolCall.toolCallId(),
                        toolCall.toolName(),
                        toolCall.command().isBlank() && previous != null ? previous.command() : toolCall.command(),
                        previous == null ? "" : previous.output(),
                        TranscriptMessageDto.ToolStatus.RUNNING // 工具尚未返回结果
                ));
        recordToolInBlocks(updated);
    }

    /**
     * 将尚未收到结果的 RUNNING 工具转换为最终状态
     * @param messageStatus
     * @return
     */
    public List<TranscriptMessageDto.ToolExecutionDto> finalizeTools(
            TranscriptMessageDto.MessageStatus messageStatus
    ) {
        TranscriptMessageDto.ToolStatus fallbackStatus = fallbackToolStatus(messageStatus);
        // 只把任处于 RUNNING 的记录改为兜底状态，其余原样保留
        return toolExecutions.values().stream()
                .map(tool -> finalizeTool(tool, fallbackStatus))
                .toList();
    }

    /** 按事件发生顺序产出最终时间线分段块，并将遗留 RUNNING 工具收敛为终态。 */
    public List<TranscriptMessageDto.MessageBlockDto> finalizeBlocks(
            TranscriptMessageDto.MessageStatus messageStatus
    ) {
        TranscriptMessageDto.ToolStatus fallbackStatus = fallbackToolStatus(messageStatus);
        synchronized (blocks) {
            List<TranscriptMessageDto.MessageBlockDto> result = new ArrayList<>();
            for (MutableBlock block : blocks) {
                if (block.kind == TranscriptMessageDto.BlockKind.THINKING) {
                    String value = block.text.toString();
                    if (!value.isBlank()) {
                        result.add(TranscriptMessageDto.MessageBlockDto.thinking(value));
                    }
                } else if (block.kind == TranscriptMessageDto.BlockKind.TEXT) {
                    String value = block.text.toString();
                    if (!value.isBlank()) {
                        result.add(TranscriptMessageDto.MessageBlockDto.text(value));
                    }
                } else if (block.kind == TranscriptMessageDto.BlockKind.TOOL_GROUP && !block.tools.isEmpty()) {
                    List<TranscriptMessageDto.ToolExecutionDto> groupTools = block.tools.values().stream()
                            .map(tool -> finalizeTool(tool, fallbackStatus))
                            .toList();
                    result.add(TranscriptMessageDto.MessageBlockDto.toolGroup(groupTools));
                }
            }
            return List.copyOf(result);
        }
    }

    private void appendTextBlock(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (blocks) {
            MutableBlock last = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
            if (last != null && last.kind == TranscriptMessageDto.BlockKind.TEXT) {
                last.text.append(delta);
            } else if (!delta.isBlank()) {
                blocks.add(MutableBlock.text(delta));
            }
        }
    }

    private void appendThinkingBlock(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (blocks) {
            MutableBlock last = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
            if (last != null && last.kind == TranscriptMessageDto.BlockKind.THINKING) {
                last.text.append(delta);
            } else if (!delta.isBlank()) {
                blocks.add(MutableBlock.thinking(delta));
            }
        }
    }

    private void recordToolInBlocks(TranscriptMessageDto.ToolExecutionDto tool) {
        synchronized (blocks) {
            for (int i = blocks.size() - 1; i >= 0; i--) {
                MutableBlock block = blocks.get(i);
                if (block.kind == TranscriptMessageDto.BlockKind.TOOL_GROUP
                        && block.tools.containsKey(tool.toolCallId())) {
                    block.tools.put(tool.toolCallId(), tool);
                    return;
                }
            }
            MutableBlock last = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
            if (last != null && last.kind == TranscriptMessageDto.BlockKind.TOOL_GROUP) {
                last.tools.put(tool.toolCallId(), tool);
            } else {
                MutableBlock group = MutableBlock.toolGroup();
                group.tools.put(tool.toolCallId(), tool);
                blocks.add(group);
            }
        }
    }

    private void applyFinalResultToBlocks(String finalText) {
        if (finalText == null || finalText.isBlank()) {
            return;
        }
        synchronized (blocks) {
            MutableBlock last = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
            if (last != null && last.kind == TranscriptMessageDto.BlockKind.TEXT) {
                last.text.setLength(0);
                last.text.append(finalText);
            } else {
                blocks.add(MutableBlock.text(finalText));
            }
        }
    }

    private static TranscriptMessageDto.ToolStatus fallbackToolStatus(
            TranscriptMessageDto.MessageStatus messageStatus
    ) {
        return switch (messageStatus) {
            case COMPLETED, FAILED -> TranscriptMessageDto.ToolStatus.FAILED;
            case CANCELLED -> TranscriptMessageDto.ToolStatus.CANCELLED;
        };
    }

    private static TranscriptMessageDto.ToolExecutionDto finalizeTool(
            TranscriptMessageDto.ToolExecutionDto tool,
            TranscriptMessageDto.ToolStatus fallbackStatus
    ) {
        return tool.status() == TranscriptMessageDto.ToolStatus.RUNNING
                ? new TranscriptMessageDto.ToolExecutionDto(
                        tool.toolCallId(), tool.toolName(), tool.command(), tool.output(), fallbackStatus
                ) : tool;
    }

    private static final class MutableBlock {
        final TranscriptMessageDto.BlockKind kind;
        final StringBuilder text;
        final Map<String, TranscriptMessageDto.ToolExecutionDto> tools;

        private MutableBlock(
                TranscriptMessageDto.BlockKind kind,
                StringBuilder text,
                Map<String, TranscriptMessageDto.ToolExecutionDto> tools
        ) {
            this.kind = kind;
            this.text = text;
            this.tools = tools;
        }

        static MutableBlock thinking(String initial) {
            return new MutableBlock(TranscriptMessageDto.BlockKind.THINKING, new StringBuilder(initial), Map.of());
        }

        static MutableBlock text(String initial) {
            return new MutableBlock(TranscriptMessageDto.BlockKind.TEXT, new StringBuilder(initial), Map.of());
        }

        static MutableBlock toolGroup() {
            return new MutableBlock(TranscriptMessageDto.BlockKind.TOOL_GROUP, new StringBuilder(), new LinkedHashMap<>());
        }
    }

    private enum CompletionState {
        READY,
        WRITING,
        COMPLETED
    }


}
