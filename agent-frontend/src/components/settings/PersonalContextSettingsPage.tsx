import { useEffect, useState } from 'react';
import { BrainCircuit, Check, Database, FileText, PauseCircle, RefreshCw, Save, Sparkles, Trash2, X } from 'lucide-react';
import {
  acceptProfileProposal, checkProfileMaintenance, clearPersonalContextProfile,
  fetchPersonalContext, fetchProfileMaintenance, rejectProfileProposal,
  updatePersonalContextEnabled, updatePersonalContextProfile, updateProfileMaintenanceEnabled,
} from '../../services/personalContextService';
import type { PersonalContextSettings, ProfileMaintenanceStatus } from '../../types/personalContext';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { LeverSwitch } from '../common/LeverSwitch';
import { formatTokenCount } from '../chat/tokenUsageFormat';
import { AgentResetSection } from '../reset/AgentResetSection';
import { ExperienceMemorySettings } from '../memory/ExperienceMemorySettings';
import { SettingsPageLayout } from './SettingsPageLayout';
import styles from './PersonalContextSettingsPage.module.css';

const RESULT_LABELS: Record<ProfileMaintenanceStatus['lastResult'], string> = {
  never_checked: '尚未检查', no_evidence: '没有可用的新记忆', no_changes: '画像无需更新',
  proposal_ready: '有一份提案待审核', accepted: '最近提案已应用', rejected: '最近提案已忽略',
  profile_too_large: '画像较长，请先手工精简后再检查', failed: '最近检查失败，可手动重试',
};
const OPERATION_LABELS = { ADD: '新增', UPDATE: '更新', DELETE: '删除' } as const;

