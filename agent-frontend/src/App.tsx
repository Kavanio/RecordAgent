import { AutomationProvider } from './context/AutomationContext';
import { TaskPage } from './components/task/TaskPage';
import { TaskReminderWindow } from './components/task/TaskReminderWindow';
import type { RecordEntry, RecordType } from './types/record';
import React, { useState, useEffect, useCallback, useRef } from 'react';
import { ModelProviderContext } from './context/ModelContext';
import { StudyRealtimeProvider } from './context/StudyRealtimeContext';
import { Sidebar } from './components/layout/Sidebar';
import { ChatWorkspace } from './components/chat/ChatWorkspace';
import { CalendarView } from './components/calendar/CalendarView';
import { FinancePage } from './components/finance/FinancePage';
import { RecordPage } from './components/record/RecordPage';
import { StudyPage } from './components/study/StudyPage';
import { StudyWindowLayer } from './components/study/StudyWindowLayer';
import { SystemStudyWindow } from './components/study/SystemStudyWindow';
import { ModelSettingsPage } from './components/model/ModelSettingsPage';
import { ModelInitPage } from './components/model/ModelInitPage';
import { MessageProvider } from './components/common/Message';
import { useMessage } from './components/common/Message';
import { LoadingTree } from './components/common/LoadingTree';
import {
  fetchModelConfig,
  fetchSupportedVendors,
  streamAgentChat,
  fetchSessions,
  fetchSessionDetail,
  createSessionApi,
  updateSessionTitleApi,
  deleteSessionApi,
  submitPermissionDecisions,
  fetchPendingPermission,
  generateSessionTitle,
  fetchSessionPermissionMode,
  updateSessionPermissionMode,
  cancelAgentChatRun,
} from './services/api';
import type { PermissionDecisionInput, PermissionToolPayload } from './services/api';
import type {
  AgentAnalysisContextRequest,
  ChatSession,
  ChatMessage,
  Project,
  SessionSummaryDto,
  TranscriptMessageDto,
  SessionPermissionMode,
} from './types/chat';
import type { SubagentProgressDto, TaskDto } from './types/team';
import {
  cancelSubagentTask,
  fetchSubagentTasks,
  subscribeSubagentTaskEvents,
} from './services/taskApi';
import { fetchProjects, importProject, removeProject as removeProjectApi } from './services/projectApi';

