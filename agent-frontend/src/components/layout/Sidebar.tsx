import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  Bell,
  SquarePen,
  Search,
  X,
  Trash2,
  Folder,
  Plus,
  FolderPlus,
  MessageSquare,
  ChevronRight,
  ChevronDown,
  MoreHorizontal,
  Pencil,
  User,
  Settings,
  AlertTriangle,
} from 'lucide-react';
import { BooksIcon, CalendarDotsIcon, StudentIcon, WalletIcon } from '@phosphor-icons/react';
import type { ChatSession, Project } from '../../types/chat';
import { FormField } from '../common/FormField';
import { TextInput } from '../common/TextInput';
import { Modal } from '../common/Modal';
import { useMessage } from '../common/Message';
import { EmailBindingModal } from '../account/EmailBindingModal';
import { fetchAccountStatus, getAccountAvatarUrl } from '../../services/api';
import { canPickProjectDirectory, pickProjectDirectory } from '../../services/projectPicker';
import styles from './Sidebar.module.css';

function getAvatarText(email: string | null): string {
  if (!email) return '';
  const prefix = email.split('@')[0] || '';
  const clean = prefix.replace(/[^a-zA-Z0-9]/g, '');
  if (clean.length >= 2) {
    return clean.slice(0, 2).toUpperCase();
  }
  if (clean.length === 1) {
    return clean.toUpperCase();
  }
  return email.slice(0, 1).toUpperCase() || 'U';
}

type TimeGroupKey = 'today' | 'yesterday' | 'last7' | 'last30' | 'older';

const TIME_GROUP_ORDER: TimeGroupKey[] = ['today', 'yesterday', 'last7', 'last30', 'older'];

const TIME_GROUP_LABELS: Record<TimeGroupKey, string> = {
  today: '今天',
  yesterday: '昨天',
  last7: '过去 7 天',
  last30: '过去 30 天',
  older: '更早',
};

const DAY_MS = 24 * 60 * 60 * 1000;

const SIDEBAR_DEFAULT_WIDTH = 260;
const SIDEBAR_MIN_WIDTH = 220;
const SIDEBAR_MAX_WIDTH = 400;
const SIDEBAR_WIDTH_KEY = 'butvan.sidebarWidth';

/** 按最近活跃时间将会话分桶（ChatGPT 式时间分组）。 */
function getTimeGroup(timestamp: number): TimeGroupKey {
  const startOfToday = new Date();
  startOfToday.setHours(0, 0, 0, 0);
  const diffDays = Math.floor((startOfToday.getTime() - timestamp) / DAY_MS);
  if (diffDays <= 0) return 'today';
  if (diffDays === 1) return 'yesterday';
  if (diffDays < 7) return 'last7';
  if (diffDays < 30) return 'last30';
  return 'older';
}

function sortByUpdatedDesc(a: ChatSession, b: ChatSession): number {
  return b.updatedAt - a.updatedAt;
}

interface SidebarProps {
  activeFeature: 'chat' | 'calendar' | 'finance' | 'record' | 'study' | 'task';
  onSelectFeature: (feature: 'chat' | 'calendar' | 'finance' | 'record' | 'study' | 'task') => void;
  /** 用户头像 URL；未配置时使用邮箱前两位作为默认头像 */
  avatarUrl?: string;
  projects: Project[];
  sessions: ChatSession[];
  activeSessionId: string;
  onSelectSession: (id: string) => void;
  onNewGeneralChat: () => void;
  onNewProjectChat: (projectId: string) => void;
  onImportProject: (name: string, path: string) => Promise<{ success: boolean; message?: string }>;
  onRemoveProject: (projectId: string) => Promise<{ success: boolean; message?: string }>;
  onDeleteSession: (id: string) => Promise<{ success: boolean; message?: string }>;
  onUpdateSessionTitle?: (id: string, newTitle: string) => void;
  onOpenSettings: () => void;
  onOpenAccountSettings: () => void;
}