/** 用户可控的稳定画像、相关记忆注入与辅助维护设置。 */
export function PersonalContextSettingsPage() {
  const { showMessage } = useMessage();
  const [settings, setSettings] = useState<PersonalContextSettings | null>(null);
  const [maintenance, setMaintenance] = useState<ProfileMaintenanceStatus | null>(null);
  const [draft, setDraft] = useState('');
  const [proposalDraft, setProposalDraft] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmingClear, setConfirmingClear] = useState(false);

  const applySettings = (next: PersonalContextSettings) => {
    setSettings(next);
    setDraft(next.profile);
  };
  const applyMaintenance = (next: ProfileMaintenanceStatus) => {
    setMaintenance(next);
    setProposalDraft(next.pendingProposal?.proposedProfile ?? '');
    setSettings((current) => current ? { ...current, maintenanceEnabled: next.enabled } : current);
  };
  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [context, maintenanceStatus] = await Promise.all([fetchPersonalContext(), fetchProfileMaintenance()]);
      applySettings(context);
      applyMaintenance(maintenanceStatus);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '读取个人上下文失败');
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { void load(); }, []);

  const mutateSettings = async (operation: () => Promise<PersonalContextSettings>, success: string) => {
    setSaving(true); setError(null);
    try {
      applySettings(await operation());
      applyMaintenance(await fetchProfileMaintenance());
      showMessage('success', success);
      return true;
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '保存个人上下文失败');
      return false;
    } finally { setSaving(false); }
  };
  const toggleMaintenance = async (enabled: boolean) => {
    setSaving(true); setError(null);
    try {
      applyMaintenance(await updateProfileMaintenanceEnabled(enabled));
      showMessage('success', enabled ? '已开启画像辅助维护' : '已暂停画像辅助维护');
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '更新画像维护开关失败');
    } finally { setSaving(false); }
  };
  const checkNow = async () => {
    setChecking(true); setError(null);
    try {
      const next = await checkProfileMaintenance();
      applyMaintenance(next);
      showMessage('success', next.pendingProposal ? '检查完成，请审核新的画像提案' : RESULT_LABELS[next.lastResult]);
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '检查画像变化失败，请确认模型配置后重试');
    } finally { setChecking(false); }
  };
  const acceptProposal = async () => {
    const proposal = maintenance?.pendingProposal;
    if (!proposal) return;
    setSaving(true); setError(null);
    try {
      applySettings(await acceptProfileProposal(proposal.id, proposal.baseRevision, proposalDraft));
      applyMaintenance(await fetchProfileMaintenance());
      showMessage('success', '画像提案已应用，并保留了上一版本');
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '应用画像提案失败，请重新检查');
    } finally { setSaving(false); }
  };
  const rejectProposal = async () => {
    const proposal = maintenance?.pendingProposal;
    if (!proposal) return;
    setSaving(true); setError(null);
    try {
      applyMaintenance(await rejectProfileProposal(proposal.id));
      showMessage('success', '已忽略这份提案，当前画像没有变化');
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '忽略画像提案失败');
    } finally { setSaving(false); }
  };

  if (loading && !settings) return <SettingsPageLayout title="个人上下文" description="控制 Agent 自动了解你的方式。"><div className={styles.state} role="status">正在读取本地设置…</div></SettingsPageLayout>;
  if (!settings || !maintenance) return <SettingsPageLayout title="个人上下文" description="控制 Agent 自动了解你的方式。"><div className={styles.state} role="alert"><p>{error}</p><Button variant="outline" onClick={() => void load()}>重新加载</Button></div></SettingsPageLayout>;

  const dirty = draft !== settings.profile;
  const atLimit = draft.length >= settings.maxProfileChars;
  const proposal = maintenance.pendingProposal;
  const proposalStale = proposal != null && proposal.baseRevision !== settings.revision;
  const sourceLabel = settings.source === 'legacy' ? '来自旧版 MEMORY.md' : settings.source === 'explicit' ? '由你维护' : '尚未填写';
  const lastChecked = maintenance.lastCheckedAt
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(maintenance.lastCheckedAt)) : null;

  return <SettingsPageLayout title="个人上下文" description="管理 Agent 对你的了解和记忆。">
    <div className={styles.content}>
      <section className={styles.switchRow} aria-labelledby="context-switch-title">
        <div className={styles.switchCopy}><span className={styles.icon}><BrainCircuit size={18} aria-hidden="true" /></span><div><h2 id="context-switch-title">自动使用个人上下文</h2><p>{settings.enabled ? '回答时参考你的个人信息和相关经历。' : '已暂停使用，已有个人信息和记忆不会删除。'}</p></div></div>
        <LeverSwitch checked={settings.enabled} disabled={saving} label={settings.enabled ? '已开启' : '已暂停'} onChange={(enabled) => void mutateSettings(() => updatePersonalContextEnabled(enabled), enabled ? '已开启个人上下文' : '已暂停个人上下文')} />
      </section>

      <dl className={styles.budgets} aria-label="单次调用 Token 硬预算">
        <div><dt><FileText size={14} />个人画像</dt><dd>≤ {formatTokenCount(settings.profileTokenBudget)}</dd></div>
        <div><dt><Database size={14} />相关记忆</dt><dd>≤ {formatTokenCount(settings.memoryTokenBudget)}</dd></div>
        <div><dt><PauseCircle size={14} />合计上限</dt><dd>≤ {formatTokenCount(settings.totalTokenBudget)}</dd></div>
      </dl>

      <section className={styles.editor} aria-labelledby="profile-editor-title">
        <div className={styles.editorHeading}><div><h2 id="profile-editor-title">个人画像</h2><p>写下你的偏好和习惯，请勿填写密码或密钥。</p></div><span>{sourceLabel}</span></div>
        {settings.source === 'legacy' && <p className={styles.legacyNote}>这是旧版保存的个人信息，保存后可在这里继续编辑。</p>}
        <textarea className={styles.textarea} value={draft} maxLength={settings.maxProfileChars} onChange={(event) => setDraft(event.target.value)} placeholder={'例如：\n- 偏好简短、先给结论的中文回复\n- 常用 React、TypeScript 与 Spring Boot\n- 做重大取舍前希望先看到方案与风险'} aria-describedby="profile-help" />
        <div className={styles.editorFooter} id="profile-help"><span className={atLimit ? styles.limit : ''}>{draft.length.toLocaleString()} / {settings.maxProfileChars.toLocaleString()} 字符 · 已保存画像约 {formatTokenCount(settings.estimatedTokens)} tokens</span><div className={styles.actions}><Button variant="ghost" icon={<Trash2 size={14} />} disabled={saving || (!draft && settings.source === 'explicit')} onClick={() => setConfirmingClear(true)}>清空画像</Button><Button variant="primary" icon={<Save size={14} />} disabled={saving || !dirty} onClick={() => void mutateSettings(() => updatePersonalContextProfile(draft), '个人画像已保存')}>{saving ? '正在保存' : '保存画像'}</Button></div></div>
      </section>

      <section className={styles.maintenance} aria-labelledby="maintenance-title">
        <div className={styles.maintenanceHeader}>
          <div className={styles.switchCopy}><span className={styles.icon}><Sparkles size={18} aria-hidden="true" /></span><div><h2 id="maintenance-title">画像辅助维护</h2><p>根据新记忆建议更新个人信息，你确认后才修改。</p></div></div>
          <LeverSwitch checked={maintenance.enabled} disabled={saving || checking} label={maintenance.enabled ? '已开启' : '已关闭'} onChange={(enabled) => void toggleMaintenance(enabled)} />
        </div>
        <div className={styles.maintenanceMeta}>
          <span>{RESULT_LABELS[maintenance.lastResult]}{lastChecked ? ` · ${lastChecked}` : ''}</span>
          <Button variant="outline" size="sm" icon={<RefreshCw size={14} />} disabled={saving || checking || Boolean(proposal)} onClick={() => void checkNow()}>{checking ? '正在检查' : proposal ? '请先审核提案' : '立即检查'}</Button>
        </div>

        {proposal && <div className={styles.proposal}>
          <div className={styles.proposalHeading}><div><h3>待审核画像提案</h3><p>{proposal.summary || '发现可能需要更新的长期信息。'}</p></div><span>{proposal.changes.length} 项变化</span></div>
          {proposalStale && <p className={styles.stale} role="alert">你已在提案生成后修改画像。请忽略本提案，再重新检查。</p>}
          <ul className={styles.changeList}>{proposal.changes.map((change, index) => <li key={`${change.section}-${index}`}>
            <div className={styles.changeTitle}><span data-operation={change.operation}>{OPERATION_LABELS[change.operation]}</span><strong>{change.section || '未分类'}</strong><small>{Math.round(change.confidence * 100)}% 置信度</small></div>
            <p>{change.reason}</p>
            {(change.before || change.after) && <div className={styles.delta}>{change.before && <span><b>原</b>{change.before}</span>}{change.after && <span><b>新</b>{change.after}</span>}</div>}
            <div className={styles.sources}>依据：{change.sourceIds.map((source) => <code key={source}>{source}</code>)}</div>
          </li>)}</ul>
          <label className={styles.proposalEditor} htmlFor="proposal-profile"><span>确认后的完整画像</span><small>你可以先编辑，再应用。</small></label>
          <textarea id="proposal-profile" className={`${styles.textarea} ${styles.proposalTextarea}`} value={proposalDraft} maxLength={settings.maxProfileChars} onChange={(event) => setProposalDraft(event.target.value)} />
          <div className={styles.proposalActions}><Button variant="ghost" icon={<X size={14} />} disabled={saving} onClick={() => void rejectProposal()}>忽略提案</Button><Button variant="primary" icon={<Check size={14} />} disabled={saving || proposalStale} onClick={() => void acceptProposal()}>{saving ? '正在应用' : '确认并应用'}</Button></div>
        </div>}
        {!proposal && <p className={styles.emptyProposal}>暂无更新建议，开启后最多每天自动检查一次新记忆。</p>}
      </section>

      <ExperienceMemorySettings />
      <AgentResetSection />
      <p className={styles.privacy}>个人信息和更新建议保存在本机，暂停使用不会删除。</p>
    </div>

    <ConfirmDialog
      open={confirmingClear}
      title="清空个人画像"
      tone="danger"
      confirmText="确认清空"
      confirmVariant="danger"
      busy={saving}
      description="清空后，Agent 将不再自动使用当前画像。历史记忆文件不会被删除。"
      onClose={() => setConfirmingClear(false)}
      onConfirm={() => {
        void mutateSettings(clearPersonalContextProfile, '个人画像已清空').then((ok) => {
          if (ok) setConfirmingClear(false);
        });
      }}
    />

  </SettingsPageLayout>;
}
