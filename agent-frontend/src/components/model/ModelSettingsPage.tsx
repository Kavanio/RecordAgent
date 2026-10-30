import React, { useState, useEffect, useMemo } from 'react';
import { useModel } from '../../context/ModelContext';
import { fetchAccountStatus, fetchSupportedVendors, type AccountStatus } from '../../services/api';
import { Button } from '../common/Button';
import { Select } from '../common/Select';
import { FormField } from '../common/FormField';
import { TextInput } from '../common/TextInput';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { Badge } from '../common/Badge';
import { Toggle } from '../common/Toggle';
import { useMessage } from '../common/Message';
import { VendorIcon } from './VendorIcon';
import { ModelEndpointFields } from './ModelEndpointFields';
import type { ModelProtocol } from '../../types/model';
import { FeatureSettingsPage, type FeatureSettingsTab } from '../settings/FeatureSettingsPage';
import { TokenUsageSettingsPage } from '../settings/TokenUsageSettingsPage';
import { PersonalContextSettingsPage } from '../settings/PersonalContextSettingsPage';
import { ProfileSettingsPage } from '../settings/ProfileSettingsPage';
import { SettingsMainRegion } from '../settings/SettingsMainRegion';
import { SlashCommandSettingsPage } from '../settings/SlashCommandSettingsPage';
import { DailyContextSettingsPage } from '../settings/DailyContextSettingsPage';
import { BackendConnectionSettings } from '../settings/BackendConnectionSettings';
import { TaskSettings } from '../task/TaskSettings';
import { ReviewWorkspace } from '../review/ReviewWorkspace';
import {
  getFeaturePreferences,
  setCalendarPreference,
  setChatTopBarPreference,
  setStudyWindowMode,
  subscribeFeaturePreferences,
} from '../../services/featurePreferences';
import { resetCalendarStickyNoteExpansion } from '../../services/calendarStickyNoteState';
import type { CalendarPreferences, ChatTopBarPreferences, FeaturePreferences, StudyWindowMode } from '../../types/preferences';
import {
  ArrowLeft,
  CalendarDays,
  Library,
  NotebookPen,
  Sliders,
  UserRound,
  Plus,
  Inbox,
  WalletCards,
  ChartNoAxesColumnIncreasing,
  Command,
  BrainCircuit,
  CloudSun,
  Bell,
  Sparkles,
  ChevronsUpDown,
  MoreHorizontal,
  MoreVertical,
  Eye,
  EyeOff,
  Trash2,
  Copy,
  Check,
  RotateCw,
  X,
  Network,
} from 'lucide-react';
import styles from './ModelSettingsPage.module.css';
interface ModelSettingsPageProps {
  onBack: () => void;
  initialTab?: string;
}
const VENDOR_DEFAULT_URLS: Record<string, string> = {
  dashscope: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  qwen: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  deepseek: 'https://api.deepseek.com/v1',
  gemini: 'https://generativelanguage.googleapis.com/v1beta',
  openai: 'https://api.openai.com/v1',
  anthropic: 'https://api.anthropic.com/v1',
  ollama: 'http://localhost:11434/v1',
};

const VENDOR_NAMES: Record<string, string> = {
  custom: '自定义 API',
  dashscope: 'DashScope',
  qwen: 'DashScope',
  deepseek: 'DeepSeek',
  gemini: 'Google Gemini',
  openai: 'OpenAI',
  anthropic: 'Anthropic Claude',
  ollama: 'Ollama',
};

interface TestRecord {
  lastTestedAt: number;
  success: boolean;
  message: string;
}

const STORAGE_KEY_TEST_RECORDS = 'butvan_agent_model_test_records_v1';

function formatRelativeTime(timestamp?: number): string {
  if (!timestamp) return '未测试';
  const diffSec = Math.floor((Date.now() - timestamp) / 1000);
  if (diffSec < 60) return '刚刚';
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)} 分钟前`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)} 小时前`;
  const diffDays = Math.floor(diffSec / 86400);
  if (diffDays < 30) return `${diffDays} 天前`;
  const date = new Date(timestamp);
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

