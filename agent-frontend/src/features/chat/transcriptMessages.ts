import type { ChatMessage, TranscriptMessageDto } from '../../types/chat';

function padTwo(value: number): string {
  return String(value).padStart(2, '0');
}

/** 将毫秒时间戳格式化为本地日期时间（精确到秒）：YYYY-MM-DD HH:mm:ss。 */
export function formatMessageTimestampSeconds(timestamp: number): string {
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '';
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '';
  return `${date.getFullYear()}-${padTwo(date.getMonth() + 1)}-${padTwo(date.getDate())} `
    + `${padTwo(date.getHours())}:${padTwo(date.getMinutes())}:${padTwo(date.getSeconds())}`;
}

/**
 * 判断一条历史/后端消息是否属于 Agent 主动推送消息。
 * 优先认服务端显式 proactive 标记；旧记录缺少该字段时，按“无同轮 USER 消息且无模型耗时/用量”识别。
 */
export function isProactiveTranscriptMessage(
  dto: TranscriptMessageDto,
  userTurnIds?: Set<string> | TranscriptMessageDto[],
): boolean {
  if (dto.role?.toUpperCase() !== 'ASSISTANT') return false;
  if (dto.proactive === true) return true;
  if (dto.proactive === false && !userTurnIds) return false;
  if (!dto.turnId || dto.status !== 'COMPLETED') return false;
  if ((dto.durationMillis ?? 0) !== 0 || dto.usage || dto.thinking) return false;
  if (dto.tools && dto.tools.length > 0) return false;
  if (!userTurnIds) return false;
  if (userTurnIds instanceof Set) {
    return !userTurnIds.has(dto.turnId);
  }
  return !userTurnIds.some((item) => item.role?.toUpperCase() === 'USER' && item.turnId === dto.turnId);
}

export function mapTranscriptToChatMessage(
  dto: TranscriptMessageDto,
  _index?: number,
  allMessages?: TranscriptMessageDto[],
  userTurnIds?: Set<string>,
): ChatMessage {
  const isUser = dto.role?.toUpperCase() === 'USER';
  const proactive = isUser ? false : isProactiveTranscriptMessage(dto, userTurnIds ?? allMessages);
  const parsedTime = dto.createdAt ? new Date(dto.createdAt).getTime() : NaN;
  const createdAt = Number.isFinite(parsedTime) && parsedTime > 0
    ? parsedTime
    : (proactive ? 0 : Date.now());
  return {
    id: dto.id,
    turnId: dto.turnId,
    role: isUser ? 'user' : 'assistant',
    modelName: isUser ? undefined : 'ButvanAgent',
    content: dto.content || '',
    reasoning: dto.thinking || undefined,
    createdAt,
    status: dto.status,
    usage: dto.usage,
    proactive: proactive || undefined,
    elapsedTime:
      dto.durationMillis != null
        ? Math.max(1, Math.round(dto.durationMillis / 1000))
        : undefined,
    tools:
      dto.tools && dto.tools.length > 0
        ? dto.tools.map((t) => ({
            toolCallId: t.toolCallId,
            toolName: t.toolName,
            command: t.command,
            output: t.output,
            status: (t.status ? t.status.toLowerCase() : 'completed') as
              | 'running'
              | 'completed'
              | 'failed'
              | 'cancelled',
          }))
        : undefined,
  };
}

/** 批量映射整段会话记录，一次建立用户轮次索引以 O(n) 识别历史主动推送消息。 */
export function mapTranscriptMessages(dtos: TranscriptMessageDto[]): ChatMessage[] {
  const userTurnIds = new Set<string>();
  for (const dto of dtos) {
    if (dto.role?.toUpperCase() === 'USER' && dto.turnId) {
      userTurnIds.add(dto.turnId);
    }
  }
  return dtos.map((dto, index) => mapTranscriptToChatMessage(dto, index, dtos, userTurnIds));
}
