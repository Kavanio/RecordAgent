import type {
  AgentAnalysisContextRequest,
  SessionSummaryDto,
  SessionDetailDto,
  SessionKind,
  SessionPermissionMode,
} from '../types/chat';
import type { SubagentProgressDto } from '../types/team';
import { invoke } from '@tauri-apps/api/core';
import { waitForRequestDelay } from './requestDelay';

export interface ModelConfig {
  vendor: string;
  name: string;
  apiKey: string;
  temperature?: number;
  stream?: boolean;
}

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface AccountStatus {
  bound: boolean;
  maskedEmail: string | null;
  emailNotificationsEnabled: boolean;
  avatarVersion: string | null;
}

export interface BackendConnectionInfo {
  mode: 'dev' | 'sidecar' | 'remote';
  remoteUrl: string | null;
  hasApiToken: boolean;
}

/** 远程服务启动检查最多等待 8 秒，再切换到本机后端。 */
const REMOTE_STARTUP_TIMEOUT_MS = 8_000;

/** 临时弱网测试：开发客户端每次业务请求额外等待 1 秒；改为 0 即可关闭。生产包与 SSR 测试不启用。 */
const DEV_API_DELAY_MS = import.meta.env.DEV && !import.meta.env.SSR ? 1_000 : 0;

/** 后端地址：浏览器/非 Tauri 环境回退到开发地址，Tauri 环境由 Rust 端动态注入 */
let apiBaseUrl = 'http://localhost:8081';
let apiAccessToken: string | null = null;

/** 获取当前生效的后端 API 基础地址（供其它 service 复用，禁止在组件中直接访问）。 */
export function getApiBaseUrl(): string {
  return apiBaseUrl;
}

/** 判断当前是否运行在 Tauri 桌面端 */
function isTauri(): boolean {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;
}

/**
 * 初始化后端 API 地址
 *
 * 在 Tauri 环境轮询 Rust 端 `get_backend_port` 命令，等待后端 sidecar
 * 健康检查通过后写入动态端口；非 Tauri 环境保持开发地址不变。
 * 应在应用渲染前调用一次。
 */
