import type { ComponentType, ReactNode } from 'react';
import type { SubagentProgressDto } from './team';

export type SessionKind = 'GENERAL' | 'PROJECT';
export type SessionStatus = 'ACTIVE' | 'DELETING';
export type MessageRole = 'USER' | 'ASSISTANT';
export type MessageStatus = 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type SessionPermissionMode = 'ASK' | 'AUTO_EDIT' | 'FULL_ACCESS';
export type UsageStatus = 'COMPLETE' | 'PARTIAL' | 'UNAVAILABLE';
export type UsagePurpose = 'CHAT' | 'SESSION_TITLE' | 'MODEL_CONNECTION_TEST' | 'PROFILE_MAINTENANCE' | 'CONTEXT_COMPACTION' | 'BACKGROUND_AGENT';

export interface InputTokenBreakdown {
  systemPromptTokens: number;
  historyTokens: number;
  currentUserTokens: number;
  toolSchemaTokens: number;
  toolResultTokens: number;
  profileContextTokens: number;
  memoryRecallTokens: number;
  ragContextTokens: number;
  otherTokens: number;
}

export interface ToolTokenUsage {
  toolName: string;
  schemaTokens: number;
  resultTokens: number;
}

export interface ModelInvocationUsage {
  invocationId: string;
  modelCallIndex: number;
  source: string;
  purpose: UsagePurpose;
  vendor: string;
  model: string;
  inputTokens?: number | null;
  outputTokens?: number | null;
  cachedInputTokens?: number | null;
  totalTokens?: number | null;
  durationMillis?: number | null;
  status: UsageStatus;
  tokenCounterId: string;
  estimatedInputTokens: number;
  estimationDeltaTokens?: number | null;
  breakdown: InputTokenBreakdown;
  toolUsages: ToolTokenUsage[];
}

export interface TurnTokenUsage {
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  totalTokens: number;
  modelCallCount: number;
  reportedCallCount: number;
  status: UsageStatus;
  calls: ModelInvocationUsage[];
  estimatedInputTokens: number;
  estimationDeltaTokens?: number | null;
  breakdown: InputTokenBreakdown;
  toolUsages: ToolTokenUsage[];
  durationMillis: number;
}

export interface TokenUsageSummary {
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  totalTokens: number;
  turnCount: number;
  trackedTurnCount: number;
  modelCallCount: number;
  reportedCallCount: number;
  status: UsageStatus;
}

/** Agent 响应时间线节点类型 */
export type TraceNodeType =
  | 'reasoning'
  | 'step'
  | 'search'
  | 'tool'
  | 'terminal'
  | 'diffs'
  | (string & {});

/** 结构化详情行 */
export interface DetailLine {
  text: string;
  tone?: 'add' | 'del' | 'ctx' | 'muted' | 'error';
}

/** 文件差异行 */
export interface DiffRow {
  old?: number | null;
  cur?: number | null;
  type: 'add' | 'del' | 'ctx';
  text: string;
}

/** Agent 响应时间线节点（思考、工具、终端、差异等） */
export interface TraceNode {
  id?: string;
  type: TraceNodeType;
  toolName?: string;
  sentences?: string[];
  durationSeconds?: number;
  primary?: string;
  secondary?: string;
  mono?: boolean;
  icon?: ComponentType<{ className?: string }> | ReactNode;
  iconClassName?: string;
  status?: 'pending' | 'running' | 'completed' | 'failed';
  args?: unknown;
  result?: unknown;
  command?: string;
  output?: string;
  exitCode?: number;
  durationMs?: number;
  add?: number;
  del?: number;
  diffRows?: DiffRow[];
  diffFile?: string;
  codeSnippet?: string;
  details?: DetailLine[];
  sources?: { name: string; url?: string }[];
  renderContent?: () => ReactNode;
}

/** 工具展示定义（标签、图标、格式化与自定义内容渲染） */
export interface ToolDefinition<TArgs = unknown, TResult = unknown> {
  name: string;
  label?: string | ((args: TArgs) => string);
  icon?: ComponentType<{ className?: string }> | ReactNode;
  iconClassName?: string;
  formatChip?: (args: TArgs, result?: TResult) => string;
  monoChip?: boolean;
  renderCustomContent?: (props: { args?: TArgs; result?: TResult; node: TraceNode }) => ReactNode;
}

export interface ToolExecution {
  toolCallId: string;
  toolName: string;
  command: string;
  status: 'running' | 'completed' | 'failed' | 'cancelled';
  output?: string;
}

export interface ChatMessage {
  id: string;
  turnId?: string;
  role: 'user' | 'assistant';
  content: string;
  reasoning?: string;
  modelName?: string;
  createdAt: number;
  startTime?: number;
  elapsedTime?: number;
  status?: MessageStatus;
  failureReason?: string;
  usage?: TurnTokenUsage | null;
  tools?: ToolExecution[];
  subagentProgress?: SubagentProgressDto[];
}

/** 服务端按当前用户读取业务数据，前端只提交受控分析意图。 */
export interface AgentAnalysisContextRequest {
  command: 'daily-review' | 'weekly-review' | 'todo-review'
    | 'finance-review' | 'study-review' | 'study-plan';
  argument: string;
  timezone: string;
  privacyConfirmed: boolean;
}

export interface Project {
  id: string;
  name: string;
  path: string;
  createdAt: number;
  availability: 'AVAILABLE' | 'MISSING' | 'INACCESSIBLE';
}

export interface ProjectDto {
  id: string;
  name: string;
  rootPath: string;
  importedAt: string;
  availability: Project['availability'];
}

export interface SessionSummaryDto {
  id: string;
  kind: SessionKind;
  projectId?: string | null;
  title: string;
  lastMessagePreview: string;
  createdAt: string;
  updatedAt: string;
  status: SessionStatus;
}

export interface TranscriptToolExecutionDto {
  toolCallId: string;
  toolName: string;
  command: string;
  output: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
}

export interface TranscriptMessageDto {
  id: string;
  turnId: string;
  role: MessageRole;
  content: string;
  createdAt: string;
  status: MessageStatus;
  durationMillis?: number | null;
  tools?: TranscriptToolExecutionDto[];
  thinking?: string | null;
  usage?: TurnTokenUsage | null;
}

export interface SessionDetailDto {
  summary: SessionSummaryDto;
  messages: TranscriptMessageDto[];
  usageSummary: TokenUsageSummary;
}

export interface ChatSession {
  id: string;
  kind: SessionKind;
  title: string;
  lastMessagePreview?: string;
  projectId?: string;
  createdAt: number;
  updatedAt: number;
  messages: ChatMessage[];
  usageSummary?: TokenUsageSummary;
  isLoaded?: boolean; // 消息记录是否已从后端详情接口中全量加载
}
