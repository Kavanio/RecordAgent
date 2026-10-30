import { STATE_TEXT, requestId, yesterday } from '../../features/review/statisticsReviewPresentation';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Download, Plus, RefreshCw, ShieldCheck, Sparkles, Trash2 } from 'lucide-react';
import { fetchFullModelConfig } from '../../services/api';
import {
  captureReviewFacts, correctReviewObservation, eraseReviewData, fetchReviewDetail, fetchReviewGoals,
  fetchReviewHistory, fetchReviewMemory, fetchReviewSettings, grantReviewConsent, previewReview,
  revokeReviewConsent, saveReviewGoal, saveReviewSettings, submitReviewFeedback,
} from '../../services/reviewApi';
import type {
  ReviewAction, ReviewDetail, ReviewDomain, ReviewGoal, ReviewMemory, ReviewObservation, ReviewPeriod,
  ReviewSettings, ReviewSummary, SaveReviewFeedback,
} from '../../types/review';
import type { FullModelConfig } from '../../types/model';
import { SettingsPageLayout } from '../settings/SettingsPageLayout';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { Button } from '../common/Button';
import { Select } from '../common/Select';
import { TextInput } from '../common/TextInput';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { ReviewFormModal } from './ReviewFormModal';
import { ReviewInteractionModals } from './ReviewInteractionModals';
import { useMessage } from '../common/Message';
import styles from './ReviewSettings.module.css';

