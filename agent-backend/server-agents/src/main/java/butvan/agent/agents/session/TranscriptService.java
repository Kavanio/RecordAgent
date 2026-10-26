package butvan.agent.agents.session;

import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.agents.usage.TurnTokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 用户可见聊天记录服务。
 *
 * <p>每行是一条完整 JSON 消息。用户消息在 Agent 调用前写入；assistant 消息在 SSE
 * 正常结束、失败或取消时写入一次，避免逐 token 写盘带来的大量 I/O。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptService {

    private final AgentStorageProperties storageProperties;

    private final ObjectMapper objectMapper;

    /**
     * 读取一条会话的所有完整消息
     * @param sessionId
     * @return
     */
    public synchronized List<TranscriptMessageDto> list(String sessionId) {
        Path transcriptFile = storageProperties.transcriptFile(sessionId);
        if (!Files.exists(transcriptFile)) {
            return List.of();
        }

        List<TranscriptMessageDto> messages = new ArrayList<>();

        try (var lines = Files.lines(transcriptFile, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    messages.add(objectMapper.readValue(line, TranscriptMessageDto.class));
                } catch (IOException e) {
                    log.warn("跳过损坏的会话消息记录：sessionId={}",sessionId);
                }
            });
            return List.copyOf(messages);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取会话消息记录失败", e);
        }
    }

    /**
     * 在 Agent 前写入用户消息，并返回本轮 turnId
     * @param sessionId
     * @param content
     * @return
     */
    public synchronized String appendUserMessage(String sessionId, String content) {
        return appendUserMessageWithTurnId(sessionId, content, UUID.randomUUID().toString());
    }

    /** 渠道在受理落盘时即确定轮次 ID，重启可精确关联结果；已有轮次禁止重复执行。 */
    public synchronized String appendUserMessageWithTurnId(String sessionId, String content, String turnId) {
        if (turnId == null || !UUID.fromString(turnId).toString().equals(turnId)
                || list(sessionId).stream().anyMatch(message -> turnId.equals(message.turnId()))) {
            throw new IllegalArgumentException("轮次 ID 无效或已被使用");
        }
        append(sessionId, new TranscriptMessageDto(
                UUID.randomUUID().toString(),
                turnId,
                TranscriptMessageDto.MessageRole.USER,
                content,
                Instant.now(),
                TranscriptMessageDto.MessageStatus.COMPLETED,
                null,
                List.of(),
                null, // 用户消息没有思考过程
                null // 用户消息不承载模型用量
        ));

        return turnId;
    }

    /**
     * 在一次 Agent 流结束后写入完整 assistant 消息
     */
    public synchronized void appendAssistantMessage(
            String sessionId,
            String turnId,
            String content,
            String thinking,
            TranscriptMessageDto.MessageStatus status,
            Long durationMillis,
            List<TranscriptMessageDto.ToolExecutionDto> tools,
            TurnTokenUsage usage
    ) {
        if (hasAssistantMessage(sessionId, turnId)) return;
        append(sessionId, new TranscriptMessageDto(
                UUID.randomUUID().toString(),
                turnId,
                TranscriptMessageDto.MessageRole.ASSISTANT,
                content == null ? "" : content,
                Instant.now(),
                status,
                durationMillis,
                tools,
                thinking,
                usage
        ));
    }

    /** 判断某轮 assistant 终态是否已经写入，用于崩溃恢复去重。 */
    public synchronized boolean hasAssistantMessage(String sessionId, String turnId) {
        return list(sessionId).stream().anyMatch(message ->
                message.role() == TranscriptMessageDto.MessageRole.ASSISTANT
                        && java.util.Objects.equals(message.turnId(), turnId)
        );
    }

    /**
     * 删除本项目拥有的用户可见记录
     * @param sessionId
     */
    public synchronized void delete(String sessionId) {
        try {
            Files.deleteIfExists(storageProperties.transcriptFile(sessionId));
        } catch (IOException e) {
            throw new IllegalArgumentException("删除会话消息记录失败", e);
        }
    }

    private void append(String sessionId, TranscriptMessageDto message) {
        Path transcriptFile = storageProperties.transcriptFile(sessionId);
        try (BufferedWriter writer = Files.newBufferedWriter(
                transcriptFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        )) {
            writer.write(objectMapper.writeValueAsString(message));
            writer.newLine();
        } catch (IOException e) {
            throw new IllegalArgumentException("写入会话消息记录失败",e);
        }
    }
}