export async function initApiBaseUrl(options: { reuseEstablishedConnection?: boolean } = {}): Promise<void> {
  if (!isTauri()) return;

  // 开发模式：后端由开发者在本机（如 IDEA）启动，直接连固定开发端口
  const mode = await invoke<string>('get_backend_mode');
  if (mode === 'remote') {
    const connection = await invoke<BackendConnectionInfo>('get_backend_connection');
    if (!connection.remoteUrl) throw new Error('远程后端地址未配置');
    apiAccessToken = await invoke<string | null>('get_backend_api_token').catch(() => null);
    // 辅助窗口只会在主窗口已连接并准备使用时创建；复用主窗口选定的地址，避免再次等待 8 秒健康检查。
    if (options.reuseEstablishedConnection) {
      apiBaseUrl = connection.remoteUrl;
      return;
    }
    try {
      const headers = new Headers();
      if (apiAccessToken) headers.set('Authorization', `Bearer ${apiAccessToken}`);
      const response = await fetch(`${connection.remoteUrl}/api/health`, {
        headers,
        signal: AbortSignal.timeout(REMOTE_STARTUP_TIMEOUT_MS),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const payload = await response.json() as ApiResponse<string>;
      if (payload.code !== 200 || payload.data !== 'ok') throw new Error('健康检查响应无效');
      apiBaseUrl = connection.remoteUrl;
      return;
    } catch (error) {
      console.warn('远程后端在 8 秒内未通过健康检查，切换到本机后端：', error);
      await invoke('start_local_backend_fallback');
      apiAccessToken = null;
      if (await invoke<string>('get_backend_mode') === 'dev') {
        apiBaseUrl = 'http://localhost:8081';
        return;
      }
    }
  }
  if (mode === 'dev') return;

  // sidecar 首次启动或远程降级后，最多等待 60 秒让本机后端就绪。
  for (let i = 0; i < 120; i++) {
    try {
      const port = await invoke<number | null>('get_backend_port');
      if (port) {
        apiBaseUrl = `http://127.0.0.1:${port}`;
        return;
      }
    } catch {
      // 命令尚未就绪，继续轮询
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

/** 读取本机保存的远程后端设置。 */
export async function fetchBackendConnection(): Promise<BackendConnectionInfo> {
  return invoke<BackendConnectionInfo>('get_backend_connection');
}

/** 测试远程服务健康状态；仅验证连接和 HTTP 健康接口。 */
export async function testBackendConnection(url: string, token?: string | null): Promise<void> {
  const headers = new Headers();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  const response = await fetch(`${url.trim().replace(/\/+$/, '')}/api/health`, { headers, signal: AbortSignal.timeout(5000) });
  if (response.status === 401) throw new Error('API Token 不匹配，请检查服务器配置或重新输入 Token');
  if (!response.ok) throw new Error(`后端健康检查失败（HTTP ${response.status}），请检查地址与跨域设置`);
  const payload = await response.json() as ApiResponse<string>;
  if (payload.code !== 200 || payload.data !== 'ok') throw new Error('健康检查响应格式不正确');
}

/** 保存远程地址；变更需重启，确保本机 sidecar 与远程调度互斥。 */
export async function saveBackendConnection(url: string | null, token: string | null): Promise<void> {
  await invoke('save_remote_backend_url', { remoteUrl: url, apiToken: token });
}

/** 统一注入远程 Bearer Token，并在开发客户端模拟请求延迟；本机模式不附加认证头。 */
export async function apiFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers ?? (input instanceof Request ? input.headers : undefined));
  const requestUrl = input instanceof Request ? input.url : String(input);
  if (apiAccessToken) {
    try {
      if (new URL(requestUrl, apiBaseUrl).origin === new URL(apiBaseUrl).origin) {
        headers.set('Authorization', `Bearer ${apiAccessToken}`);
      }
    } catch {
      // 非 URL 请求交由浏览器 Fetch API 返回标准错误。
    }
  }
  if (DEV_API_DELAY_MS > 0) {
    const signal = init.signal ?? (input instanceof Request ? input.signal : undefined);
    await waitForRequestDelay(DEV_API_DELAY_MS, signal);
  }
  return fetch(input, { ...init, headers });
}

/** EventSource 不支持自定义请求头，令牌仅用于受保护的 SSE 订阅。 */
export function apiEventSourceUrl(path: string): string {
  const url = new URL(path, apiBaseUrl);
  if (apiAccessToken) url.searchParams.set('access_token', apiAccessToken);
  return url.toString();
}

/** 原生 img/download 无法设置 Authorization 头，返回仅用于受保护只读 GET 的访问地址。 */
export function apiResourceUrl(path: string): string {
  const url = new URL(path, apiBaseUrl);
  if (apiAccessToken) url.searchParams.set('access_token', apiAccessToken);
  return url.toString();
}

/**
 * 获取当前模型配置
 */
export async function fetchModelConfig(): Promise<ModelConfig | null> {
  try {
    const res = await apiFetch(`${apiBaseUrl}/agent/model/config`, { signal: AbortSignal.timeout(5_000) });
    if (!res.ok) return null;
    const json: ApiResponse<ModelConfig> = await res.json();
    return json.data;
  } catch (err) {
    console.error('获取模型配置失败:', err);
    return null;
  }
}

/**
 * 获取全量多厂商模型配置
 */
export async function fetchFullModelConfig(): Promise<any | null> {
  try {
    const res = await apiFetch(`${apiBaseUrl}/agent/model/full-config`);
    if (!res.ok) return null;
    const json: ApiResponse<any> = await res.json();
    return json.data;
  } catch (err) {
    console.error('获取全量模型配置失败:', err);
    return null;
  }
}

/**
 * 保存全量多厂商模型配置
 */
export async function saveFullModelConfig(fullConfig: any): Promise<{ success: boolean; message: string }> {
  try {
    const res = await apiFetch(`${apiBaseUrl}/agent/model/full-config`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(fullConfig),
    });
    const json: ApiResponse<string> = await res.json();
    if (res.ok && json.code === 200) {
      return { success: true, message: json.data || '全量模型配置保存成功！' };
    }
    return { success: false, message: json.message || '保存失败' };
  } catch (err: any) {
    return { success: false, message: err.message || '网络无法连接' };
  }
}

/**
 * 获取系统支持的供应商列表（从后端 yml 中拉取）
 */
export async function fetchSupportedVendors(): Promise<string[]> {
  try {
    const res = await apiFetch(`${apiBaseUrl}/agent/model/vendors`, { signal: AbortSignal.timeout(5_000) });
    if (!res.ok) return ['gemini', 'openai', 'dashscope', 'deepseek', 'anthropic', 'ollama'];
    const json: ApiResponse<string[]> = await res.json();
    return json.data || [];
  } catch (err) {
    console.error('获取供应商列表失败:', err);
    return ['gemini', 'openai', 'dashscope', 'deepseek', 'anthropic', 'ollama'];
  }
}

/**
 * 更新保存模型配置
 */
export async function saveModelConfig(params: {
  vendor: string;
  modelName: string;
  apiKey: string;
}): Promise<{ success: boolean; message: string }> {
  try {
    const res = await apiFetch(`${apiBaseUrl}/agent/model`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(params),
    });
    const json: ApiResponse<string> = await res.json();
    if (res.ok && json.code === 200) {
      return { success: true, message: json.data || '更新模型配置成功！' };
    }
    return { success: false, message: json.message || '更新配置失败' };
  } catch (err: any) {
    const errMsg = err?.message === 'Load failed' || err?.message === 'Failed to fetch'
      ? '后端网络请求被拒绝：请确认后端服务已启动且支持跨域访问'
      : (err.message || '无法连接至后端服务');
    return { success: false, message: errMsg };
  }
}

/** 获取当前设备的邮箱绑定状态。 */
export async function fetchAccountStatus(): Promise<AccountStatus | null> {
  try {
    const response = await apiFetch(`${apiBaseUrl}/agent/account/status`);
    if (!response.ok) return null;
    const json: ApiResponse<AccountStatus> = await response.json();
    return json.code === 200 ? json.data : null;
  } catch {
    return null;
  }
}

/** 返回带版本参数的头像地址，确保更换头像后不会命中旧缓存。 */
export function getAccountAvatarUrl(avatarVersion: string): string {
  return apiResourceUrl(`/agent/account/avatar/content?v=${encodeURIComponent(avatarVersion)}`);
}

/** 上传并替换当前设备用户的头像。 */
export async function uploadAccountAvatar(file: File): Promise<AccountStatus> {
  const body = new FormData();
  body.append('file', file);
  const response = await apiFetch(`${apiBaseUrl}/agent/account/avatar`, { method: 'POST', body });
  const json: ApiResponse<AccountStatus> = await response.json();
  if (!response.ok || json.code !== 200) throw new Error(json.message || '头像上传失败');
  return json.data;
}

async function accountRequest<T>(path: string, body: Record<string, string>): Promise<{ success: boolean; message: string; data?: T }> {
  try {
    const response = await apiFetch(`${apiBaseUrl}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const json: ApiResponse<T> = await response.json();
    return json.code === 200
      ? { success: true, message: json.message || '操作成功', data: json.data }
      : { success: false, message: json.message || '操作失败' };
  } catch {
    return { success: false, message: '无法连接后端服务，请稍后重试' };
  }
}

/** 发送邮箱绑定验证码。 */
export async function sendEmailVerificationCode(email: string) {
  return accountRequest<string>('/agent/account/verification-code', { email });
}

/** 校验验证码并将邮箱绑定到当前设备。 */
export async function bindEmailAccount(params: { email: string; password: string; verificationCode: string }) {
  return accountRequest<AccountStatus>('/agent/account/bind', params);
}

export interface ToolCallPayload {
  toolCallId?: string;
  toolName?: string;
  command?: string;
}

export interface ToolResultPayload {
  toolCallId?: string;
  toolName?: string;
  result?: string;
  status?: 'running' | 'completed' | 'failed' | 'cancelled';
}

/** Jev 路由失败后由后端发送的非终态降级提示。 */
export interface RoutingNoticePayload {
  code: string;
  message: string;
  requestId: string;
  retryable: boolean;
  retryAfterMillis: number | null;
}

/** 后端要求用户确认时返回的高风险工具。 */
export interface PermissionToolPayload {
  toolCallId: string;
  toolName: string;
  input: Record<string, unknown>;
  riskDescription: string;
  index: number;
  total: number;
}

export interface PermissionRequiredPayload {
  approvalId: string;
  runId: string;
  turnId: string;
  tools: PermissionToolPayload[];
}

export interface PendingApprovalPayload extends PermissionRequiredPayload {
  sessionId: string;
  partialContent: string;
  startedAt: string;
  readyToResume: boolean;
}

export interface PermissionDecisionInput {
  toolCallId: string;
  approved: boolean;
  rememberForSession: boolean;
}

export interface PermissionDecisionResponse {
  readyToResume: boolean;
  nextTool: PermissionToolPayload | null;
}

function isSubagentProgressDto(value: unknown): value is SubagentProgressDto {
  if (typeof value !== 'object' || value === null) return false;
  const event = value as Record<string, unknown>;
  return (
    typeof event.source === 'string' &&
    typeof event.agentId === 'string' &&
    typeof event.content === 'string' &&
    (event.eventType === 'start' || event.eventType === 'text' || event.eventType === 'tool' || event.eventType === 'end')
  );
}

/** 原子提交同一审批批次的全部工具授权决定。 */
export async function submitPermissionDecisions(params: {
  sessionId: string;
  approvalId: string;
  decisions: PermissionDecisionInput[];
}): Promise<PermissionDecisionResponse> {
  const response = await apiFetch(`${apiBaseUrl}/agent/chat/permission/decisions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
  if (!response.ok) {
    throw new Error(`提交权限决定失败：HTTP ${response.status}`);
  }
  return response.json() as Promise<PermissionDecisionResponse>;
}

/** 查询会话当前待处理审批，供页面刷新或重新切换会话后恢复。 */
export async function fetchPendingPermission(
  sessionId: string,
): Promise<PendingApprovalPayload | null> {
  const response = await apiFetch(`${apiBaseUrl}/agent/chat/${sessionId}/permission/pending`);
  if (response.status === 204) return null;
  if (!response.ok) throw new Error(`读取待审批操作失败：HTTP ${response.status}`);
  return response.json() as Promise<PendingApprovalPayload>;
}

/**
 * Agent 对话流式 SSE 交互函数
 * 利用 fetch + ReadableStream 实时解析后端推流
 */
export async function streamAgentChat(
  params: {
    runId: string;
    sessionId: string;
    content?: string;
    context?: string;
    recordReferenceIds?: string[];
    analysisContext?: AgentAnalysisContextRequest;
    approvalId?: string;
    signal?: AbortSignal;
  },
  onChunk: (text: string) => void,
  onComplete?: () => void,
  onError?: (error: Error) => void,
  onToolCall?: (payload: ToolCallPayload) => void,
  onToolResult?: (payload: ToolResultPayload) => void,
  onThinking?: (thinkingText: string) => void,
  onPermissionRequired?: (payload: PermissionRequiredPayload) => void,
  onSubagentProgress?: (payload: SubagentProgressDto) => void,
  onCancelled?: () => void,
  onRoutingNotice?: (payload: RoutingNoticePayload) => void,
): Promise<void> {
  try {
    const payloadContent = params.content || params.context || '';
    const modelContext = params.context || params.content || '';
    const isResume = Boolean(params.approvalId);
    const response = await apiFetch(
      `${apiBaseUrl}/agent/chat${isResume ? '/permission/resume' : '/stream'}`,
      {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
        signal: params.signal,
        body: JSON.stringify(isResume
          ? { sessionId: params.sessionId, approvalId: params.approvalId, runId: params.runId }
          : {
              runId: params.runId,
              sessionId: params.sessionId,
              context: modelContext,
              content: payloadContent,
              recordReferenceIds: params.recordReferenceIds ?? [],
              analysisContext: params.analysisContext,
            }),
      }
    );

    if (!response.ok) {
      throw new Error(`HTTP 响应异常: Status ${response.status}`);
    }

    if (!response.body) {
      throw new Error('ReadableStream 不可用');
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    let streamFinished = false;

    const dispatchSseEvent = (eventBlock: string) => {
      let eventName = 'message';
      const dataLines: string[] = [];

      for (const rawLine of eventBlock.split(/\r?\n/)) {
        if (rawLine.startsWith('event:')) {
          eventName = rawLine.substring(6).trim();
        } else if (rawLine.startsWith('data:')) {
          dataLines.push(rawLine.substring(5).trimStart());
        }
      }

      const dataStr = dataLines.join('\n');
      if (eventName === 'text' || eventName === 'message') {
        if (dataStr) onChunk(dataStr);
      } else if (eventName === 'thinking') {
        if (dataStr) onThinking?.(dataStr);
      } else if (eventName === 'tool_call') {
        try {
          const payload: ToolCallPayload = JSON.parse(dataStr);
          onToolCall?.(payload);
        } catch {
          onToolCall?.({ toolName: 'tool', command: dataStr });
        }
      } else if (eventName === 'tool_result') {
        try {
          const payload: ToolResultPayload = JSON.parse(dataStr);
          onToolResult?.(payload);
        } catch {
          onToolResult?.({ toolName: 'tool', result: dataStr });
        }
      } else if (eventName === 'permission_required') {
        streamFinished = true;
        try {
          onPermissionRequired?.(JSON.parse(dataStr) as PermissionRequiredPayload);
        } catch {
          onError?.(new Error('权限确认事件格式错误'));
        }
      } else if (eventName === 'subagent') {
        try {
          const payload: unknown = JSON.parse(dataStr);
          if (!isSubagentProgressDto(payload)) throw new Error('invalid payload');
          onSubagentProgress?.(payload);
        } catch {
          onError?.(new Error('子 Agent 进度事件格式错误'));
        }
      } else if (eventName === 'routing_notice') {
        try {
          const payload = JSON.parse(dataStr) as RoutingNoticePayload;
          if (typeof payload.code !== 'string' || typeof payload.message !== 'string') {
            throw new Error('invalid payload');
          }
          onRoutingNotice?.({
            code: payload.code,
            message: payload.message,
            requestId: typeof payload.requestId === 'string' ? payload.requestId : '',
            retryable: payload.retryable === true,
            retryAfterMillis: typeof payload.retryAfterMillis === 'number'
              ? payload.retryAfterMillis
              : null,
          });
        } catch {
          console.warn('Jev 路由提示事件格式错误');
        }
      } else if (eventName === 'error') {
        streamFinished = true;
        onError?.(new Error(dataStr || 'Agent 流式处理失败'));
      } else if (eventName === 'cancelled') {
        streamFinished = true;
        onCancelled?.();
      } else if (eventName === 'done') {
        streamFinished = true;
        onComplete?.();
      }
    };

    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        buffer += decoder.decode();
        if (buffer.trim()) dispatchSseEvent(buffer);
        if (!streamFinished) {
          onError?.(new Error('Agent 流连接意外结束，未收到终态，请重试。'));
        }
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      const eventBlocks = buffer.split(/\r?\n\r?\n/);
      buffer = eventBlocks.pop() || ''; // 保留尚未接收完整的 SSE 事件。

      for (const eventBlock of eventBlocks) {
        if (eventBlock.trim()) dispatchSseEvent(eventBlock);
      }
    }
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') return;
    console.error('SSE 流数据解析失败:', error);
    onError?.(error instanceof Error ? error : new Error('SSE 流数据解析失败'));
  }
}

export interface AgentRunCancelResponse {
  runId: string;
  accepted: boolean;
  status: 'CANCELLING' | 'NOT_FOUND';
}

/** 请求后端停止精确的 Agent run；最终结果以 SSE 终态为准。 */
export async function cancelAgentChatRun(
  sessionId: string,
  runId: string,
): Promise<AgentRunCancelResponse> {
  const response = await apiFetch(`${apiBaseUrl}/agent/chat/runs/${encodeURIComponent(runId)}/cancel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId }),
  });
  if (!response.ok) throw new Error(`停止请求失败（${response.status}）`);
  return response.json() as Promise<AgentRunCancelResponse>;
}

/**
 * 获取后端会话摘要列表
 */
export async function fetchSessions(): Promise<SessionSummaryDto[]> {
  try {
    const res = await apiFetch(`${apiBaseUrl}/agent/sessions`);
    if (!res.ok) return [];
    const json: ApiResponse<SessionSummaryDto[]> = await res.json();
    return json.code === 200 && Array.isArray(json.data) ? json.data : [];
  } catch (err) {
    console.error('获取后端会话列表失败:', err);
    return [];
  }
}

/**
 * 获取会话详情（包含完整消息记录列表）
 */
export async function fetchSessionDetail(sessionId: string): Promise<SessionDetailDto | null> {
  try {
    const res = await apiFetch(`${apiBaseUrl}/agent/sessions/${sessionId}`);
    if (!res.ok) return null;
    const json: ApiResponse<SessionDetailDto> = await res.json();
    return json.code === 200 ? json.data : null;
  } catch (err) {
    console.error('获取会话详情失败:', err);
    return null;
  }
}

/**
 * 创建新会话（ID 由后端生成 UUID）
 */
export async function createSessionApi(params?: {
  kind?: SessionKind;
  title?: string;
  projectId?: string;
}): Promise<{ success: boolean; data?: SessionSummaryDto; message?: string }> {
  try {
    const res = await apiFetch(`${apiBaseUrl}/agent/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        kind: params?.kind || 'GENERAL',
        title: params?.title || '新对话',
        projectId: params?.projectId,
      }),
    });
    const json: ApiResponse<SessionSummaryDto> = await res.json();
    if (res.ok && json.code === 200) {
      return { success: true, data: json.data };
    }
    return { success: false, message: json.message || '创建会话失败' };
  } catch (err: any) {
    console.error('创建会话 API 调用失败:', err);
    return { success: false, message: err?.message || '网络连接失败' };
  }
}