const PERIODS: { id: ReviewPeriod; label: string }[] = [
  { id: 'DAILY_REPORT', label: '日报' },
  { id: 'WEEKLY_REPORT', label: '周报' },
  { id: 'MONTHLY_REPORT', label: '月报' },
];
const DOMAINS: { id: ReviewDomain; label: string }[] = [
  { id: 'FINANCE', label: '财务统计' },
  { id: 'TODO', label: '待办' },
  { id: 'STUDY', label: '学习' },
];
const DECISIONS: { id: 'ACCEPTED' | 'DECLINED' | 'DEFERRED'; label: string }[] = [
  { id: 'ACCEPTED', label: '采纳' },
  { id: 'DECLINED', label: '不适合' },
  { id: 'DEFERRED', label: '暂不决定' },
];
const RESULTS: { id: 'COMPLETED' | 'PARTIAL' | 'NOT_DONE' | 'UNKNOWN'; label: string }[] = [
  { id: 'COMPLETED', label: '完成' },
  { id: 'PARTIAL', label: '部分完成' },
  { id: 'NOT_DONE', label: '未完成' },
  { id: 'UNKNOWN', label: '无法判断' },
];
/** 长期复盘设置、授权、预览、历史与反馈；默认关闭，保存不等于授权。 */
export function ReviewSettings() {
  const [settings, setSettings] = useState<ReviewSettings | null>(null);
  const [goals, setGoals] = useState<ReviewGoal[]>([]);
  const [history, setHistory] = useState<ReviewSummary[]>([]);
  const [memory, setMemory] = useState<ReviewMemory>({ actions: [], observations: [] });
  const [providers, setProviders] = useState<FullModelConfig['providers']>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [consentOpen, setConsentOpen] = useState(false);
  const [configOpen, setConfigOpen] = useState(false);
  const [goalOpen, setGoalOpen] = useState(false);
  const [savedDraft, setSavedDraft] = useState<ReviewSettings | null>(null);
  const [goalTitle, setGoalTitle] = useState('');
  const [goalCriterion, setGoalCriterion] = useState('');
  const [goalConstraint, setGoalConstraint] = useState('');
  const [consent, setConsent] = useState(false);
  const [detail, setDetail] = useState<ReviewDetail | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [eraseOpen, setEraseOpen] = useState(false);
  const [feedbackTarget, setFeedbackTarget] = useState<{ action: ReviewAction; decision: SaveReviewFeedback['decision'] } | null>(null);
  const [feedbackText, setFeedbackText] = useState('');
  const [correctionTarget, setCorrectionTarget] = useState<ReviewObservation | null>(null);
  const [correctionText, setCorrectionText] = useState('');
  const { showMessage } = useMessage();

  const load = useCallback(async () => {
    setError('');
    try {
      const [nextSettings, nextGoals, nextHistory, nextMemory, config] = await Promise.all([
        fetchReviewSettings(), fetchReviewGoals(), fetchReviewHistory(20), fetchReviewMemory(),
        fetchFullModelConfig().catch(() => null),
      ]);
      setSettings(nextSettings);
      setGoals(nextGoals);
      setHistory(nextHistory);
      setMemory(nextMemory);
      setProviders(config?.providers ?? []);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '长期复盘设置读取失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const activeProvider = useMemo(
    () => providers.find(provider => provider.id === settings?.providerId),
    [providers, settings?.providerId],
  );
  const models = useMemo(
    () => (activeProvider?.models ?? []).filter(model => model.name && model.providerId !== '__none__'),
    [activeProvider],
  );

  async function act(work: () => Promise<unknown>, success: string) {
    if (busy) return;
    setBusy(true);
    try {
      await work();
      showMessage('success', success);
      await load();
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '操作失败');
    } finally {
      setBusy(false);
    }
  }

  function patch(next: Partial<ReviewSettings>) {
    setSettings(current => (current ? { ...current, ...next } : current));
  }

  function toggle<T extends string>(values: T[], value: T): T[] {
    return values.includes(value) ? values.filter(item => item !== value) : [...values, value];
  }

  function save() {
    if (!settings) return;
    void act(async () => { await saveReviewSettings({
      expectedRevision: settings.revision,
      enabled: settings.enabled,
      periods: settings.periods,
      domains: settings.domains,
      historyDays: settings.historyDays,
      dailyLimit: settings.dailyLimit,
      providerId: settings.providerId,
      modelName: settings.modelName,
    }); setConfigOpen(false); }, '长期复盘设置已保存；修改范围后需要重新授权');
  }

  function grant() {
    if (!settings || !consent) return;
    void act(async () => {
      await grantReviewConsent(settings.revision, settings.policyVersion, true);
      setConsent(false); setConsentOpen(false);
    }, '已按当前范围授予自动复盘权限');
  }

  function revoke() {
    if (!settings?.grant) return;
    void act(() => revokeReviewConsent(settings.grant!.id, settings.grant!.revision), '已撤销授权，未发送的 AI 邮件将改用基础报告');
  }

  function addGoal() {
    if (!goalTitle.trim()) return;
    void act(async () => {
      await saveReviewGoal({ expectedRevision: 0, title: goalTitle.trim(), constraintText: goalConstraint.trim(),
        successCriterion: goalCriterion.trim(), state: 'ACTIVE' });
      setGoalTitle(''); setGoalCriterion(''); setGoalConstraint(''); setGoalOpen(false);
    }, '目标已保存');
  }

  function archiveGoal(goal: ReviewGoal) {
    void act(() => saveReviewGoal({ expectedRevision: goal.revision, title: goal.title,
      constraintText: goal.constraintText, successCriterion: goal.successCriterion, state: 'ARCHIVED' }, goal.id),
      '目标已归档');
  }

  function runPreview() {
    if (!settings) return;
    setPreviewOpen(false);
    const day = yesterday();
    void act(async () => {
      const result = await previewReview({ requestId: requestId('preview'), period: 'DAILY_REPORT',
        from: day, to: day, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, confirmedPaidCall: true });
      setDetail(result);
      if (result.state !== 'SUCCEEDED') showMessage('info', result.message || '本次 AI 复盘未生成，数据不受影响');
    }, 'AI 复盘预览已生成，不会发送邮件');
  }

  function capture() {
    const day = yesterday();
    void act(() => captureReviewFacts(day, day, Intl.DateTimeFormat().resolvedOptions().timeZone), '已采集最近一天的事实摘要');
  }

  function openDetail(id: string) {
    void act(async () => setDetail(await fetchReviewDetail(id)), '复盘详情已读取');
  }

  function feedback() {
    if (!feedbackTarget) return;
    const { action, decision } = feedbackTarget;
    void act(async () => {
      await submitReviewFeedback(action.id, {
        requestId: requestId('feedback'), expectedRevision: action.revision,
        decision,
        title: action.title, criterion: action.criterion, checkOn: action.checkOn,
        feedback: feedbackText.trim(),
      });
      setFeedbackTarget(null); setFeedbackText('');
    }, '反馈已保存');
  }

  function correct() {
    if (!correctionTarget) return;
    void act(async () => {
      await correctReviewObservation(correctionTarget.id, {
        requestId: requestId('correction'), expectedRevision: correctionTarget.revision,
        decision: correctionText.trim() ? 'CORRECTED' : 'REJECTED', correction: correctionText.trim(),
      });
      setCorrectionTarget(null); setCorrectionText('');
    }, '观察已处理，后续不会再作为自动召回证据');
  }

  const waiting = settings ? !settings.authorized : true;

  return (
    <SettingsPageLayout
      title="长期复盘"
      description="结合历史统计、你的目标与以往建议，在每个报告周期给出有依据的下一步；默认关闭。"
      loading={loading}
      actions={<Button variant="outline" size="sm" icon={<RefreshCw size={14} />} disabled={busy} onClick={() => void load()}>刷新</Button>}
    >
      {error && <p className={styles.error} role="alert">{error}</p>}

      {settings && (
        <>
          <SettingsGroup title="统计复盘配置" description="使用独立的统计摘要授权，按需调整周期、数据范围与固定模型。">
            <SettingsRow label={settings.enabled ? '统计复盘已启用' : '统计复盘未启用'} description={`${settings.modelName || '尚未选择模型'} · 回看${settings.historyDays}天 · 每日上限${settings.dailyLimit}次`}
              control={<Button size="sm" disabled={busy} onClick={() => { setSavedDraft(settings); setConfigOpen(true); }}>配置统计复盘</Button>} />
          </SettingsGroup>
          <ReviewFormModal open={configOpen} title="配置统计复盘" busy={busy} onClose={() => { setSettings(savedDraft); setConfigOpen(false); }}>
          <SettingsGroup title="生成范围" description="保存配置不会调用模型；扩大范围后需要重新授权。">
            <SettingsRow label="启用长期复盘" description="关闭后不再排队生成，也不发送 AI 内容；已有数据保留。"
              control={<input type="checkbox" checked={settings.enabled} disabled={busy}
                onChange={event => patch({ enabled: event.target.checked })} aria-label="启用长期复盘" />} />
            <SettingsRow label="报告周期" layout="stacked" description="仅对勾选的报告类型生成 AI 复盘。"
              control={<div className={styles.chips}>{PERIODS.map(item => (
                <label key={item.id} className={styles.chip}>
                  <input type="checkbox" checked={settings.periods.includes(item.id)} disabled={busy}
                    onChange={() => patch({ periods: toggle(settings.periods, item.id) })} />{item.label}
                </label>))}</div>} />
            <SettingsRow label="数据范围" layout="stacked" description="只读取统计摘要，不读取订单备注、资料或聊天正文。"
              control={<div className={styles.chips}>{DOMAINS.map(item => (
                <label key={item.id} className={styles.chip}>
                  <input type="checkbox" checked={settings.domains.includes(item.id)} disabled={busy}
                    onChange={() => patch({ domains: toggle(settings.domains, item.id) })} />{item.label}
                </label>))}</div>} />
            <SettingsRow label="最多回看" description="事实摘要只采集已结束自然日，最大 180 天。"
              control={<TextInput className={styles.number} aria-label="最多回看天数" type="number" min={1} max={180} value={settings.historyDays}
                disabled={busy} onChange={event => patch({ historyDays: Number(event.target.value) })} />} />
            <SettingsRow label="每天自动生成上限" description="用于限制付费调用；失败不会自动重试。"
              control={<TextInput className={styles.number} aria-label="每天自动生成上限" type="number" min={1} max={3} value={settings.dailyLimit}
                disabled={busy} onChange={event => patch({ dailyLimit: Number(event.target.value) })} />} />
            <SettingsRow label="复盘模型" layout="stacked" description="固定使用所选模型，不跟随聊天默认模型切换。"
              control={<div className={styles.selects}>
                <Select label="模型连接" placeholder="请选择模型连接" fieldSize="md" fullWidth
                  value={settings.providerId} disabled={busy}
                  description="选择已配置的服务连接。"
                  options={providers.filter(provider => provider.isEnabled).map(provider => ({
                    value: provider.id, label: provider.name || provider.id,
                  }))}
                  onChange={event => patch({ providerId: event.target.value, modelName: '' })} />
                <Select label="具体模型" placeholder="请选择具体模型" fieldSize="md" fullWidth
                  value={settings.modelName} disabled={busy || !activeProvider || models.length === 0}
                  description={!activeProvider ? '请先选择左侧模型连接。'
                    : models.length === 0 ? '此连接暂无可选模型，请先到模型配置中添加。' : '选择该连接用于复盘的模型。'}
                  options={models.map(model => ({ value: model.name || model.id, label: model.name || model.id }))}
                  onChange={event => patch({ modelName: event.target.value })} />
              </div>} />
            <SettingsRow label="" control={<Button variant="primary" size="sm" disabled={busy} onClick={save}>保存设置</Button>} />
          </SettingsGroup>

          </ReviewFormModal>
          <SettingsGroup title="授权" description="长期复盘需要单独授权，不会复用聊天中的逐次确认。">
            <SettingsRow label="当前状态" description={settings.authorized
              ? `已授权 ${settings.grant?.periods.join('、')}；撤销后不再采集或生成。`
              : '尚未授权，或配置范围已扩大、模型接收方已变化。'}
              control={<span className={settings.authorized ? styles.ok : styles.warn}>{settings.authorized ? '已授权' : '未授权'}</span>} />
            {!settings.authorized && <SettingsRow label="确认范围" description="核对已保存的范围与固定模型后再授权。" control={<Button size="sm" disabled={busy} onClick={() => { setConsent(false); setConsentOpen(true); }}>查看统计授权</Button>} />}
            <ReviewFormModal open={consentOpen} title="确认统计复盘授权" busy={busy} onClose={() => { setConsent(false); setConsentOpen(false); }}>
              <p className={styles.muted}>固定模型：{settings.providerId} / {settings.modelName} · 回看{settings.historyDays}天 · 每日上限{settings.dailyLimit}次</p>
            {!settings.authorized && (
              <SettingsRow label="确认授权" layout="stacked" description="我确认上述数据范围、周期、回看窗口和固定模型接收方，并了解生成会把统计摘要发送给该模型。"
                control={<div className={styles.stack}>
                  <label className={styles.chip}><input type="checkbox" checked={consent} disabled={busy}
                    onChange={event => setConsent(event.target.checked)} />我已阅读并同意</label>
                  <Button variant="primary" size="sm" disabled={busy || !consent} icon={<ShieldCheck size={14} />} onClick={grant}>授权</Button>
                </div>} />
            )}
            </ReviewFormModal>
            {settings.authorized && (
              <SettingsRow label="撤销授权" description="撤销立即生效；已发送的邮件无法撤回。"
                control={<Button variant="outline" size="sm" disabled={busy} onClick={revoke}>撤销</Button>} />
            )}
          </SettingsGroup>

          <SettingsGroup title="我的目标" description="目标只由你维护，模型只能引用，不能改写。最多 3 个有效目标。">
            {goals.map(goal => (
              <SettingsRow key={goal.id} label={goal.title} description={`${goal.constraintText || '无额外约束'} · ${goal.successCriterion || '未填写衡量方式'} · ${STATE_TEXT[goal.state] ?? goal.state}`}
                control={goal.state === 'ACTIVE'
                  ? <Button variant="ghost" size="sm" disabled={busy} onClick={() => archiveGoal(goal)}>归档</Button>
                  : <span className={styles.muted}>已归档</span>} />
            ))}
            <SettingsRow label="添加目标" description="由你明确填写目标与约束，不由模型自动创建。" control={<Button size="sm" disabled={busy} onClick={() => { setGoalTitle(''); setGoalConstraint(''); setGoalCriterion(''); setGoalOpen(true); }}>新增目标</Button>} />
            <ReviewFormModal open={goalOpen} title="新增复盘目标" busy={busy} onClose={() => setGoalOpen(false)}>
            <SettingsRow label="新增目标" layout="stacked"
              control={<div className={styles.stack}>
                <TextInput aria-label="目标名称" value={goalTitle} maxLength={200} placeholder="例如：每周完成三次学习" disabled={busy}
                  onChange={event => setGoalTitle(event.target.value)} />
                <TextInput aria-label="目标约束" value={goalConstraint} maxLength={500} placeholder="约束，例如：不牺牲睡眠" disabled={busy}
                  onChange={event => setGoalConstraint(event.target.value)} />
                <TextInput aria-label="目标衡量方式" value={goalCriterion} maxLength={300} placeholder="怎样算有进展" disabled={busy}
                  onChange={event => setGoalCriterion(event.target.value)} />
                <Button variant="outline" size="sm" icon={<Plus size={14} />} disabled={busy || !goalTitle.trim()} onClick={addGoal}>添加目标</Button>
              </div>} />
            </ReviewFormModal>
          </SettingsGroup>

          <SettingsGroup title="预览与数据" description="预览会真实调用模型并产生费用，不发送邮件，也不写入长期建议。">
            <SettingsRow label="生成一次预览" description="使用昨天的已结束自然日，明确确认后才调用模型。"
              control={<Button variant="primary" size="sm" icon={<Sparkles size={14} />} disabled={busy || waiting}
                onClick={() => setPreviewOpen(true)}>生成预览</Button>} />
            <SettingsRow label="采集事实摘要" description="只读取已授权统计范围，不调用模型。"
              control={<Button variant="outline" size="sm" icon={<Download size={14} />} disabled={busy || waiting} onClick={capture}>采集昨天</Button>} />
            <SettingsRow label="清除复盘数据" description="撤销授权并删除本机复盘摘要、目标和建议；聊天与账本不受影响。"
              control={<Button variant="danger" size="sm" icon={<Trash2 size={14} />} disabled={busy} onClick={() => setEraseOpen(true)}>清除</Button>} />
          </SettingsGroup>

          <SettingsGroup title="复盘历史" description="点击查看依据；历史不会自动重新调用模型。">
            {history.length === 0 && <p className={styles.muted}>还没有生成过复盘。</p>}
            {history.map(item => (
              <SettingsRow key={item.id} label={`${PERIODS.find(period => period.id === item.period)?.label ?? item.period} · ${item.from}${item.to !== item.from ? ` — ${item.to}` : ''}`}
                description={`${STATE_TEXT[item.state] ?? item.state}${item.message ? ` · ${item.message}` : ''}${item.preview ? ' · 预览' : ''}`}
                control={<Button variant="ghost" size="sm" disabled={busy} onClick={() => openDetail(item.id)}>查看</Button>} />
            ))}
          </SettingsGroup>

          <SettingsGroup title="建议与观察" description="反馈由你提交；未反馈不会被判定为失败。">
            {memory.actions.length === 0 && memory.observations.length === 0 && <p className={styles.muted}>还没有建议。</p>}
            {memory.actions.map(action => (
              <div key={action.id} className={styles.card}>
                <div className={styles.cardHead}>
                  <strong>{action.title}</strong>
                  <span className={styles.badge}>{STATE_TEXT[action.state] ?? action.state}</span>
                </div>
                {action.rationale && <p className={styles.muted}>{action.rationale}</p>}
                {action.criterion && <p className={styles.muted}>检查：{action.criterion}{action.checkOn ? ` · ${action.checkOn}` : ''}</p>}
                {action.feedback && <p className={styles.muted}>我的反馈：{action.feedback}</p>}
                <div className={styles.chips}>
                  {DECISIONS.map(item => (
                    <Button key={item.id} variant="outline" size="sm" disabled={busy}
                      onClick={() => { setFeedbackTarget({ action, decision: item.id }); setFeedbackText(''); }}>{item.label}</Button>
                  ))}
                  {['ACCEPTED', 'DEFERRED', 'COMPLETED', 'PARTIAL', 'NOT_DONE', 'UNKNOWN'].includes(action.state) && RESULTS.map(item => (
                    <Button key={item.id} variant="ghost" size="sm" disabled={busy}
                      onClick={() => { setFeedbackTarget({ action, decision: item.id }); setFeedbackText(''); }}>{item.label}</Button>
                  ))}
                </div>
              </div>
            ))}
            {memory.observations.map(observation => (
              <div key={observation.id} className={styles.card}>
                <div className={styles.cardHead}>
                  <strong>{observation.content}</strong>
                  <span className={styles.badge}>{STATE_TEXT[observation.state] ?? observation.state}</span>
                </div>
                {observation.uncertainty && <p className={styles.muted}>仍不确定：{observation.uncertainty}</p>}
                {observation.correction && <p className={styles.muted}>纠正：{observation.correction}</p>}
                {observation.state === 'CANDIDATE' && (
                  <div className={styles.chips}>
                    <Button variant="outline" size="sm" disabled={busy}
                      onClick={() => { setCorrectionTarget(observation); setCorrectionText(''); }}>纠正或否定</Button>
                  </div>
                )}
              </div>
            ))}
          </SettingsGroup>
        </>
      )}

      <ConfirmDialog open={previewOpen} title="生成 AI 复盘预览" tone="info" confirmText="确认生成"
        description="将调用你选择的模型一次，可能产生费用；只生成预览，不发送邮件，也不会保存为长期建议。"
        onClose={() => setPreviewOpen(false)} onConfirm={runPreview} />
      <ConfirmDialog open={eraseOpen} title="清除长期复盘数据" tone="danger" confirmText="确认清除" confirmVariant="danger"
        busy={busy} description="将撤销授权、关闭长期复盘，并删除本机复盘摘要、目标、建议和观察。聊天记录和账本不受影响。"
        onClose={() => setEraseOpen(false)}
        onConfirm={() => {
          if (!settings) return;
          void act(async () => {
            await eraseReviewData(settings.revision, true); setEraseOpen(false);
          }, '长期复盘数据已清除');
        }} />

      <ReviewInteractionModals detail={detail} feedbackDecision={feedbackTarget ? STATE_TEXT[feedbackTarget.decision] || feedbackTarget.decision : null}
        correctionOpen={Boolean(correctionTarget)} busy={busy} feedbackText={feedbackText} correctionText={correctionText}
        setFeedbackText={setFeedbackText} setCorrectionText={setCorrectionText} closeFeedback={() => setFeedbackTarget(null)}
        closeCorrection={() => setCorrectionTarget(null)} closeDetail={() => setDetail(null)} feedback={feedback} correct={correct} />
    </SettingsPageLayout>
  );
}