function mapTranscriptToChatMessage(dto: TranscriptMessageDto): ChatMessage {
  const isUser = dto.role?.toUpperCase() === 'USER';
  return {
    id: dto.id,
    turnId: dto.turnId,
    role: isUser ? 'user' : 'assistant',
    modelName: isUser ? undefined : 'ButvanAgent',
    content: dto.content || '',
    reasoning: dto.thinking || undefined,
    createdAt: new Date(dto.createdAt).getTime() || Date.now(),
    status: dto.status,
    usage: dto.usage,
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

interface ActiveChatRun {
  runId: string;
  assistantMessageId: string;
  controller: AbortController;
}

interface PendingPermissionState {
  sessionId: string;
  assistantMessageId: string;
  approvalId: string;
  runId: string;
  tools: PermissionToolPayload[];
}

function markAssistantTerminal(
  message: ChatMessage,
  status: 'FAILED' | 'CANCELLED',
  failureReason?: string,
): ChatMessage {
  const toolStatus = status === 'CANCELLED' ? 'cancelled' as const : 'failed' as const;
  return {
    ...message,
    status,
    failureReason: status === 'FAILED' ? failureReason : undefined,
    elapsedTime: Math.max(
      1,
      Math.floor((Date.now() - (message.startTime || message.createdAt)) / 1000),
    ),
    tools: message.tools?.map((tool) => tool.status === 'running'
      ? { ...tool, status: toolStatus }
      : tool),
  };
}

function createRunId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `run_${Date.now()}_${Math.random().toString(36).slice(2)}`;
}

export const MainLayout: React.FC<{
  isSettingsOpen: boolean;
  setIsSettingsOpen: (open: boolean) => void;
  settingsTab: string;
  setSettingsTab: (tab: string) => void;
}> = ({ isSettingsOpen, setIsSettingsOpen, settingsTab, setSettingsTab }) => {
  const { showMessage } = useMessage();
  const [projects, setProjects] = useState<Project[]>([]);
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string>('');
  const [recordInitialType, setRecordInitialType] = useState<RecordType>('quick');
  const [recordTarget, setRecordTarget] = useState<RecordEntry | null | undefined>(undefined);
  const [activeFeature, setActiveFeature] = useState<'chat' | 'calendar' | 'finance' | 'record' | 'study' | 'task'>('chat');
  const [pendingPermissions, setPendingPermissions] = useState<Record<string, PendingPermissionState>>({});
  const [isPermissionSubmitting, setIsPermissionSubmitting] = useState(false);
  const [subagentTasks, setSubagentTasks] = useState<TaskDto[]>([]);
  const [isSubagentTasksLoading, setIsSubagentTasksLoading] = useState(false);
  const [subagentTaskError, setSubagentTaskError] = useState<string | null>(null);
  const [cancellingTaskId, setCancellingTaskId] = useState<string | null>(null);
  const [permissionModes, setPermissionModes] = useState<Record<string, SessionPermissionMode>>({});
  const [savingPermissionSessionId, setSavingPermissionSessionId] = useState<string | null>(null);
  const [streamingSessionIds, setStreamingSessionIds] = useState<Set<string>>(() => new Set());
  const [stoppingSessionIds, setStoppingSessionIds] = useState<Set<string>>(() => new Set());
  const activeChatRunsRef = useRef<Map<string, ActiveChatRun>>(new Map());
  const lastRoutingNoticeRef = useRef<{ code: string; shownAt: number } | null>(null);
  const [sessionLoadErrors, setSessionLoadErrors] = useState<Record<string, string>>({});
  const activeSessionIdRef = useRef(activeSessionId);

  useEffect(() => {
    activeSessionIdRef.current = activeSessionId;
  }, [activeSessionId]);

  const setSessionStreaming = useCallback((sessionId: string, streaming: boolean) => {
    setStreamingSessionIds((previous) => {
      const next = new Set(previous);
      if (streaming) next.add(sessionId);
      else next.delete(sessionId);
      return next;
    });
  }, []);

  const beginChatRun = useCallback((
    sessionId: string,
    runId: string,
    assistantMessageId: string,
  ) => {
    const controller = new AbortController();
    activeChatRunsRef.current.set(sessionId, { runId, assistantMessageId, controller });
    setSessionStreaming(sessionId, true);
    return controller;
  }, [setSessionStreaming]);

  const finishChatRun = useCallback((sessionId: string, runId: string) => {
    if (activeChatRunsRef.current.get(sessionId)?.runId !== runId) return false;
    activeChatRunsRef.current.delete(sessionId);
    setSessionStreaming(sessionId, false);
    setStoppingSessionIds((previous) => {
      const next = new Set(previous);
      next.delete(sessionId);
      return next;
    });
    return true;
  }, [setSessionStreaming]);

  useEffect(() => () => {
    activeChatRunsRef.current.forEach((run) => run.controller.abort());
    activeChatRunsRef.current.clear();
  }, []);

  const syncSessionDetail = useCallback(async (sessionId: string, generateTitle = false) => {
    setSessionLoadErrors((previous) => {
      if (!previous[sessionId]) return previous;
      const next = { ...previous };
      delete next[sessionId];
      return next;
    });
    const detail = await fetchSessionDetail(sessionId);
    if (!detail) {
      setSessionLoadErrors((previous) => ({
        ...previous,
        [sessionId]: '聊天记录读取失败，请检查网络连接后重试。',
      }));
      return false;
    }

    // 立即刷新消息与 Token 用量（毫秒级响应，避免被标题生成阻塞）
    const messages = detail.messages.map(mapTranscriptToChatMessage);
    setSessions((previous) => previous.map((session) => session.id === sessionId
      ? {
          ...session,
          title: detail.summary.title,
          lastMessagePreview: detail.summary.lastMessagePreview,
          messages,
          usageSummary: detail.usageSummary,
          isLoaded: true,
        }
      : session));

    // 若当前会话为“新对话”，在后台异步生成标题并局部更新，不阻塞界面的 Token 和消息终态呈现
    const hasUserMessage = detail.messages.some((message) => message.role === 'USER');
    if (detail.summary.title === '新对话' && (generateTitle || hasUserMessage)) {
      void (async () => {
        try {
          await generateSessionTitle(sessionId);
          const updatedDetail = await fetchSessionDetail(sessionId);
          if (updatedDetail) {
            setSessions((previous) => previous.map((session) => session.id === sessionId
              ? {
                  ...session,
                  title: updatedDetail.summary.title,
                  lastMessagePreview: updatedDetail.summary.lastMessagePreview,
                }
              : session));
          }
        } catch (error) {
          console.error('异步生成会话标题失败:', error);
        }
      })();
    }
    return true;
  }, []);

  const refreshSubagentTasks = useCallback(async (sessionId = activeSessionId) => {
    if (!sessionId) {
      setSubagentTasks([]);
      return;
    }

    setIsSubagentTasksLoading(true);
    try {
      const tasks = await fetchSubagentTasks(sessionId);
      if (sessionId !== activeSessionIdRef.current) return;
      setSubagentTasks(tasks);
      setSubagentTaskError(null);
    } catch (error) {
      if (sessionId !== activeSessionIdRef.current) return;
      setSubagentTaskError(error instanceof Error ? error.message : '读取子 Agent 任务失败，请稍后重试');
    } finally {
      if (sessionId === activeSessionIdRef.current) setIsSubagentTasksLoading(false);
    }
  }, [activeSessionId]);

  useEffect(() => {
    if (!activeSessionId) {
      setSubagentTasks([]);
      setIsSubagentTasksLoading(false);
      return;
    }

    const sessionId = activeSessionId;
    setSubagentTasks([]);
    setSubagentTaskError(null);
    setIsSubagentTasksLoading(true);
    return subscribeSubagentTaskEvents(sessionId, {
      onSnapshot: (tasks) => {
        if (sessionId !== activeSessionIdRef.current) return;
        setSubagentTasks(tasks);
        setSubagentTaskError(null);
        setIsSubagentTasksLoading(false);
      },
      onTask: (task) => {
        if (sessionId !== activeSessionIdRef.current) return;
        setSubagentTasks((previous) => {
          const index = previous.findIndex((current) => current.taskId === task.taskId);
          if (index < 0) return [...previous, task];
          const next = [...previous];
          next[index] = task;
          return next;
        });
        setSubagentTaskError(null);
        setIsSubagentTasksLoading(false);
      },
      onError: () => {
        if (sessionId !== activeSessionIdRef.current) return;
        setIsSubagentTasksLoading(false);
        setSubagentTaskError('子 Agent 任务实时连接暂时中断，正在自动重连。');
      },
    });
  }, [activeSessionId]);

  const handleCancelSubagentTask = async (taskId: string) => {
    if (!activeSessionId || cancellingTaskId) return;
    setCancellingTaskId(taskId);
    try {
      await cancelSubagentTask(activeSessionId, taskId);
    } catch (error) {
      setSubagentTaskError(error instanceof Error ? error.message : '取消子 Agent 任务失败，请稍后重试');
    } finally {
      setCancellingTaskId(null);
    }
  };

  const appendSubagentProgress = (
    sessionId: string,
    messageId: string,
    progress: SubagentProgressDto,
  ) => {
    setSessions((previous) => previous.map((session) => session.id === sessionId
      ? {
          ...session,
          messages: session.messages.map((message) => message.id === messageId
            ? { ...message, subagentProgress: [...(message.subagentProgress || []), progress] }
            : message),
        }
      : session));
  };

  // 1. 初始化从后端 API 获取会话列表数据
  useEffect(() => {
    fetchProjects()
      .then(setProjects)
      .catch((error) => showMessage('error', error instanceof Error ? error.message : '读取项目列表失败'));

    fetchSessions().then(async (data: SessionSummaryDto[]) => {
      if (Array.isArray(data) && data.length > 0) {
        const initialSessions: ChatSession[] = data.map((dto) => ({
          id: dto.id,
          kind: dto.kind,
          projectId: dto.projectId ?? undefined,
          title: dto.title,
          lastMessagePreview: dto.lastMessagePreview,
          createdAt: new Date(dto.createdAt).getTime() || Date.now(),
          updatedAt: new Date(dto.updatedAt).getTime() || Date.now(),
          messages: [],
          isLoaded: false,
        }));
        setSessions(initialSessions);
        setActiveSessionId(initialSessions[0].id);
      } else {
        // 若无会话，由后端 API 创建一个初始化会话
        const res = await createSessionApi({ kind: 'GENERAL', title: '新对话' });
        if (res.success && res.data) {
          const created: ChatSession = {
            id: res.data.id,
            kind: res.data.kind,
            projectId: res.data.projectId ?? undefined,
            title: res.data.title,
            lastMessagePreview: res.data.lastMessagePreview,
            createdAt: new Date(res.data.createdAt).getTime() || Date.now(),
            updatedAt: new Date(res.data.updatedAt).getTime() || Date.now(),
            messages: [],
            isLoaded: true,
          };
          setSessions([created]);
          setActiveSessionId(created.id);
        }
      }
    });
  }, [showMessage]);

  // 2. 切换当前激活会话时，若消息未加载，从后端 fetchSessionDetail 获取完整聊天记录
  useEffect(() => {
    if (!activeSessionId) return;

    const currentSession = sessions.find((s) => s.id === activeSessionId);
    if (currentSession && !currentSession.isLoaded) {
      void syncSessionDetail(activeSessionId);
    }
  }, [activeSessionId, sessions, syncSessionDetail]);

  // 权限模式属于会话状态：切换会话时按需读取，网络失败时安全回退到逐次批准。
  useEffect(() => {
    if (!activeSessionId || permissionModes[activeSessionId]) return;
    let cancelled = false;
    fetchSessionPermissionMode(activeSessionId)
      .then((mode) => {
        if (!cancelled) setPermissionModes((previous) => ({ ...previous, [activeSessionId]: mode }));
      })
      .catch(() => {
        if (!cancelled) setPermissionModes((previous) => ({ ...previous, [activeSessionId]: 'ASK' }));
      });
    return () => { cancelled = true; };
  }, [activeSessionId, permissionModes]);

  const activeSession = sessions.find((s) => s.id === activeSessionId);

  // 待审批状态以服务端为准，刷新页面或切回会话时恢复审批卡片和 partial assistant。
  useEffect(() => {
    if (!activeSessionId || !activeSession?.isLoaded || streamingSessionIds.has(activeSessionId)) return;
    const sessionId = activeSessionId;
    let cancelled = false;
    fetchPendingPermission(sessionId).then((pending) => {
      if (cancelled) return;
      if (!pending) {
        setPendingPermissions((previous) => {
          if (!previous[sessionId]) return previous;
          const next = { ...previous };
          delete next[sessionId];
          return next;
        });
        return;
      }

      let assistantMessageId = `pending_${pending.turnId}`;
      setSessions((previous) => previous.map((session) => {
        if (session.id !== sessionId) return session;
        const existing = session.messages.find(
          (message) => message.role === 'assistant' && message.turnId === pending.turnId,
        );
        if (existing) {
          assistantMessageId = existing.id;
          return session;
        }
        return {
          ...session,
          messages: [...session.messages, {
            id: assistantMessageId,
            turnId: pending.turnId,
            role: 'assistant',
            modelName: 'ButvanAgent',
            content: pending.partialContent || '',
            createdAt: new Date(pending.startedAt).getTime() || Date.now(),
            startTime: new Date(pending.startedAt).getTime() || Date.now(),
          }],
        };
      }));
      setPendingPermissions((previous) => {
        if (previous[sessionId]?.approvalId === pending.approvalId) return previous;
        return {
          ...previous,
          [sessionId]: {
            sessionId,
            assistantMessageId,
            approvalId: pending.approvalId,
            runId: pending.runId,
            tools: pending.tools,
          },
        };
      });
    }).catch(() => {
      // 会话详情仍可正常使用；下一次切换会话时会重新查询权威审批状态。
    });
    return () => { cancelled = true; };
  }, [activeSessionId, activeSession?.isLoaded, streamingSessionIds]);

  const activePendingPermission = activeSessionId ? pendingPermissions[activeSessionId] ?? null : null;
  const activeMessages = activeSession?.messages || [];
  const activeSessionLoadError = activeSession && !activeSession.isLoaded
    ? sessionLoadErrors[activeSession.id] ?? null
    : null;
  const activeProjectPath = activeSession?.projectId
    ? (projects.find((project) => project.id === activeSession.projectId && project.availability === 'AVAILABLE')?.path ?? null)
    : null;

  // 3. 新建普通独立会话（UUID 由后端统一生成）
  const handleNewGeneralChat = async () => {
    const res = await createSessionApi({ kind: 'GENERAL', title: '新对话' });
    if (res.success && res.data) {
      const newSession: ChatSession = {
        id: res.data.id,
        kind: res.data.kind,
        projectId: res.data.projectId ?? undefined,
        title: res.data.title,
        lastMessagePreview: res.data.lastMessagePreview,
        createdAt: new Date(res.data.createdAt).getTime() || Date.now(),
        updatedAt: new Date(res.data.updatedAt).getTime() || Date.now(),
        messages: [],
        isLoaded: true,
      };
      setSessions((prev) => [newSession, ...prev]);
      setActiveSessionId(newSession.id);
    }
  };

  // 4. 新建项目绑定会话
  const handleNewProjectChat = async (projectId: string, knownProjectName?: string): Promise<boolean> => {
    const targetProject = projects.find((p) => p.id === projectId);
    if (targetProject && targetProject.availability !== 'AVAILABLE') {
      showMessage('error', '项目目录当前不可访问，请恢复目录后重试。');
      return false;
    }
    const titleName = `${knownProjectName || targetProject?.name || '项目'} 会话`;

    const res = await createSessionApi({ kind: 'PROJECT', projectId, title: titleName });
    if (res.success && res.data) {
      const newSession: ChatSession = {
        id: res.data.id,
        kind: res.data.kind,
        title: res.data.title,
        lastMessagePreview: res.data.lastMessagePreview,
        projectId: res.data.projectId ?? projectId,
        createdAt: new Date(res.data.createdAt).getTime() || Date.now(),
        updatedAt: new Date(res.data.updatedAt).getTime() || Date.now(),
        messages: [],
        isLoaded: true,
      };
      setSessions((prev) => [newSession, ...prev]);
      setActiveSessionId(newSession.id);
      setActiveFeature('chat');
      return true;
    }
    showMessage('error', res.message || '创建项目会话失败');
    return false;
  };

  // 5. 导入本地项目
  const handleImportProject = async (name: string, path: string): Promise<{ success: boolean; message?: string }> => {
    try {
      const project = await importProject(name, path);
      setProjects((previous) => [project, ...previous]);
      const created = await handleNewProjectChat(project.id, project.name);
      showMessage(
        created ? 'success' : 'info',
        created ? `已导入项目“${project.name}”` : `已导入项目“${project.name}”，请从项目旁的新建按钮重试会话。`,
      );
      return { success: true };
    } catch (error) {
      return { success: false, message: error instanceof Error ? error.message : '导入项目失败' };
    }
  };

  // 6. 移除项目登记；项目文件保持不变，关联会话转为普通会话
  const handleRemoveProject = async (projectId: string): Promise<{ success: boolean; message?: string }> => {
    const projectName = projects.find((project) => project.id === projectId)?.name || '项目';
    try {
      await removeProjectApi(projectId);
      setProjects((previous) => previous.filter((project) => project.id !== projectId));
      setSessions((previous) => previous.map((session) => session.projectId === projectId
        ? { ...session, kind: 'GENERAL', projectId: undefined }
        : session));
      showMessage('success', `已移除项目“${projectName}”，关联会话已保留`);
      return { success: true };
    } catch (error) {
      return { success: false, message: error instanceof Error ? error.message : '移除项目失败' };
    }
  };

  // 7. 删除会话
  const handleDeleteSession = async (id: string): Promise<{ success: boolean; message?: string }> => {
    if (streamingSessionIds.has(id) || pendingPermissions[id]) {
      return { success: false, message: '当前会话仍在运行或等待权限确认，请结束后再删除。' };
    }
    const res = await deleteSessionApi(id);
    if (res.success) {
      setSessions((prev) => {
        const updated = prev.filter((s) => s.id !== id);
        if (activeSessionId === id) {
          const nextId = updated[0]?.id || '';
          setActiveSessionId(nextId);
        }
        return updated;
      });
      setPermissionModes((prev) => {
        const next = { ...prev };
        delete next[id];
        return next;
      });
      setSessionLoadErrors((prev) => {
        const next = { ...prev };
        delete next[id];
        return next;
      });
      setStreamingSessionIds((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
      showMessage('success', '会话已删除。');
      return { success: true };
    }
    return { success: false, message: res.message || '删除会话失败，请稍后重试。' };
  };

  // 7. 修改会话标题
  const handleUpdateSessionTitle = async (id: string, newTitle: string) => {
    const previousTitle = sessions.find((session) => session.id === id)?.title;
    setSessions((prev) =>
      prev.map((s) => (s.id === id ? { ...s, title: newTitle } : s))
    );
    const result = await updateSessionTitleApi(id, newTitle);
    if (!result.success && previousTitle !== undefined) {
      setSessions((prev) => prev.map((session) =>
        session.id === id ? { ...session, title: previousTitle } : session));
    }
    return result;
  };

  // 7.5 选择会话：切换到对应会话并确保回到对话视图（日历模式下点击会话可跳回）
  const handleSelectSession = (id: string) => {
    setActiveSessionId(id);
    setActiveFeature('chat');
  };

  const handlePermissionModeChange = async (mode: SessionPermissionMode) => {
    if (!activeSessionId || savingPermissionSessionId || streamingSessionIds.has(activeSessionId)
        || pendingPermissions[activeSessionId]) return;
    const sessionId = activeSessionId;
    const previous = permissionModes[sessionId] ?? 'ASK';
    setPermissionModes((current) => ({ ...current, [sessionId]: mode }));
    setSavingPermissionSessionId(sessionId);
    try {
      const saved = await updateSessionPermissionMode(sessionId, mode);
      setPermissionModes((current) => ({ ...current, [sessionId]: saved }));
    } catch (error) {
      setPermissionModes((current) => ({ ...current, [sessionId]: previous }));
      showMessage('error', error instanceof Error ? error.message : '权限模式保存失败，请重试。');
    } finally {
      setSavingPermissionSessionId(null);
    }
  };

  // 8. 发送消息发起 SSE 流
  const handleSendMessage = async (
    prompt: string,
    modelContext = prompt,
    recordReferenceIds: string[] = [],
    analysisContext?: AgentAnalysisContextRequest,
  ) => {
    let currentSessionId = activeSessionId;
    let targetSession = sessions.find((s) => s.id === currentSessionId);

    // 若无激活会话，首先调用后端生成新会话 ID
    if (!targetSession) {
      const res = await createSessionApi({ kind: 'GENERAL', title: '新对话' });
      if (res.success && res.data) {
        currentSessionId = res.data.id;
        targetSession = {
          id: res.data.id,
          kind: res.data.kind,
          title: res.data.title,
          lastMessagePreview: res.data.lastMessagePreview,
          createdAt: new Date(res.data.createdAt).getTime() || Date.now(),
          updatedAt: new Date(res.data.updatedAt).getTime() || Date.now(),
          messages: [],
          isLoaded: true,
        };
        setSessions((prev) => [targetSession!, ...prev]);
        setActiveSessionId(currentSessionId);
      } else {
        return;
      }
    }

    if (activeChatRunsRef.current.has(currentSessionId)) return;

    const userMsg: ChatMessage = {
      id: String(Date.now()),
      role: 'user',
      content: prompt,
      createdAt: Date.now(),
    };

    const startTime = Date.now();
    const assistantMsgId = String(startTime + 1);
    const runId = createRunId();
    const assistantMsg: ChatMessage = {
      id: assistantMsgId,
      role: 'assistant',
      modelName: 'ButvanAgent',
      content: '',
      createdAt: startTime,
      startTime: startTime,
    };

    // 本地即时追加 UI 消息
    setSessions((prev) =>
      prev.map((s) => {
        if (s.id === currentSessionId) {
          return {
            ...s,
            updatedAt: Date.now(),
            messages: [...s.messages, userMsg, assistantMsg],
          };
        }
        return s;
      })
    );

    // 发起 SSE 流式调用，发送 content 字段
    const controller = beginChatRun(currentSessionId, runId, assistantMsgId);
    void streamAgentChat(
      {
        runId,
        sessionId: currentSessionId,
        content: prompt,
        context: modelContext,
        recordReferenceIds,
        analysisContext,
        signal: controller.signal,
      },
      (chunkText) => {
        if (activeChatRunsRef.current.get(currentSessionId)?.runId !== runId) return;
        setSessions((prev) =>
          prev.map((s) => {
            if (s.id === currentSessionId) {
              return {
                ...s,
                messages: s.messages.map((msg) =>
                  msg.id === assistantMsgId
                    ? { ...msg, content: msg.content + chunkText }
                    : msg
                ),
              };
            }
            return s;
          })
        );
      },
      () => {
        if (!finishChatRun(currentSessionId, runId)) return;
        // 流式对话完成后同步耗时并重新刷新后端的最新详情
        setSessions((prev) =>
          prev.map((s) => {
            if (s.id === currentSessionId) {
              return {
                ...s,
                messages: s.messages.map((msg) => {
                  if (msg.id === assistantMsgId) {
                    const elapsed = Math.max(
                      1,
                      Math.floor((Date.now() - (msg.startTime || msg.createdAt)) / 1000)
                    );
                    return { ...msg, elapsedTime: elapsed };
                  }
                  return msg;
                }),
              };
            }
            return s;
          })
        );

        // 从后端重新同步最新的消息和摘要（包含更新的目录册 title / preview 及后端持久化的完整消息）
        void syncSessionDetail(currentSessionId, targetSession?.title === '新对话');
      },
      (err) => {
        if (!finishChatRun(currentSessionId, runId)) return;
        console.error('Session 流式对话异常:', err);
        setSessions((prev) =>
          prev.map((s) => {
            if (s.id === currentSessionId) {
              return {
                ...s,
                messages: s.messages.map((msg) =>
                  msg.id === assistantMsgId
                    ? markAssistantTerminal(msg, 'FAILED', err.message)
                    : msg,
                ),
              };
            }
            return s;
          })
        );
      },
      (toolCallPayload) => {
        if (activeChatRunsRef.current.get(currentSessionId)?.runId !== runId) return;
        setSessions((prev) =>
          prev.map((s) => {
            if (s.id === currentSessionId) {
              return {
                ...s,
                messages: s.messages.map((msg) => {
                  if (msg.id === assistantMsgId) {
                    const tools = msg.tools ? [...msg.tools] : [];
                    const targetId = toolCallPayload.toolCallId || 'tool_' + Date.now();
                    const existingIndex = tools.findIndex((t) => t.toolCallId === targetId);

                    if (existingIndex >= 0) {
                      tools[existingIndex] = {
                        ...tools[existingIndex],
                        toolName: toolCallPayload.toolName || tools[existingIndex].toolName,
                        command: toolCallPayload.command || tools[existingIndex].command,
                      };
                    } else {
                      tools.push({
                        toolCallId: targetId,
                        toolName: toolCallPayload.toolName || 'custom_bash',
                        command: toolCallPayload.command || '',
                        status: 'running',
                      });
                    }
                    return { ...msg, tools };
                  }
                  return msg;
                }),
              };
            }
            return s;
          })
        );
      },
      (toolResultPayload) => {
        if (activeChatRunsRef.current.get(currentSessionId)?.runId !== runId) return;
        setSessions((prev) =>
          prev.map((s) => {
            if (s.id === currentSessionId) {
              return {
                ...s,
                messages: s.messages.map((msg) => {
                  if (msg.id === assistantMsgId) {
                    const tools = msg.tools ? [...msg.tools] : [];
                    const targetId = toolResultPayload.toolCallId;

                    let targetIndex = -1;
                    if (targetId) {
                      targetIndex = tools.findIndex((t) => t.toolCallId === targetId);
                    }
                    if (targetIndex === -1) {
                      targetIndex = tools.findLastIndex((t) => t.status === 'running');
                    }

                    if (targetIndex >= 0) {
                      tools[targetIndex] = {
                        ...tools[targetIndex],
                        output: (tools[targetIndex].output || '') + (toolResultPayload.result || ''),
                        status: toolResultPayload.status || 'completed',
                      };
                    }
                    return { ...msg, tools };
                  }
                  return msg;
                }),
              };
            }
            return s;
          })
        );
      },
      (thinkingChunk) => {
        if (activeChatRunsRef.current.get(currentSessionId)?.runId !== runId) return;
        setSessions((prev) =>
          prev.map((s) => {
            if (s.id === currentSessionId) {
              return {
                ...s,
                messages: s.messages.map((msg) =>
                  msg.id === assistantMsgId
                    ? { ...msg, reasoning: (msg.reasoning || '') + thinkingChunk }
                    : msg
                ),
              };
            }
            return s;
          })
        );
      },
      (permissionPayload) => {
        if (!finishChatRun(currentSessionId, runId)) return;
        updateAssistantMessage(currentSessionId, assistantMsgId,
          (message) => ({ ...message, turnId: permissionPayload.turnId }));
        setPendingPermissions((previous) => ({
          ...previous,
          [currentSessionId]: {
            sessionId: currentSessionId,
            assistantMessageId: assistantMsgId,
            approvalId: permissionPayload.approvalId,
            runId: permissionPayload.runId,
            tools: permissionPayload.tools,
          },
        }));
      },
      (progress) => {
        if (activeChatRunsRef.current.get(currentSessionId)?.runId !== runId) return;
        appendSubagentProgress(currentSessionId, assistantMsgId, progress);
      },
      () => {
        if (!finishChatRun(currentSessionId, runId)) return;
        updateAssistantMessage(currentSessionId, assistantMsgId,
          (message) => markAssistantTerminal(message, 'CANCELLED'));
        void syncSessionDetail(currentSessionId);
      },
      (notice) => {
        const previous = lastRoutingNoticeRef.current;
        const now = Date.now();
        if (previous?.code === notice.code && now - previous.shownAt < 60_000) return;
        lastRoutingNoticeRef.current = { code: notice.code, shownAt: now };
        const requestHint = notice.requestId ? `（请求 ID：${notice.requestId}）` : '';
        const retryHint = notice.retryAfterMillis && notice.retryAfterMillis > 0
          ? `建议 ${Math.ceil(notice.retryAfterMillis / 1_000)} 秒后重试 Jev。`
          : '';
        showMessage('info', `${notice.message}${retryHint}${requestHint}`, { duration: 8_000 });
      },
    );
  };

  /** 向当前 assistant 草稿追加恢复流产生的内容。 */
  const updateAssistantMessage = (
    sessionId: string,
    messageId: string,
    update: (message: ChatMessage) => ChatMessage,
  ) => {
    setSessions((prev) => prev.map((session) => session.id === sessionId
      ? { ...session, messages: session.messages.map((message) =>
        message.id === messageId ? update(message) : message) }
      : session));
  };

  /** 建立恢复 SSE；提交决定后立即清除待处理弹窗，使界面无缝切入恢复推流。 */
  const resumePendingPermission = async (current: PendingPermissionState) => {
    const clearPending = () => setPendingPermissions((previous) => {
      const next = { ...previous };
      delete next[current.sessionId];
      return next;
    });
    clearPending();

    const controller = beginChatRun(
      current.sessionId,
      current.runId,
      current.assistantMessageId,
    );
    await streamAgentChat(
      {
        runId: current.runId,
        sessionId: current.sessionId,
        approvalId: current.approvalId,
        signal: controller.signal,
      },
      (text) => {
        if (activeChatRunsRef.current.get(current.sessionId)?.runId !== current.runId) return;
        updateAssistantMessage(current.sessionId, current.assistantMessageId,
          (message) => ({ ...message, content: message.content + text }));
      },
      () => {
        if (!finishChatRun(current.sessionId, current.runId)) return;
        clearPending();
        updateAssistantMessage(current.sessionId, current.assistantMessageId, (message) => ({
          ...message,
          elapsedTime: Math.max(1, Math.floor((Date.now() - (message.startTime || message.createdAt)) / 1000)),
        }));
        void syncSessionDetail(current.sessionId, true);
      },
      (error) => {
        if (!finishChatRun(current.sessionId, current.runId)) return;
        clearPending();
        updateAssistantMessage(current.sessionId, current.assistantMessageId,
          (message) => markAssistantTerminal(message, 'FAILED', `恢复任务失败：${error.message}`));
      },
      (tool) => {
        if (activeChatRunsRef.current.get(current.sessionId)?.runId !== current.runId) return;
        updateAssistantMessage(current.sessionId, current.assistantMessageId, (message) => ({
          ...message,
          tools: [...(message.tools || []), {
            toolCallId: tool.toolCallId || `tool_${Date.now()}`,
            toolName: tool.toolName || 'tool', command: tool.command || '', status: 'running',
          }],
        }));
      },
      (result) => {
        if (activeChatRunsRef.current.get(current.sessionId)?.runId !== current.runId) return;
        updateAssistantMessage(current.sessionId, current.assistantMessageId, (message) => ({
          ...message,
          tools: (message.tools || []).map((tool) => tool.toolCallId === result.toolCallId
            ? {
                ...tool,
                status: result.status || 'completed',
                output: (tool.output || '') + (result.result || ''),
              }
            : tool),
        }));
      },
      (thinking) => {
        if (activeChatRunsRef.current.get(current.sessionId)?.runId !== current.runId) return;
        updateAssistantMessage(current.sessionId, current.assistantMessageId,
          (message) => ({ ...message, reasoning: (message.reasoning || '') + thinking }));
      },
      (permissionPayload) => {
        if (!finishChatRun(current.sessionId, current.runId)) return;
        updateAssistantMessage(current.sessionId, current.assistantMessageId,
          (message) => ({ ...message, turnId: permissionPayload.turnId }));
        setPendingPermissions((previous) => ({
          ...previous,
          [current.sessionId]: {
            sessionId: current.sessionId,
            assistantMessageId: current.assistantMessageId,
            approvalId: permissionPayload.approvalId,
            runId: permissionPayload.runId,
            tools: permissionPayload.tools,
          },
        }));
      },
      (progress) => {
        if (activeChatRunsRef.current.get(current.sessionId)?.runId !== current.runId) return;
        appendSubagentProgress(current.sessionId, current.assistantMessageId, progress);
        void refreshSubagentTasks(current.sessionId);
      },
      () => {
        if (!finishChatRun(current.sessionId, current.runId)) return;
        clearPending();
        updateAssistantMessage(current.sessionId, current.assistantMessageId,
          (message) => markAssistantTerminal(message, 'CANCELLED'));
        void syncSessionDetail(current.sessionId);
      },
    );
  };

  /** 一次提交整批决定；成功后恢复原 Agent 运行。 */
  const handlePermissionDecision = async (decisions: PermissionDecisionInput[]) => {
    if (!activePendingPermission || activePendingPermission.tools.length === 0
        || decisions.length === 0 || isPermissionSubmitting) return;
    const current = activePendingPermission;
    setIsPermissionSubmitting(true);
    try {
      const result = await submitPermissionDecisions({
        sessionId: current.sessionId,
        approvalId: current.approvalId,
        decisions,
      });
      if (result.readyToResume) await resumePendingPermission(current);
    } catch {
      updateAssistantMessage(current.sessionId, current.assistantMessageId, (message) => ({
        ...message,
        content: message.content || '提交权限决定失败，请重试。',
      }));
    } finally {
      setIsPermissionSubmitting(false);
    }
  };

  const handlePermissionResume = async () => {
    if (!activePendingPermission || activePendingPermission.tools.length > 0 || isPermissionSubmitting) return;
    setIsPermissionSubmitting(true);
    try {
      await resumePendingPermission(activePendingPermission);
    } finally {
      setIsPermissionSubmitting(false);
    }
  };

  const handleStopAgentRun = async () => {
    if (!activeSessionId || stoppingSessionIds.has(activeSessionId)) return;
    const activeRun = activeChatRunsRef.current.get(activeSessionId);
    if (!activeRun) return;
    const sessionId = activeSessionId;
    setStoppingSessionIds((previous) => new Set(previous).add(sessionId));
    try {
      const result = await cancelAgentChatRun(sessionId, activeRun.runId);
      if (result.status === 'NOT_FOUND') {
        activeRun.controller.abort();
        finishChatRun(sessionId, activeRun.runId);
        const reconciled = await syncSessionDetail(sessionId);
        if (!reconciled) {
          updateAssistantMessage(sessionId, activeRun.assistantMessageId,
            (message) => markAssistantTerminal(
              message,
              'FAILED',
              '未找到对应的 Agent 运行，且聊天记录同步失败，请重新发送。',
            ));
        }
      }
    } catch (error) {
      setStoppingSessionIds((previous) => {
        const next = new Set(previous);
        next.delete(sessionId);
        return next;
      });
      showMessage('error', error instanceof Error ? error.message : '停止请求失败，请重试。');
    }
  };

  return (
    <div style={{ display: 'flex', width: '100vw', height: '100vh', overflow: 'hidden' }}>
      {isSettingsOpen ? (
        <ModelSettingsPage onBack={() => setIsSettingsOpen(false)} initialTab={settingsTab} />
      ) : (
        <>
          <Sidebar
            activeFeature={activeFeature}
            onSelectFeature={(feature) => { setRecordTarget(undefined); setActiveFeature(feature); }}
            projects={projects}
            sessions={sessions}
            activeSessionId={activeSessionId}
            onSelectSession={handleSelectSession}
            onNewGeneralChat={handleNewGeneralChat}
            onNewProjectChat={handleNewProjectChat}
            onImportProject={handleImportProject}
            onRemoveProject={handleRemoveProject}
            onDeleteSession={handleDeleteSession}
            onUpdateSessionTitle={handleUpdateSessionTitle}
            onOpenSettings={() => { setSettingsTab('config'); setIsSettingsOpen(true); }}
            onOpenAccountSettings={() => { setSettingsTab('account'); setIsSettingsOpen(true); }}
          />
          {activeFeature === 'task' ? (
            <TaskPage onOpenSettings={() => { setSettingsTab('task'); setIsSettingsOpen(true); }} />
          ) : activeFeature === 'calendar' ? (
            <CalendarView />
          ) : activeFeature === 'finance' ? (
            <FinancePage />
          ) : activeFeature === 'record' ? (
            <RecordPage initialEntry={recordTarget} initialType={recordInitialType} />
          ) : activeFeature === 'study' ? (
            <StudyPage />
          ) : (
            <ChatWorkspace
              onOpenFeature={setActiveFeature}
              onOpenRecords={(entry, initialType = 'quick') => { setRecordTarget(entry); setRecordInitialType(initialType); setActiveFeature('record'); }}
              messages={activeMessages}
              sessionId={activeSessionId}
              sessionTitle={activeSession?.title || '新对话'}
              sessionUsageSummary={activeSession?.usageSummary}
              isSessionLoading={Boolean(activeSession && !activeSession.isLoaded && !activeSessionLoadError)}
              isSessionStreaming={streamingSessionIds.has(activeSessionId)}
              isSessionStopping={stoppingSessionIds.has(activeSessionId)}
              onStopSession={handleStopAgentRun}
              sessionLoadError={activeSessionLoadError}
              onRetrySessionLoad={() => {
                if (activeSessionId) void syncSessionDetail(activeSessionId);
              }}
              onSendMessage={handleSendMessage}
              onRenameSession={(title) => handleUpdateSessionTitle(activeSessionId, title)}
              onOpenSettings={() => setIsSettingsOpen(true)}
              pendingPermission={activePendingPermission}
              isPermissionSubmitting={isPermissionSubmitting}
              onPermissionDecision={handlePermissionDecision}
              onPermissionResume={handlePermissionResume}
              subagentTasks={subagentTasks}
              isSubagentTasksLoading={isSubagentTasksLoading}
              subagentTaskError={subagentTaskError}
              cancellingTaskId={cancellingTaskId}
              onRefreshSubagentTasks={() => void refreshSubagentTasks()}
              onCancelSubagentTask={handleCancelSubagentTask}
              projectPath={activeProjectPath}
              projectId={activeSession?.projectId ?? null}
              permissionMode={permissionModes[activeSessionId] ?? 'ASK'}
              onPermissionModeChange={handlePermissionModeChange}
              isPermissionModeDisabled={!activeSessionId
                || streamingSessionIds.has(activeSessionId)
                || Boolean(activePendingPermission)
                || savingPermissionSessionId === activeSessionId}
              isPermissionModeSaving={savingPermissionSessionId === activeSessionId}
            />
          )}
        </>
      )}
      <StudyWindowLayer />
    </div>
  );
};


const PrimaryApp: React.FC = () => {
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [settingsTab, setSettingsTab] = useState('config');
  const [needsInit, setNeedsInit] = useState<boolean>(false);
  const [vendors, setVendors] = useState<string[]>(['gemini', 'openai', 'dashscope', 'deepseek', 'anthropic', 'ollama']);
  const [loading, setLoading] = useState<boolean>(true);

  const checkConfig = async () => {
    setLoading(true);
    try {
      // 1. 从后端获取支持的厂商列表
      const supportedVendors = await fetchSupportedVendors();
      if (supportedVendors && supportedVendors.length > 0) {
        setVendors(supportedVendors);
      }

      // 2. 从后端获取当前本地 config.json 中的模型配置
      const config = await fetchModelConfig();
      
      // 判断逻辑：若无配置，或字段内容为空（vendor/name/apiKey为空），则判定需要初始化
      if (!config || 
          !config.vendor || !config.vendor.trim() || 
          !config.name || !config.name.trim() || 
          (config.vendor !== 'ollama' && (!config.apiKey || !config.apiKey.trim()))) {
        setNeedsInit(true);
      } else {
        setNeedsInit(false);
      }
    } catch (e) {
      console.error('检查模型配置状态失败:', e);
      setNeedsInit(false);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    checkConfig();
  }, []);

  if (loading) {
    return (
      <div className="bootScreen">
        <LoadingTree size="large" label="正在启动 ButvanAgent…" />
      </div>
    );
  }

  // 若未初始化配置（字段为空），全屏展示初始化设置页面 ModelInitPage
  if (needsInit) {
    return (
      <ModelInitPage
        vendors={vendors}
        onSuccess={() => {
          setNeedsInit(false);
          checkConfig();
        }}
      />
    );
  }

  return (
    <ModelProviderContext>
      <MainLayout
        isSettingsOpen={isSettingsOpen}
        setIsSettingsOpen={setIsSettingsOpen}
        settingsTab={settingsTab}
        setSettingsTab={setSettingsTab}
      />
    </ModelProviderContext>
  );
};

export const App: React.FC = () => (
  <MessageProvider>
    <AutomationProvider>
    {new URLSearchParams(window.location.search).get('view') === 'task-reminder'
      ? <TaskReminderWindow />
      : <StudyRealtimeProvider>
          {new URLSearchParams(window.location.search).get('view') === 'study-widget'
            ? <SystemStudyWindow />
            : <PrimaryApp />}
        </StudyRealtimeProvider>}
    </AutomationProvider>
  </MessageProvider>
);

export default App;