function formatAbsoluteTime(timestamp?: number): string {
  if (!timestamp) return '未测试';
  const date = new Date(timestamp);
  const pad = (n: number) => (n < 10 ? `0${n}` : `${n}`);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export const ModelSettingsPage: React.FC<ModelSettingsPageProps> = ({ onBack, initialTab = 'config' }) => {
  const {
    providers,
    activeProviderId,
    activeModelId,
    selectActiveModel,
    addModelItem,
    updateModelItem,
    deleteModelItem,
    testConnectionByUrl,
    getAllModels,
  } = useModel();

  const { showMessage } = useMessage();

  const [activeTab, setActiveTab] = useState<string>(initialTab);
  const [supportedVendors, setSupportedVendors] = useState<string[]>([
    'dashscope',
    'deepseek',
    'gemini',
    'openai',
    'anthropic',
    'ollama',
  ]);
  const [accountStatus, setAccountStatus] = useState<AccountStatus | null>(null);
  const [accountLoading, setAccountLoading] = useState(true);
  const [featurePreferences, setFeaturePreferencesState] = useState<FeaturePreferences>(getFeaturePreferences);

  const allModels = useMemo(() => (providers ? getAllModels() : []), [getAllModels, providers]);

  // 排序状态
  const [sortOrder, setSortOrder] = useState<'none' | 'asc' | 'desc'>('none');

  // 当前选中的模型 Key（providerId + id）
  const [selectedKey, setSelectedKey] = useState<string>('');
  const [isCreating, setIsCreating] = useState<boolean>(false);
  // 右侧面板是否展开显示（默认收起）
  const [isPaneOpen, setIsPaneOpen] = useState<boolean>(false);

  // 编辑态/表单受控状态
  const [editName, setEditName] = useState('');
  const [editVendor, setEditVendor] = useState('dashscope');
  const [editModelId, setEditModelId] = useState('');
  const [editApiKey, setEditApiKey] = useState('');
  const [editBaseUrl, setEditBaseUrl] = useState('');
  const [editProtocol, setEditProtocol] = useState<ModelProtocol>('openai');
  const [saving, setSaving] = useState(false);
  const [editIsDefault, setEditIsDefault] = useState(false);
  const [editSupportsVision, setEditSupportsVision] = useState(false);
  const [showApiKey, setShowApiKey] = useState(false);

  // 测试状态管理
  const [testingKey, setTestingKey] = useState<string | null>(null);
  const [testRecords, setTestRecords] = useState<Record<string, TestRecord>>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY_TEST_RECORDS);
      if (saved) return JSON.parse(saved);
    } catch (e) {
      console.error('加载测试记录失败:', e);
    }
    return {};
  });

  // 删除确认弹窗
  const [showDeleteModal, setShowDeleteModal] = useState(false);

  // 更多操作下拉菜单
  const [openRowMenuKey, setOpenRowMenuKey] = useState<string | null>(null);
  const [openHeaderMenu, setOpenHeaderMenu] = useState(false);

  // 供应商列表初始化
  useEffect(() => {
    const loadVendors = async () => {
      const vendorList = await fetchSupportedVendors();
      if (vendorList && vendorList.length > 0) {
        setSupportedVendors(vendorList);
      }
    };
    loadVendors();
  }, []);

  useEffect(() => subscribeFeaturePreferences(setFeaturePreferencesState), []);

  useEffect(() => {
    let active = true;
    void fetchAccountStatus().then(status => {
      if (active) { setAccountStatus(status); setAccountLoading(false); }
    });
    return () => { active = false; };
  }, []);

  useEffect(() => setActiveTab(initialTab), [initialTab]);

  // 点击外部关闭下拉菜单
  useEffect(() => {
    const handleWindowClick = () => {
      setOpenRowMenuKey(null);
      setOpenHeaderMenu(false);
    };
    window.addEventListener('click', handleWindowClick);
    return () => window.removeEventListener('click', handleWindowClick);
  }, []);

  // 排序后的模型列表
  const displayedModels = useMemo(() => {
    if (sortOrder === 'none') return allModels;
    return [...allModels].sort((a, b) => {
      const nameA = (a.name || a.id).toLowerCase();
      const nameB = (b.name || b.id).toLowerCase();
      if (sortOrder === 'asc') return nameA.localeCompare(nameB);
      return nameB.localeCompare(nameA);
    });
  }, [allModels, sortOrder]);

  // 当前选中的模型对象
  const selectedModel = useMemo(() => {
    if (!selectedKey) return null;
    return allModels.find((m) => `${m.providerId}-${m.id}` === selectedKey) || null;
  }, [allModels, selectedKey]);

  // 列表变动时清理无效选中的模型并收起面板
  useEffect(() => {
    if (isCreating) return;
    if (allModels.length === 0) {
      setSelectedKey('');
      setIsPaneOpen(false);
      return;
    }

    if (selectedKey && !allModels.some((m) => `${m.providerId}-${m.id}` === selectedKey)) {
      setSelectedKey('');
      setIsPaneOpen(false);
    }
  }, [allModels, isCreating, selectedKey]);

  // 记录当前已加载到表单的模型标识，避免非切换选择引起的重复重置
  const loadedModelKeyRef = React.useRef<string>('');

  // 仅在用户切换选中模型或打开不同模型时填充右侧编辑表单
  useEffect(() => {
    if (isCreating) {
      loadedModelKeyRef.current = '';
      return;
    }
    if (!selectedKey || !selectedModel) {
      loadedModelKeyRef.current = '';
      return;
    }

    if (loadedModelKeyRef.current !== selectedKey) {
      loadedModelKeyRef.current = selectedKey;
      setEditName(selectedModel.name || selectedModel.id);
      setEditVendor(selectedModel.providerType || selectedModel.providerId || 'dashscope');
      setEditModelId(selectedModel.id);
      setEditApiKey(selectedModel.apiKey || '');
      setEditBaseUrl(selectedModel.baseUrl || VENDOR_DEFAULT_URLS[selectedModel.providerType] || '');
      setEditProtocol(selectedModel.protocol || 'openai');
      const isAct =
        selectedModel.providerId === activeProviderId &&
        selectedModel.id === activeModelId;
      setEditIsDefault(isAct);
      setEditSupportsVision(selectedModel.supportsVision === true);
      setShowApiKey(false);
    }
  }, [selectedKey, selectedModel, isCreating, activeProviderId, activeModelId]);

  // 关闭/收起右侧面板
  const handleClosePane = () => {
    setIsPaneOpen(false);
    setIsCreating(false);
    setSelectedKey('');
    loadedModelKeyRef.current = '';
    setShowApiKey(false);
  };

  // 开始创建新模型
  const handleStartAdd = () => {
    setIsCreating(true);
    setSelectedKey('');
    loadedModelKeyRef.current = '';
    const defaultVendor = supportedVendors[0] || 'dashscope';
    setEditVendor(defaultVendor);
    setEditProtocol('openai');
    setEditName('');
    setEditModelId('');
    setEditApiKey('');
    setEditBaseUrl(VENDOR_DEFAULT_URLS[defaultVendor] || '');
    setEditIsDefault(allModels.length === 0);
    setEditSupportsVision(false);
    setShowApiKey(false);
    setIsPaneOpen(true);
  };

  // 取消编辑/新建（收起面板）
  const handleCancel = () => {
    handleClosePane();
  };

  const changeVendor = (vendor: string) => {
    setEditVendor(vendor);
    setEditBaseUrl(VENDOR_DEFAULT_URLS[vendor] || '');
  };

  const setDefaultModel = async (providerId: string, modelId: string, name: string) => {
    try {
      await selectActiveModel(providerId, modelId);
      setEditIsDefault(true);
      showMessage('success', `已将 ${name} 设为默认模型`);
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '切换模型失败');
    }
  };

  // 保存更改或创建模型
  const handleSave = async () => {
    const trimmedModelId = editModelId.trim();
    const trimmedName = editName.trim();
    if (!trimmedModelId) {
      showMessage('error', '请输入有效的 Model ID');
      return;
    }
    if (!trimmedName) {
      showMessage('error', '请输入模型显示名称');
      return;
    }

    if (editVendor === 'custom' && !editBaseUrl.trim()) {
      showMessage('error', '请填写自定义 API 基础地址');
      return;
    }
    setSaving(true);
    try {
      const params = { vendor: editVendor, baseUrl: editBaseUrl.trim(),
        protocol: editVendor === 'custom' ? editProtocol : undefined,
        apiKey: editApiKey.trim(), modelId: trimmedModelId, name: trimmedName, makeDefault: editIsDefault,
        supportsVision: editSupportsVision };
      const providerId = isCreating ? await addModelItem(params)
        : selectedModel ? await updateModelItem({ providerId: selectedModel.providerId, modelId: selectedModel.id }, params) : '';
      showMessage('success', isCreating ? '模型配置已添加' : '模型配置已更新');
      loadedModelKeyRef.current = '';
      setShowApiKey(false);
      setIsCreating(false);
      setSelectedKey(`${providerId}-${trimmedModelId}`);
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '模型配置保存失败');
    } finally {
      setSaving(false);
    }
  };

  // 测试连接函数
  const handleTestConnection = async (target: {
    providerId: string;
    providerType: string;
    id: string;
    baseUrl?: string;
    apiKey?: string;
    protocol?: ModelProtocol;
  }) => {
    const key = `${target.providerId}-${target.id}`;
    setTestingKey(key);

    const baseUrl = target.baseUrl || VENDOR_DEFAULT_URLS[target.providerType] || '';
    const apiKey = target.apiKey || '';

    const res = await testConnectionByUrl(baseUrl, apiKey, target.providerType, target.id, target.protocol);

    const newRecord: TestRecord = {
      lastTestedAt: Date.now(),
      success: res.success,
      message: res.message,
    };

    setTestRecords((prev) => {
      const updated = { ...prev, [key]: newRecord };
      localStorage.setItem(STORAGE_KEY_TEST_RECORDS, JSON.stringify(updated));
      return updated;
    });

    setTestingKey(null);
    showMessage(res.success ? 'success' : 'error', res.message);
  };

  // 确认删除模型
  const handleConfirmDelete = async () => {
    if (!selectedModel) return;
    const { providerId, id, name } = selectedModel;
    try {
      await deleteModelItem(providerId, id);
      setShowDeleteModal(false);
      handleClosePane();
      showMessage('success', `模型 ${name} 已删除`);
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '删除失败');
    }
  };

  // 切换表头排序
  const handleToggleSort = () => {
    if (sortOrder === 'none') setSortOrder('asc');
    else if (sortOrder === 'asc') setSortOrder('desc');
    else setSortOrder('none');
  };

  // 复制 Model ID
  const handleCopyModelId = (modelId: string) => {
    navigator.clipboard.writeText(modelId);
    showMessage('success', 'Model ID 已复制到剪贴板');
  };

  const handleStudyWindowModeChange = (mode: StudyWindowMode) => {
    setFeaturePreferencesState(setStudyWindowMode(mode));
  };

  const handleChatTopBarChange = (key: keyof ChatTopBarPreferences, visible: boolean) => {
    setFeaturePreferencesState(setChatTopBarPreference(key, visible));
  };

  const handleCalendarPreferenceChange = (key: keyof CalendarPreferences, value: boolean) => {
    setFeaturePreferencesState(setCalendarPreference(key, value));
    if (key === 'stickyNotesDefaultExpanded') resetCalendarStickyNoteExpansion(value);
  };

  const isFeatureTab = (tab: string): tab is FeatureSettingsTab =>
    tab === 'calendar' || tab === 'finance' || tab === 'library' || tab === 'record';

  const vendorOptions = [...new Set([...supportedVendors, 'custom'])].map((v) => ({
    label: VENDOR_NAMES[v] || v,
    value: v,
  }));

  const currentTestRecord = selectedModel ? testRecords[`${selectedModel.providerId}-${selectedModel.id}`] : undefined;
  const isCurrentTesting = selectedModel ? testingKey === `${selectedModel.providerId}-${selectedModel.id}` : false;

  return (
    <div className={styles.pageContainer}>
      <div className={styles.titlebarDragRegion} data-tauri-drag-region aria-hidden="true" />
      <div className={styles.settingsSidebar}>
        <button className={styles.backBtn} onClick={onBack}>
          <ArrowLeft size={14} />
          返回应用
        </button>

        <div className={styles.navGroup}>
          <div className={styles.groupLabel}>通用</div>
          <button
            className={`${styles.navItem} ${activeTab === 'config' ? styles.navItemActive : ''}`}
            onClick={() => setActiveTab('config')}
          >
            <Sliders size={14} /> 配置 (模型 API Key)
          </button>
          <button
            className={`${styles.navItem} ${activeTab === 'account' ? styles.navItemActive : ''}`}
            onClick={() => setActiveTab('account')}
          >
            <UserRound size={14} /> 个人资料
          </button>
          <button
            className={`${styles.navItem} ${activeTab === 'usage' ? styles.navItemActive : ''}`}
            onClick={() => setActiveTab('usage')}
          >
            <ChartNoAxesColumnIncreasing size={14} /> Token 用量
          </button>
          <button
            className={`${styles.navItem} ${activeTab === 'backend' ? styles.navItemActive : ''}`}
            onClick={() => setActiveTab('backend')}
          >
            <Network size={14} /> 后端连接
          </button>
          <button
            className={`${styles.navItem} ${activeTab === 'context' ? styles.navItemActive : ''}`}
            onClick={() => setActiveTab('context')}
          >
            <BrainCircuit size={14} /> 个人上下文
          </button>
          <button
            className={`${styles.navItem} ${activeTab === 'commands' ? styles.navItemActive : ''}`}
            onClick={() => setActiveTab('commands')}
          >
            <Command size={14} /> 指令配置
          </button>
        </div>

        <div className={styles.navGroup}>
          <div className={styles.groupLabel}>功能</div>
          {[
            { id: 'calendar', label: '日历', icon: CalendarDays },
            { id: 'task', label: '任务', icon: Bell },
            { id: 'review', label: '生活复盘', icon: Sparkles }, { id: 'review-statistics', label: '统计复盘', icon: ChartNoAxesColumnIncreasing },
            { id: 'finance', label: '财务', icon: WalletCards },
            { id: 'library', label: '资料', icon: Library },
            { id: 'record', label: '记录', icon: NotebookPen },
            { id: 'daily-context', label: '天气与节假日', icon: CloudSun },
          ].map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              className={`${styles.navItem} ${activeTab === id ? styles.navItemActive : ''}`}
              onClick={() => setActiveTab(id)}
            >
              <Icon size={14} /> {label}
            </button>
          ))}
        </div>
      </div>

      {/* Main Settings Content */}
      <SettingsMainRegion contentClassName={`${styles.settingsContent} ${activeTab === 'config' ? styles.settingsContentConfig : ''}`}>
        {activeTab === 'config' && (
          <div className={styles.configContainer}>
            {/* 左侧列表主区域 */}
            <div className={styles.listPane}>
              {/* 顶部标题栏 */}
              <div className={styles.headerRow}>
                <div className={styles.titleArea}>
                  <h1>模型配置</h1>
                  <p>管理模型、端点与凭证，并设置默认推理模型。</p>
                </div>
                <Button
                  variant="primary"
                  icon={<Plus size={15} />}
                  onClick={handleStartAdd}
                >
                  添加模型
                </Button>
              </div>

              {/* 空状态提示 */}
              {allModels.length === 0 ? (
                <div className={styles.emptyState}>
                  <div className={styles.emptyIcon}>
                    <Inbox size={30} />
                  </div>
                  <h3 className={styles.emptyTitle}>暂未配置任何 AI 大模型</h3>
                  <p className={styles.emptyDesc}>
                    本地配置文件 <code>~/.butvan-agent/config.json</code> 当前无任何有效模型。请点击右侧表单添加您的第一个模型。
                  </p>
                  <Button variant="primary" icon={<Plus size={16} />} onClick={handleStartAdd}>
                    新增配置模型
                  </Button>
                </div>
              ) : (
                /* 模型列表表格 */
                <div className={styles.tableContainer}>
                  {/* 表头 */}
                  <div className={styles.tableHeader}>
                    <div className={styles.sortCol} onClick={handleToggleSort} title="点击按名称排序">
                      <span>模型名称</span>
                      <ChevronsUpDown size={13} />
                    </div>
                    <div>提供商</div>
                    <div>状态</div>
                    <div>最后测试</div>
                    <div style={{ textAlign: 'right' }}>操作</div>
                  </div>

                  {/* 表格内容 */}
                  <div className={styles.tableBody}>
                    {displayedModels.map((item) => {
                      const key = `${item.providerId}-${item.id}`;
                      const isSelected = isPaneOpen && !isCreating && selectedKey === key;
                      const isDefaultModel =
                        item.providerId === activeProviderId &&
                        item.id === activeModelId;
                      const vendorKey = (item.providerType || item.providerId || '').toLowerCase();
                      const vendorDisplayName = VENDOR_NAMES[vendorKey] || item.providerName || (vendorKey === 'dashscope' ? 'DashScope' : vendorKey);
                      const record = testRecords[key];
                      const isTestingThis = testingKey === key;

                      // 状态判断
                      const hasTested = !!record;

                      return (
                        <div
                          key={key}
                          className={`${styles.tableRow} ${isSelected ? styles.tableRowSelected : ''}`}
                          onClick={() => {
                            if (isPaneOpen && selectedKey === key && !isCreating) {
                              handleClosePane();
                            } else {
                              setIsCreating(false);
                              setSelectedKey(key);
                              setIsPaneOpen(true);
                            }
                          }}
                        >
                          {/* 模型名称列 */}
                          <div className={styles.nameCell}>
                            <div className={styles.modelIcon}>
                              <VendorIcon vendor={vendorKey} size={22} />
                            </div>
                            <div className={styles.nameInfo}>
                              <div className={styles.nameRow}>
                                <span className={styles.nameText}>{item.name || item.id}</span>
                                {isDefaultModel && <Badge variant="primary">默认</Badge>}
                              </div>
                              <span className={styles.subText}>{vendorDisplayName}</span>
                            </div>
                          </div>

                          {/* 提供商列 */}
                          <div className={styles.vendorCell}>{vendorDisplayName}</div>

                          {/* 状态列 */}
                          <div className={styles.statusCell}>
                            <span
                              className={`${styles.statusDot} ${hasTested
                                ? (record.success ? styles.statusDotConnected : styles.statusDotFailed)
                                : styles.statusDotUntested}`}
                            />
                            <span>{hasTested ? (record.success ? '已连接' : '连接失败') : '未测试'}</span>
                          </div>

                          {/* 最后测试列 */}
                          <div className={styles.lastTestedCell}>
                            {formatRelativeTime(record?.lastTestedAt)}
                          </div>

                          {/* 操作列 */}
                          <div className={styles.actionsCell} onClick={(e) => e.stopPropagation()}>
                            <button
                              type="button"
                              className={`${styles.rowTestBtn} ${isSelected ? styles.rowTestBtnActive : ''}`}
                              onClick={() => handleTestConnection(item)}
                              disabled={isTestingThis}
                            >
                              {isTestingThis ? '测试中...' : '测试'}
                            </button>

                            <button
                              type="button"
                              className={styles.rowMoreBtn}
                              onClick={(e) => {
                                e.stopPropagation();
                                setOpenRowMenuKey(openRowMenuKey === key ? null : key);
                              }}
                              aria-label="更多选项"
                            >
                              <MoreHorizontal size={15} />

                              {/* 行内快捷菜单 */}
                              {openRowMenuKey === key && (
                                <div className={styles.menuDropdown}>
                                  {!isDefaultModel && (
                                    <button
                                      type="button"
                                      className={styles.menuItem}
                                      onClick={() => {
                                        void setDefaultModel(item.providerId, item.id, item.name);
                                      }}
                                    >
                                      <Check size={14} /> 设为默认模型
                                    </button>
                                  )}
                                  <button
                                    type="button"
                                    className={styles.menuItem}
                                    onClick={() => handleCopyModelId(item.id)}
                                  >
                                    <Copy size={14} /> 复制 Model ID
                                  </button>
                                  <button
                                    type="button"
                                    className={styles.menuItem}
                                    onClick={() => handleTestConnection(item)}
                                  >
                                    <RotateCw size={14} /> 测试连接
                                  </button>
                                  <button
                                    type="button"
                                    className={`${styles.menuItem} ${styles.menuItemDanger}`}
                                    onClick={() => {
                                      setSelectedKey(key);
                                      setShowDeleteModal(true);
                                    }}
                                  >
                                    <Trash2 size={14} /> 删除模型
                                  </button>
                                </div>
                              )}
                            </button>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>

            {/* 右侧详情/编辑面板（受控展示/可收起） */}
            {isPaneOpen && (
              <div className={styles.detailPane}>
                {isCreating ? (
                  <>
                    {/* 新建模型头部 */}
                    <div className={styles.detailHeader}>
                      <div className={styles.detailIconTile}>
                        <VendorIcon vendor={editVendor} size={24} />
                      </div>
                      <div className={styles.detailHeaderContent}>
                        <div className={styles.detailHeaderTitleRow}>
                          <div className={styles.detailHeaderTitle}>
                            <span>添加新模型</span>
                          </div>
                          <button
                            type="button"
                            className={styles.closePaneBtn}
                            onClick={handleClosePane}
                            aria-label="收起面板"
                            title="收起"
                          >
                            <X size={16} />
                          </button>
                        </div>
                        <div className={styles.detailHeaderMeta}>
                          <span>配置新的 API 凭据与推理服务</span>
                        </div>
                      </div>
                    </div>

                    {/* 新建模型表单 */}
                    <div className={styles.detailForm}>
                      <FormField label="模型名称" htmlFor="create-model-name" required>
                        <TextInput
                          id="create-model-name"
                          value={editName}
                          onChange={(e) => {
                            setEditName(e.target.value);
                            if (!editModelId) setEditModelId(e.target.value);
                          }}
                          placeholder="例如 qwen-plus-2025-07-28"
                        />
                      </FormField>

                      <FormField label="提供商" htmlFor="create-model-vendor" required>
                        <Select
                          id="create-model-vendor"
                          options={vendorOptions}
                          value={editVendor}
                          onChange={(e) => changeVendor(e.target.value)}
                          icon={<VendorIcon vendor={editVendor} size={16} />}
                          fieldSize="md"
                          fullWidth
                        />
                      </FormField>

                      <ModelEndpointFields idPrefix="create-model" vendor={editVendor} baseUrl={editBaseUrl}
                        protocol={editProtocol} onBaseUrlChange={setEditBaseUrl} onProtocolChange={setEditProtocol} />

                      <FormField label="Model ID" htmlFor="create-model-id" required>
                        <TextInput
                          id="create-model-id"
                          value={editModelId}
                          onChange={(e) => setEditModelId(e.target.value)}
                          placeholder="例如 qwen-plus / deepseek-chat"
                        />
                      </FormField>

                      <FormField label="API Key" htmlFor="create-model-api-key" required>
                        <div className={styles.keyInputContainer}>
                          <TextInput
                            id="create-model-api-key"
                            type={showApiKey ? 'text' : 'password'}
                            value={editApiKey}
                            onChange={(e) => setEditApiKey(e.target.value)}
                            placeholder="sk-... / 密钥凭证"
                            autoComplete="off"
                          />
                          <button
                            type="button"
                            className={styles.eyeIconBtn}
                            onClick={(e) => {
                              e.preventDefault();
                              e.stopPropagation();
                              setShowApiKey((prev) => !prev);
                            }}
                            aria-label={showApiKey ? '隐藏 API Key' : '显示 API Key'}
                            title={showApiKey ? '隐藏 API Key' : '显示 API Key'}
                          >
                            {showApiKey ? <EyeOff size={15} /> : <Eye size={15} />}
                          </button>
                        </div>
                      </FormField>

                      <div className={styles.defaultModelRow}>
                        <div className={styles.defaultModelLabels}>
                          <span className={styles.defaultModelTitle}>支持图片识别</span>
                          <span className={styles.defaultModelDesc}>仅在该模型真的能看图时开启，系统据此判断能否接收图片</span>
                        </div>
                        <Toggle checked={editSupportsVision} onChange={setEditSupportsVision} aria-label="支持图片识别" />
                      </div>

                      <div className={styles.defaultModelRow}>
                        <div className={styles.defaultModelLabels}>
                          <span className={styles.defaultModelTitle}>设为默认模型</span>
                          <span className={styles.defaultModelDesc}>在新对话中默认使用此模型</span>
                        </div>
                        <Toggle checked={editIsDefault} onChange={setEditIsDefault} />
                      </div>
                    </div>

                    {/* 底部按钮栏 */}
                    <div className={styles.detailFooter}>
                      <Button variant="outline" disabled={testingKey !== null || !editModelId.trim()} onClick={() =>
                        handleTestConnection({ providerId: 'draft', providerType: editVendor, id: editModelId,
                          baseUrl: editBaseUrl, apiKey: editApiKey, protocol: editProtocol })}>
                        {testingKey ? '测试中...' : '测试连接'}
                      </Button>
                      <div className={styles.footerActionsRight}>
                        <Button variant="outline" onClick={handleCancel}>
                          取消
                        </Button>
                        <Button variant="primary" onClick={handleSave} disabled={saving}>
                          保存并创建
                        </Button>
                      </div>
                    </div>
                  </>
                ) : selectedModel ? (
                  <>
                    {/* 选中模型卡片头部 */}
                    <div className={styles.detailHeader}>
                      <div className={styles.detailIconTile}>
                        <VendorIcon vendor={editVendor} size={24} />
                      </div>
                      <div className={styles.detailHeaderContent}>
                        <div className={styles.detailHeaderTitleRow}>
                          <div className={styles.detailHeaderTitle}>
                            <span title={selectedModel.name || selectedModel.id}>{selectedModel.name || selectedModel.id}</span>
                            {editIsDefault && <Badge variant="primary">默认</Badge>}
                          </div>

                          <div className={styles.headerActionGroup}>
                            <div className={styles.headerMoreWrap}>
                              <button
                                type="button"
                                className={styles.headerMoreBtn}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setOpenHeaderMenu(!openHeaderMenu);
                                }}
                                aria-label="更多操作"
                              >
                                <MoreVertical size={16} />
                              </button>

                              {openHeaderMenu && (
                                <div className={styles.menuDropdown} onClick={(e) => e.stopPropagation()}>
                                  {!editIsDefault && (
                                    <button
                                      type="button"
                                      className={styles.menuItem}
                                      onClick={() => {
                                        void setDefaultModel(selectedModel.providerId, selectedModel.id, selectedModel.name);
                                        setOpenHeaderMenu(false);
                                      }}
                                    >
                                      <Check size={14} /> 设为默认模型
                                    </button>
                                  )}
                                  <button
                                    type="button"
                                    className={styles.menuItem}
                                    onClick={() => {
                                      handleCopyModelId(selectedModel.id);
                                      setOpenHeaderMenu(false);
                                    }}
                                  >
                                    <Copy size={14} /> 复制 Model ID
                                  </button>
                                  <button
                                    type="button"
                                    className={`${styles.menuItem} ${styles.menuItemDanger}`}
                                    onClick={() => {
                                      setOpenHeaderMenu(false);
                                      setShowDeleteModal(true);
                                    }}
                                  >
                                    <Trash2 size={14} /> 删除模型
                                  </button>
                                </div>
                              )}
                            </div>

                            <button
                              type="button"
                              className={styles.closePaneBtn}
                              onClick={handleClosePane}
                              aria-label="收起面板"
                              title="收起"
                            >
                              <X size={16} />
                            </button>
                          </div>
                        </div>

                        <div className={styles.detailHeaderMeta}>
                          <span>{VENDOR_NAMES[editVendor] || editVendor}</span>
                          <span>·</span>
                          <span
                            className={`${styles.statusDot} ${currentTestRecord
                              ? (currentTestRecord.success ? styles.statusDotConnected : styles.statusDotFailed)
                              : styles.statusDotUntested}`}
                          />
                          <span>{currentTestRecord ? (currentTestRecord.success ? '已连接' : '连接失败') : '未测试'}</span>
                        </div>
                      </div>
                    </div>

                    {/* 详情表单项 */}
                    <div className={styles.detailForm}>
                      <FormField label="模型名称" htmlFor="detail-model-name" required>
                        <TextInput
                          id="detail-model-name"
                          value={editName}
                          onChange={(e) => setEditName(e.target.value)}
                          placeholder="模型显示名称"
                        />
                      </FormField>

                      <FormField label="提供商" htmlFor="detail-model-vendor" required>
                        <Select
                          id="detail-model-vendor"
                          options={vendorOptions}
                          value={editVendor}
                          onChange={(e) => changeVendor(e.target.value)}
                          icon={<VendorIcon vendor={editVendor} size={16} />}
                          fieldSize="md"
                          fullWidth
                        />
                      </FormField>

                      <ModelEndpointFields idPrefix="detail-model" vendor={editVendor} baseUrl={editBaseUrl}
                        protocol={editProtocol} onBaseUrlChange={setEditBaseUrl} onProtocolChange={setEditProtocol} />

                      <FormField label="Model ID" htmlFor="detail-model-id" required>
                        <TextInput
                          id="detail-model-id"
                          value={editModelId}
                          onChange={(e) => setEditModelId(e.target.value)}
                          placeholder="Model ID"
                        />
                      </FormField>

                      <FormField label="API Key" htmlFor="detail-model-api-key" required>
                        <div className={styles.keyInputContainer}>
                          <TextInput
                            id="detail-model-api-key"
                            type={showApiKey ? 'text' : 'password'}
                            value={editApiKey}
                            onChange={(e) => setEditApiKey(e.target.value)}
                            placeholder="sk-... / 密钥凭证"
                            autoComplete="off"
                          />
                          <button
                            type="button"
                            className={styles.eyeIconBtn}
                            onClick={(e) => {
                              e.preventDefault();
                              e.stopPropagation();
                              setShowApiKey((prev) => !prev);
                            }}
                            aria-label={showApiKey ? '隐藏 API Key' : '显示 API Key'}
                            title={showApiKey ? '隐藏 API Key' : '显示 API Key'}
                          >
                            {showApiKey ? <EyeOff size={15} /> : <Eye size={15} />}
                          </button>
                        </div>
                      </FormField>

                      {/* 状态行 */}
                      <FormField label="状态">
                        <div className={styles.statusRowWrapper}>
                          <div className={styles.statusInfoBlock}>
                            <div className={styles.statusIndicatorText}>
                              <span
                                className={`${styles.statusDot} ${currentTestRecord
                                  ? (currentTestRecord.success ? styles.statusDotConnected : styles.statusDotFailed)
                                  : styles.statusDotUntested}`}
                              />
                              <span>{currentTestRecord ? (currentTestRecord.success ? '已连接' : '连接失败') : '未测试'}</span>
                            </div>
                            <span className={styles.statusSubText}>
                              最后测试: {formatAbsoluteTime(currentTestRecord?.lastTestedAt)}
                            </span>
                          </div>

                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            onClick={() =>
                              handleTestConnection({
                                providerId: selectedModel.providerId,
                                providerType: editVendor,
                                id: editModelId,
                                baseUrl: editBaseUrl,
                                apiKey: editApiKey,
                                protocol: editProtocol,
                              })
                            }
                            disabled={isCurrentTesting}
                          >
                            {isCurrentTesting ? '测试中...' : '测试连接'}
                          </Button>
                        </div>
                      </FormField>

                      {/* 图片识别能力标注行 */}
                      <div className={styles.defaultModelRow}>
                        <div className={styles.defaultModelLabels}>
                          <span className={styles.defaultModelTitle}>支持图片识别</span>
                          <span className={styles.defaultModelDesc}>仅在该模型真的能看图时开启，系统据此判断能否接收图片</span>
                        </div>
                        <Toggle checked={editSupportsVision} onChange={setEditSupportsVision} aria-label="支持图片识别" />
                      </div>

                      {/* 设为默认模型行 */}
                      <div className={styles.defaultModelRow}>
                        <div className={styles.defaultModelLabels}>
                          <span className={styles.defaultModelTitle}>设为默认模型</span>
                          <span className={styles.defaultModelDesc}>在新对话中默认使用此模型</span>
                        </div>
                        <Toggle checked={editIsDefault} onChange={setEditIsDefault} />
                      </div>
                    </div>

                    {/* 底部操作栏 */}
                    <div className={styles.detailFooter}>
                      <Button
                        type="button"
                        variant="ghost"
                        className={styles.deleteActionBtn}
                        icon={<Trash2 size={15} />}
                        onClick={() => setShowDeleteModal(true)}
                      >
                        删除模型
                      </Button>

                      <div className={styles.footerActionsRight}>
                        <Button type="button" variant="outline" onClick={handleCancel}>
                          取消
                        </Button>
                        <Button type="button" variant="primary" onClick={handleSave} disabled={saving}>
                          保存更改
                        </Button>
                      </div>
                    </div>
                  </>
                ) : null}
              </div>
            )}

            {/* 删除确认弹窗 */}
            <ConfirmDialog
              open={showDeleteModal}
              title="确认删除模型"
              tone="danger"
              confirmVariant="danger"
              confirmText="删除"
              description={
                <>
                  确定要删除模型 <strong>{selectedModel?.name || selectedModel?.id}</strong> 吗？
                  <br />
                  删除后将从配置文件中移除，无法在对话中继续使用此模型。
                </>
              }
              onClose={() => setShowDeleteModal(false)}
              onConfirm={handleConfirmDelete}
            />
          </div>
        )}

        {activeTab === 'account' && (
          <ProfileSettingsPage accountStatus={accountStatus} accountLoading={accountLoading} onAccountStatusChange={setAccountStatus} />
        )}

        {activeTab === 'usage' && <TokenUsageSettingsPage />}

        {activeTab === 'task' && <TaskSettings />}

        {(activeTab === 'review' || activeTab === 'review-statistics') && <ReviewWorkspace kind={activeTab === 'review-statistics' ? 'statistics' : 'life'} />}

        {activeTab === 'backend' && <BackendConnectionSettings />}

        {activeTab === 'context' && <PersonalContextSettingsPage />}

        {activeTab === 'commands' && <SlashCommandSettingsPage />}

        {activeTab === 'daily-context' && (
          <DailyContextSettingsPage
            chatTopBar={featurePreferences.chatTopBar}
            onChatTopBarChange={handleChatTopBarChange}
          />
        )}

        {isFeatureTab(activeTab) && (
          <FeatureSettingsPage
            tab={activeTab}
            studyWindowMode={featurePreferences.studyWindowMode}
            onStudyWindowModeChange={handleStudyWindowModeChange}
            chatTopBar={featurePreferences.chatTopBar}
            onChatTopBarChange={handleChatTopBarChange}
            calendar={featurePreferences.calendar}
            onCalendarChange={handleCalendarPreferenceChange}
          />
        )}
      </SettingsMainRegion>
    </div>
  );
};