/**
 * 修改会话标题 (PATCH /agent/sessions/{sessionId})
 */
export async function updateSessionTitleApi(
  sessionId: string,
  title: string
): Promise<{ success: boolean; data?: SessionSummaryDto; message?: string }> {
  try {
    const res = await apiFetch(`${apiBaseUrl}/agent/sessions/${sessionId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title }),
    });
    const json: ApiResponse<SessionSummaryDto> = await res.json();
    if (res.ok && json.code === 200) {
      return { success: true, data: json.data };
    }
    return { success: false, message: json.message || '更新标题失败' };
  } catch (err: any) {
    console.error('更新会话标题 API 调用失败:', err);
    return { success: false, message: err?.message || '网络连接失败' };
  }
}

/** 根据首个用户问题为仍使用占位名称的会话生成标题。 */
export async function generateSessionTitle(sessionId: string): Promise<SessionSummaryDto | null> {
  try {
    const response = await apiFetch(`${apiBaseUrl}/agent/sessions/${sessionId}/title/generate`, {
      method: 'POST',
    });
    if (!response.ok) return null;
    const json: ApiResponse<SessionSummaryDto> = await response.json();
    return json.code === 200 ? json.data : null;
  } catch (error) {
    console.error('生成会话标题失败:', error);
    return null;
  }
}

export interface PermissionModeResponse {
  mode: SessionPermissionMode;
}

/** 获取当前会话持久化的权限模式。 */
export async function fetchSessionPermissionMode(sessionId: string): Promise<SessionPermissionMode> {
  const response = await apiFetch(`${apiBaseUrl}/agent/sessions/${sessionId}/permission-mode`);
  if (!response.ok) throw new Error(`读取权限模式失败：HTTP ${response.status}`);
  const json: ApiResponse<PermissionModeResponse> = await response.json();
  if (json.code !== 200 || !json.data?.mode) throw new Error(json.message || '读取权限模式失败');
  return json.data.mode;
}

/** 修改并持久化当前会话权限模式。 */
export async function updateSessionPermissionMode(
  sessionId: string,
  mode: SessionPermissionMode,
): Promise<SessionPermissionMode> {
  const response = await apiFetch(`${apiBaseUrl}/agent/sessions/${sessionId}/permission-mode`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode }),
  });
  if (!response.ok) throw new Error(`修改权限模式失败：HTTP ${response.status}`);
  const json: ApiResponse<PermissionModeResponse> = await response.json();
  if (json.code !== 200 || !json.data?.mode) throw new Error(json.message || '修改权限模式失败');
  return json.data.mode;
}

/**
 * 删除会话 (DELETE /agent/sessions/{sessionId})
 */
export async function deleteSessionApi(sessionId: string): Promise<{ success: boolean; message?: string }> {
  try {
    const res = await apiFetch(`${apiBaseUrl}/agent/sessions/${sessionId}`, {
      method: 'DELETE',
    });
    const json: ApiResponse<void> = await res.json();
    if (res.ok && json.code === 200) {
      return { success: true };
    }
    return { success: false, message: json.message || '删除会话失败' };
  } catch (err: any) {
    console.error('删除会话 API 调用失败:', err);
    return { success: false, message: err?.message || '网络连接失败' };
  }
}

/** 读取当前会话的任务计划书（不存在时返回 null）。 */
export async function fetchPlan(sessionId: string): Promise<string | null> {
  try {
    const res = await apiFetch(`${apiBaseUrl}/agent/chat/${sessionId}/plan`);
    if (!res.ok) return null;
    const json = (await res.json()) as { content?: string };
    return json.content ?? null;
  } catch (err) {
    console.error('读取计划书失败:', err);
    return null;
  }
}