export const Sidebar: React.FC<SidebarProps> = ({
  activeFeature,
  onSelectFeature,
  avatarUrl,
  projects,
  sessions,
  activeSessionId,
  onSelectSession,
  onNewGeneralChat,
  onNewProjectChat,
  onImportProject,
  onRemoveProject,
  onDeleteSession,
  onUpdateSessionTitle,
  onOpenSettings,
  onOpenAccountSettings,
}) => {
  const { showMessage } = useMessage();
  const [isEmailBindingOpen, setIsEmailBindingOpen] = useState(false);
  const [maskedEmail, setMaskedEmail] = useState<string | null>(null);
  const [storedAvatarVersion, setStoredAvatarVersion] = useState<string | null>(null);
  const [avatarFailed, setAvatarFailed] = useState(false);

  // 双击 / 菜单重命名会话标题
  const [editingSessionId, setEditingSessionId] = useState<string | null>(null);
  const [editingTitle, setEditingTitle] = useState('');

  // 项目分区展开/收起
  const [isProjectsExpanded, setIsProjectsExpanded] = useState(true);
  const [expandedProjects, setExpandedProjects] = useState<Record<string, boolean>>({});

  // 会话搜索与悬停菜单
  const [searchQuery, setSearchQuery] = useState('');
  const [menuSession, setMenuSession] = useState<{
    session: ChatSession;
    x: number;
    y: number;
  } | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [deleteSession, setDeleteSession] = useState<ChatSession | null>(null);
  const [isDeletingSession, setIsDeletingSession] = useState(false);

  // 项目操作菜单与移除确认
  const [menuProject, setMenuProject] = useState<{
    project: Project;
    x: number;
    y: number;
  } | null>(null);
  const projectMenuRef = useRef<HTMLDivElement>(null);
  const [deleteProject, setDeleteProject] = useState<Project | null>(null);
  const [isRemovingProject, setIsRemovingProject] = useState(false);

  const [isImportModalOpen, setIsImportModalOpen] = useState(false);
  const [importPath, setImportPath] = useState('');
  const [importName, setImportName] = useState('');
  const [isPickingProject, setIsPickingProject] = useState(false);
  const [isImportingProject, setIsImportingProject] = useState(false);

  // 侧边栏拖拽调整宽度
  const sidebarRef = useRef<HTMLElement>(null);
  const [sidebarWidth, setSidebarWidth] = useState<number>(() => {
    try {
      const saved = Number(localStorage.getItem(SIDEBAR_WIDTH_KEY));
      if (saved >= SIDEBAR_MIN_WIDTH && saved <= SIDEBAR_MAX_WIDTH) {
        return saved;
      }
    } catch {
      // 忽略本地存储异常，使用默认宽度
    }
    return SIDEBAR_DEFAULT_WIDTH;
  });
  const sidebarWidthRef = useRef(sidebarWidth);
  sidebarWidthRef.current = sidebarWidth;
  const [isResizing, setIsResizing] = useState(false);

  useEffect(() => {
    if (!isResizing) return;

    const onMouseMove = (e: MouseEvent) => {
      const rect = sidebarRef.current?.getBoundingClientRect();
      if (!rect) return;
      const next = Math.min(
        SIDEBAR_MAX_WIDTH,
        Math.max(SIDEBAR_MIN_WIDTH, Math.round(e.clientX - rect.left))
      );
      setSidebarWidth(next);
    };

    const onMouseUp = () => {
      setIsResizing(false);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      try {
        localStorage.setItem(SIDEBAR_WIDTH_KEY, String(sidebarWidthRef.current));
      } catch {
        // 忽略本地存储异常
      }
    };

    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
    return () => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
    };
  }, [isResizing]);

  // Esc 关闭弹窗与用户菜单
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsImportModalOpen(false);
        setMenuSession(null);
        setMenuProject(null);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  // 点击会话菜单外部时关闭
  useEffect(() => {
    if (!menuSession) return;
    const onPointerDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuSession(null);
      }
    };
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, [menuSession]);

  // 点击项目菜单外部时关闭
  useEffect(() => {
    if (!menuProject) return;
    const onPointerDown = (e: MouseEvent) => {
      if (projectMenuRef.current && !projectMenuRef.current.contains(e.target as Node)) {
        setMenuProject(null);
      }
    };
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, [menuProject]);

  useEffect(() => {
    fetchAccountStatus().then((status) => {
      setMaskedEmail(status?.bound ? status.maskedEmail : null);
      setStoredAvatarVersion(status?.avatarVersion ?? null);
      setAvatarFailed(false);
    });
  }, []);

  const resolvedAvatarUrl = avatarUrl
    ?? (storedAvatarVersion ? getAccountAvatarUrl(storedAvatarVersion) : undefined);

  const generalSessions = sessions.filter((s) => !s.projectId);
  const sortedGeneral = [...generalSessions].sort(sortByUpdatedDesc);
  const normalizedQuery = searchQuery.trim().toLowerCase();
  const isSearching = normalizedQuery.length > 0;

  const searchResults = isSearching
    ? [...sessions].sort(sortByUpdatedDesc).filter((s) => s.title.toLowerCase().includes(normalizedQuery))
    : [];

  const groupedGeneral = TIME_GROUP_ORDER.map((key) => ({
    key,
    label: TIME_GROUP_LABELS[key],
    sessions: sortedGeneral.filter((s) => getTimeGroup(s.updatedAt) === key),
  })).filter((group) => group.sessions.length > 0);

  const toggleProject = (projectId: string) => {
    setExpandedProjects((prev) => ({
      ...prev,
      [projectId]: !prev[projectId],
    }));
  };

  const openSessionMenu = (e: React.MouseEvent<HTMLButtonElement>, session: ChatSession) => {
    e.stopPropagation();
    const rect = e.currentTarget.getBoundingClientRect();
    const menuWidth = 176;
    const left = Math.min(rect.right - menuWidth, window.innerWidth - menuWidth - 8);
    setMenuProject(null);
    setMenuSession({ session, x: left, y: rect.bottom + 4 });
  };

  const openProjectMenu = (e: React.MouseEvent<HTMLButtonElement>, project: Project) => {
    e.stopPropagation();
    const rect = e.currentTarget.getBoundingClientRect();
    const menuWidth = 176;
    const left = Math.min(rect.right - menuWidth, window.innerWidth - menuWidth - 8);
    setMenuSession(null);
    setMenuProject({ project, x: left, y: rect.bottom + 4 });
  };

  const requestRemoveProject = (project: Project) => {
    setMenuProject(null);
    setDeleteProject(project);
  };

  const closeRemoveProject = () => {
    if (isRemovingProject) return;
    setDeleteProject(null);
  };

  const confirmRemoveProject = async () => {
    if (!deleteProject || isRemovingProject) return;
    setIsRemovingProject(true);
    try {
      const result = await onRemoveProject(deleteProject.id);
      if (result.success) {
        setDeleteProject(null);
        return;
      }
      showMessage('error', result.message || '移除项目失败，请稍后重试。');
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '移除项目失败，请稍后重试。');
    } finally {
      setIsRemovingProject(false);
    }
  };

  const startRename = (session: ChatSession) => {
    setMenuSession(null);
    setEditingSessionId(session.id);
    setEditingTitle(session.title || '');
  };

  const requestDeleteSession = (session: ChatSession, e?: React.MouseEvent) => {
    e?.stopPropagation();
    setMenuSession(null);
    setDeleteSession(session);
  };

  const closeDeleteSession = () => {
    if (isDeletingSession) return;
    setDeleteSession(null);
  };

  const confirmDeleteSession = async () => {
    if (!deleteSession || isDeletingSession) return;
    setIsDeletingSession(true);
    try {
      const result = await onDeleteSession(deleteSession.id);
      if (result.success) {
        setDeleteSession(null);
        return;
      }
      showMessage('error', result.message || '删除会话失败，请稍后重试。');
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '删除会话失败，请稍后重试。');
    } finally {
      setIsDeletingSession(false);
    }
  };

  const commitRename = (session: ChatSession) => {
    if (editingTitle.trim() && onUpdateSessionTitle && editingTitle.trim() !== session.title) {
      onUpdateSessionTitle(session.id, editingTitle.trim());
    }
    setEditingSessionId(null);
  };

  const openImportDialog = async () => {
    if (isPickingProject) return;
    if (!canPickProjectDirectory()) {
      setIsImportModalOpen(true);
      return;
    }
    setIsPickingProject(true);
    try {
      const selected = await pickProjectDirectory();
      if (!selected) return;
      setImportPath(selected);
      setImportName(selected.split(/[\\/]/).filter(Boolean).pop() || '未命名项目');
      setIsImportModalOpen(true);
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '无法打开目录选择器，请手动填写路径');
      setIsImportModalOpen(true);
    } finally {
      setIsPickingProject(false);
    }
  };

  const handleConfirmImport = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!importPath.trim() || isImportingProject) return;
    const name = importName.trim() || importPath.split(/[\\/]/).filter(Boolean).pop() || '未命名项目';
    setIsImportingProject(true);
    try {
      const result = await onImportProject(name, importPath.trim());
      if (!result.success) {
        showMessage('error', result.message || '导入项目失败，请检查目录后重试。');
        return;
      }
      setImportPath('');
      setImportName('');
      setIsImportModalOpen(false);
    } finally {
      setIsImportingProject(false);
    }
  };

  const renderSessionRow = (session: ChatSession, withMenu = true) => {
    const isActive = session.id === activeSessionId;
    const isEditing = editingSessionId === session.id;
    const isMenuOpen = menuSession?.session.id === session.id;

    return (
      <div
        key={session.id}
        className={`${styles.sessionItem} ${isActive ? styles.sessionActive : ''}`}
        onClick={() => !isEditing && onSelectSession(session.id)}
      >
        <MessageSquare size={15} className={styles.sessionIcon} />

        {isEditing ? (
          <input
            type="text"
            className={styles.renameInput}
            value={editingTitle}
            onChange={(e) => setEditingTitle(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.stopPropagation();
                commitRename(session);
              } else if (e.key === 'Escape') {
                e.stopPropagation();
                setEditingSessionId(null);
              }
            }}
            onBlur={() => commitRename(session)}
            autoFocus
            onClick={(e) => e.stopPropagation()}
          />
        ) : (
          <span
            className={styles.sessionTitle}
            title={`${session.title || '新对话'}（双击修改标题）`}
            onDoubleClick={(e) => {
              e.stopPropagation();
              setEditingSessionId(session.id);
              setEditingTitle(session.title || '');
            }}
          >
            {session.title || '新对话'}
          </span>
        )}

        {withMenu && !isEditing && (
          <button
            type="button"
            className={`${styles.menuBtn} ${isMenuOpen ? styles.menuBtnOpen : ''}`}
            title="更多操作"
            aria-label="更多操作"
            onClick={(e) => openSessionMenu(e, session)}
          >
            <MoreHorizontal size={15} />
          </button>
        )}
      </div>
    );
  };

  return (
    <aside ref={sidebarRef} className={styles.sidebar} style={{ width: sidebarWidth }}>
      {/* Overlay 标题栏在侧边栏上方没有内容时，保留可拖拽的原生窗口区域。 */}
      <div className={styles.titlebarDragRegion} data-tauri-drag-region aria-hidden="true" />
      {/* 右缘拖拽手柄：调整侧边栏宽度，双击恢复默认宽度 */}
      <div
        className={`${styles.resizeHandle} ${isResizing ? styles.resizeHandleActive : ''}`}
        onMouseDown={(e) => {
          e.preventDefault();
          setIsResizing(true);
        }}
        onDoubleClick={() => setSidebarWidth(SIDEBAR_DEFAULT_WIDTH)}
        role="separator"
        aria-orientation="vertical"
        aria-label="拖拽调整侧边栏宽度"
      />
      {/*
        DIRECTION CONTRACT
        THESIS: 按 ChatGPT 官方会话历史的布局与交互重构侧边栏：搜索、时间分组、悬停菜单；新建聊天为列表标题行上的编辑图标。
        OWN-WORLD: 近白冷灰 #F7F8FA 纯平面，无边框无阴影；选中态仅文字变黑；蓝色仅作文件夹与焦点色。
        STORY: 用户一眼找到“新建聊天”，按 今天/昨天/近7天/近30天 回溯会话，悬停即可重命名或删除。
        FIRST VIEWPORT: 顶部搜索框 → 对话/日历功能选择器 → 项目分区 + 时间分组列表（标题行右侧编辑图标新建聊天）→ 底部用户信息与设置。
        FORM: ChatGPT 会话侧栏（用户钉定方向，非概念轮盘）。FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.
      */}
      <div className={styles.topArea}>
        {/* 会话搜索 */}
        <div className={styles.searchBox}>
          <Search size={14} className={styles.searchIcon} />
          <input
            type="text"
            className={styles.searchInput}
            placeholder="搜索对话"
            aria-label="搜索对话"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                setSearchQuery('');
                e.currentTarget.blur();
              }
            }}
          />
          {searchQuery && (
            <button
              type="button"
              className={styles.clearBtn}
              title="清空搜索"
              aria-label="清空搜索"
              onClick={() => setSearchQuery('')}
            >
              <X size={13} />
            </button>
          )}
        </div>

        <nav className={styles.featureNav} aria-label="功能导航">
          <button
            type="button"
            className={`${styles.featureTab} ${activeFeature === 'calendar' ? styles.featureTabActive : ''}`}
            onClick={() => onSelectFeature('calendar')}
            aria-current={activeFeature === 'calendar' ? 'page' : undefined}
          >
            <CalendarDotsIcon size={14} />
            <span>日历</span>
          </button>
          <button
            type="button"
            className={`${styles.featureTab} ${activeFeature === 'finance' ? styles.featureTabActive : ''}`}
            onClick={() => onSelectFeature('finance')}
            aria-current={activeFeature === 'finance' ? 'page' : undefined}
          >
            <WalletIcon size={14} />
            <span>财务</span>
          </button>
          <button
            type="button"
            className={`${styles.featureTab} ${activeFeature === 'record' ? styles.featureTabActive : ''}`}
            onClick={() => onSelectFeature('record')}
            aria-current={activeFeature === 'record' ? 'page' : undefined}
          >
            <BooksIcon size={14} />
            <span>资料</span>
          </button>
          <button
            type="button"
            className={`${styles.featureTab} ${activeFeature === 'study' ? styles.featureTabActive : ''}`}
            onClick={() => onSelectFeature('study')}
            aria-current={activeFeature === 'study' ? 'page' : undefined}
          >
            <StudentIcon size={14} />
            <span>记录</span>
          </button>
          <button type="button" className={`${styles.featureTab} ${activeFeature === 'task' ? styles.featureTabActive : ''}`}
            onClick={() => onSelectFeature('task')} aria-current={activeFeature === 'task' ? 'page' : undefined}>
            <Bell size={14} /><span>任务</span>
          </button>
        </nav>

        <div className={styles.scrollArea}>
          {isSearching ? (
            /* 搜索结果：不分项目/时间，扁平展示 */
            <section className={styles.sectionGroup}>
              <div className={styles.groupHeader}>
                <span className={styles.groupTitle}>搜索结果</span>
                <span className={styles.resultCount}>{searchResults.length} 条</span>
              </div>
              <div className={styles.sessionList}>
                {searchResults.length > 0 ? (
                  searchResults.map((session) => renderSessionRow(session))
                ) : (
                  <div className={styles.emptyText}>未找到相关对话</div>
                )}
              </div>
            </section>
          ) : (
            <>
              {/* 1. 项目大类 */}
              <section className={styles.sectionGroup}>
                <div className={styles.groupHeader}>
                  <button
                    type="button"
                    className={styles.groupTitleBtn}
                    onClick={() => setIsProjectsExpanded((prev) => !prev)}
                    aria-expanded={isProjectsExpanded}
                  >
                    {isProjectsExpanded ? (
                      <ChevronDown size={13} className={styles.arrowIcon} />
                    ) : (
                      <ChevronRight size={13} className={styles.arrowIcon} />
                    )}
                    <span className={styles.groupTitle}>项目</span>
                  </button>
                  <button
                    className={styles.iconBtnSmall}
                    title="导入本地项目"
                    aria-label="导入本地项目"
                    onClick={() => void openImportDialog()}
                    disabled={isPickingProject}
                  >
                    <FolderPlus size={14} />
                  </button>
                </div>

                {isProjectsExpanded && (
                  <div className={styles.sectionContent}>
                    {projects.length === 0 ? (
                      <button
                        type="button"
                        className={styles.emptyLinkRow}
                        onClick={() => void openImportDialog()}
                        disabled={isPickingProject}
                      >
                        <Plus size={13} />
                        <span>导入项目</span>
                      </button>
                    ) : (
                      <div className={styles.projectList}>
                        {projects.map((project) => {
                          const isExpanded = expandedProjects[project.id] ?? true;
                          const projectSessions = sessions
                            .filter((s) => s.projectId === project.id)
                            .sort(sortByUpdatedDesc);

                          return (
                            <div key={project.id} className={styles.projectItemContainer}>
                              <div className={styles.projectHead}>
                                <button
                                  type="button"
                                  className={styles.projectHeadLeft}
                                  onClick={() => toggleProject(project.id)}
                                  aria-expanded={isExpanded}
                                >
                                  {isExpanded ? (
                                    <ChevronDown size={13} className={styles.arrowIcon} />
                                  ) : (
                                    <ChevronRight size={13} className={styles.arrowIcon} />
                                  )}
                                  <Folder size={14} className={styles.folderIcon} />
                                  <span className={`${styles.projectName} ${project.availability !== 'AVAILABLE' ? styles.projectUnavailable : ''}`} title={project.availability === 'AVAILABLE' ? project.path : `${project.path}\n项目目录已移动或不可访问，可移除后重新导入`}>
                                    {project.name}
                                  </span>
                                  {project.availability !== 'AVAILABLE' && (
                                    <AlertTriangle
                                      size={12}
                                      className={styles.projectWarning}
                                      aria-label="项目目录已移动或不可访问"
                                    />
                                  )}
                                </button>
                                <button
                                  className={styles.iconBtnSmall}
                                  title={project.availability === 'AVAILABLE' ? '在此项目下新建会话' : '项目目录不可访问，无法新建会话'}
                                  aria-label={project.availability === 'AVAILABLE' ? '在此项目下新建会话' : '项目目录不可访问，无法新建会话'}
                                  onClick={() => onNewProjectChat(project.id)}
                                  disabled={project.availability !== 'AVAILABLE'}
                                >
                                  <Plus size={14} />
                                </button>
                                <button
                                  type="button"
                                  className={styles.iconBtnSmall}
                                  title="项目操作"
                                  aria-label={`${project.name} 项目操作`}
                                  aria-expanded={menuProject?.project.id === project.id}
                                  onClick={(e) => openProjectMenu(e, project)}
                                >
                                  <MoreHorizontal size={14} />
                                </button>
                              </div>

                              {isExpanded && (
                                <div className={styles.projectSubSessions}>
                                  {projectSessions.length > 0 ? (
                                    projectSessions.map((session) => {
                                      const isActive = session.id === activeSessionId;
                                      return (
                                        <div
                                          key={session.id}
                                          className={`${styles.projectSession} ${isActive ? styles.projectSessionActive : ''}`}
                                          onClick={() => onSelectSession(session.id)}
                                        >
                                          <span className={styles.sessionTitle} title={session.title}>
                                            {session.title || '新对话'}
                                          </span>
                                          <button
                                            type="button"
                                            className={styles.deleteBtn}
                                            title="删除会话"
                                            aria-label="删除会话"
                                            onClick={(e) => requestDeleteSession(session, e)}
                                          >
                                            <Trash2 size={12} />
                                          </button>
                                        </div>
                                      );
                                    })
                                  ) : (
                                    <div className={styles.emptyText}>暂无项目会话</div>
                                  )}
                                </div>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </div>
                )}
              </section>

              {/* 2. 最近会话（ChatGPT 式时间分组） */}
              <section className={styles.sectionGroup}>
                <div className={styles.groupHeader}>
                  <span className={styles.groupTitle}>最近</span>
                  <button
                    className={styles.iconBtnSmall}
                    title="新建聊天"
                    aria-label="新建聊天"
                    onClick={onNewGeneralChat}
                  >
                    <SquarePen size={14} />
                  </button>
                </div>
                {groupedGeneral.length > 0 ? (
                  groupedGeneral.map((group) => (
                    <div key={group.key} className={styles.timeGroup}>
                      <div className={styles.timeLabel}>{group.label}</div>
                      <div className={styles.sessionList}>
                        {group.sessions.map((session) => renderSessionRow(session))}
                      </div>
                    </div>
                  ))
                ) : (
                  <div className={styles.emptyText}>暂无对话</div>
                )}
              </section>
            </>
          )}
        </div>
      </div>

      {/* 会话操作菜单（Portal 渲染，避免被滚动区裁剪） */}
      {menuSession &&
        createPortal(
          <div
            ref={menuRef}
            className={styles.sessionMenu}
            role="menu"
            style={{ left: menuSession.x, top: menuSession.y }}
          >
            <button
              type="button"
              role="menuitem"
              className={styles.menuItem}
              onClick={() => startRename(menuSession.session)}
            >
              <Pencil size={13} />
              <span>重命名</span>
            </button>
            <button
              type="button"
              role="menuitem"
              className={`${styles.menuItem} ${styles.menuItemDanger}`}
              onClick={(e) => requestDeleteSession(menuSession.session, e)}
            >
              <Trash2 size={13} />
              <span>删除</span>
            </button>
          </div>,
          document.body
        )}

      {/* 项目操作菜单（Portal 渲染，避免被滚动区裁剪） */}
      {menuProject &&
        createPortal(
          <div
            ref={projectMenuRef}
            className={styles.sessionMenu}
            role="menu"
            style={{ left: menuProject.x, top: menuProject.y }}
          >
            <button
              type="button"
              role="menuitem"
              className={`${styles.menuItem} ${styles.menuItemDanger}`}
              onClick={() => requestRemoveProject(menuProject.project)}
            >
              <Trash2 size={13} />
              <span>移除项目</span>
            </button>
          </div>,
          document.body
        )}

      {/* 导入项目 UI 弹窗 */}
      <Modal
        open={isImportModalOpen}
        title="导入本地项目"
        onClose={() => { if (!isImportingProject) setIsImportModalOpen(false); }}
      >
        <form onSubmit={handleConfirmImport} className={styles.modalBody}>
          <FormField label="项目目录" htmlFor="import-path" required hint="只登记目录位置，不会复制、初始化或删除其中的文件。">
            <div className={styles.pathPickerRow}>
              <TextInput
                id="import-path"
                placeholder="选择目录，或粘贴绝对路径"
                value={importPath}
                onChange={(e) => setImportPath(e.target.value)}
                autoFocus
                required
              />
              {canPickProjectDirectory() && (
                <button type="button" className={styles.browseBtn} onClick={() => void openImportDialog()} disabled={isPickingProject || isImportingProject}>
                  重新选择
                </button>
              )}
            </div>
          </FormField>

          <FormField label="项目别名 (可选)" htmlFor="import-name">
            <TextInput
              id="import-name"
              placeholder="项目名称（留空自动解析路径最后一级）"
              value={importName}
              onChange={(e) => setImportName(e.target.value)}
            />
          </FormField>

          <div className={styles.modalActions}>
            <button
              type="button"
              className={styles.cancelBtn}
              onClick={() => setIsImportModalOpen(false)}
              disabled={isImportingProject}
            >
              取消
            </button>
            <button type="submit" className={styles.submitBtn} disabled={!importPath.trim() || isImportingProject}>
              {isImportingProject ? '正在导入…' : '导入并新建会话'}
            </button>
          </div>
        </form>
      </Modal>

      <Modal
        open={deleteSession !== null}
        title="删除会话？"
        onClose={closeDeleteSession}
        width={420}
        centered
      >
        <div className={styles.deleteModalBody}>
          <p className={styles.deleteDescription}>
            “{deleteSession?.title || '新对话'}”及其全部聊天记录将被永久删除，此操作无法撤销。
          </p>
          <div className={styles.modalActions}>
            <button
              type="button"
              className={styles.cancelBtn}
              onClick={closeDeleteSession}
              disabled={isDeletingSession}
              autoFocus
            >
              取消
            </button>
            <button
              type="button"
              className={styles.deleteConfirmBtn}
              onClick={() => void confirmDeleteSession()}
              disabled={isDeletingSession}
            >
              {isDeletingSession ? '正在删除…' : '删除会话'}
            </button>
          </div>
        </div>
      </Modal>

      <Modal
        open={deleteProject !== null}
        title="移除项目？"
        onClose={closeRemoveProject}
        width={420}
        centered
      >
        <div className={styles.deleteModalBody}>
          <p className={styles.deleteDescription}>
            “{deleteProject?.name || '项目'}”
            {deleteProject?.availability !== 'AVAILABLE'
              ? '当前目录已移动或不可访问。'
              : '将从应用的项目列表中移除。'}
            此操作只解除项目登记，不会删除磁盘文件；关联会话会保留为普通会话。
          </p>
          <div className={styles.modalActions}>
            <button
              type="button"
              className={styles.cancelBtn}
              onClick={closeRemoveProject}
              disabled={isRemovingProject}
              autoFocus
            >
              取消
            </button>
            <button
              type="button"
              className={styles.deleteConfirmBtn}
              onClick={() => void confirmRemoveProject()}
              disabled={isRemovingProject}
            >
              {isRemovingProject ? '正在移除…' : '移除项目'}
            </button>
          </div>
        </div>
      </Modal>

      <EmailBindingModal
        open={isEmailBindingOpen}
        onClose={() => setIsEmailBindingOpen(false)}
        onBound={setMaskedEmail}
      />

      {/* 底部用户个人信息与系统设置 */}
      <div className={styles.footer}>
        <button
          className={styles.userProfileCard}
          type="button"
          onClick={() => (maskedEmail ? onOpenAccountSettings() : setIsEmailBindingOpen(true))}
          title={maskedEmail ? `已绑定账号：${maskedEmail}（点击进入个人资料）` : '点击绑定邮箱'}
        >
          <div className={styles.avatarWrapper}>
            {resolvedAvatarUrl && !avatarFailed ? (
              <img
                src={resolvedAvatarUrl}
                alt="用户头像"
                className={styles.avatarImage}
                onError={() => setAvatarFailed(true)}
              />
            ) : (
              <div className={styles.avatar}>
                {maskedEmail ? getAvatarText(maskedEmail) : <User size={13} />}
              </div>
            )}
          </div>
          <div className={styles.profileInfo}>
            <span className={styles.profileName}>
              {maskedEmail || '未绑定邮箱'}
            </span>
          </div>
        </button>

        <button
          className={styles.settingsBtn}
          type="button"
          title="系统设置"
          aria-label="系统设置"
          onClick={onOpenSettings}
        >
          <Settings size={15} strokeWidth={1.75} />
        </button>
      </div>
    </aside>
  );
};

export default Sidebar;
