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
import { ConfirmDialog } from '../common/ConfirmDialog';
import { Modal } from '../common/Modal';
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
const STATE_TEXT: Record<string, string> = {
  PENDING: '排队中', RUNNING: '生成中', SUCCEEDED: '已完成', DEGRADED: '已降级',
  SKIPPED: '已跳过', UNKNOWN: '结果未知', PROPOSED: '待决定', ACCEPTED: '已采纳',
  DECLINED: '不适合', DEFERRED: '暂不决定', COMPLETED: '已完成', PARTIAL: '部分完成',
  NOT_DONE: '未完成', ARCHIVED: '已归档', CANDIDATE: '候选观察', CORRECTED: '已纠正', REJECTED: '已否定',
};

function requestId(prefix: string) {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}
function yesterday(): string {
  const value = new Date();
  value.setDate(value.getDate() - 1);
  return value.toISOString().slice(0, 10);
}

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
    void act(() => saveReviewSettings({
      expectedRevision: settings.revision,
      enabled: settings.enabled,
      periods: settings.periods,
      domains: settings.domains,
      historyDays: settings.historyDays,
      dailyLimit: settings.dailyLimit,
      providerId: settings.providerId,
      modelName: settings.modelName,
    }), '长期复盘设置已保存；修改范围后需要重新授权');
  }

  function grant() {
    if (!settings || !consent) return;
    void act(async () => {
      await grantReviewConsent(settings.revision, settings.policyVersion, true);
      setConsent(false);
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
      setGoalTitle(''); setGoalCriterion(''); setGoalConstraint('');
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
              control={<input className={styles.number} type="number" min={1} max={180} value={settings.historyDays}
                disabled={busy} onChange={event => patch({ historyDays: Number(event.target.value) })} />} />
            <SettingsRow label="每天自动生成上限" description="用于限制付费调用；失败不会自动重试。"
              control={<input className={styles.number} type="number" min={1} max={3} value={settings.dailyLimit}
                disabled={busy} onChange={event => patch({ dailyLimit: Number(event.target.value) })} />} />
            <SettingsRow label="复盘模型" layout="stacked" description="固定使用所选模型，不跟随聊天默认模型切换。"
              control={<div className={styles.selects}>
                <select aria-label="模型连接" value={settings.providerId} disabled={busy}
                  onChange={event => patch({ providerId: event.target.value, modelName: '' })}>
                  <option value="">请选择模型连接</option>
                  {providers.filter(provider => provider.isEnabled).map(provider => (
                    <option key={provider.id} value={provider.id}>{provider.name || provider.id}</option>))}
                </select>
                <select aria-label="模型" value={settings.modelName} disabled={busy || !activeProvider}
                  onChange={event => patch({ modelName: event.target.value })}>
                  <option value="">请选择模型</option>
                  {models.map(model => <option key={model.id} value={model.name || model.id}>{model.name || model.id}</option>)}
                </select>
              </div>} />
            <SettingsRow label="" control={<Button variant="primary" size="sm" disabled={busy} onClick={save}>保存设置</Button>} />
          </SettingsGroup>

          <SettingsGroup title="授权" description="长期复盘需要单独授权，不会复用聊天中的逐次确认。">
            <SettingsRow label="当前状态" description={settings.authorized
              ? `已授权 ${settings.grant?.periods.join('、')}；撤销后不再采集或生成。`
              : '尚未授权，或配置范围已扩大、模型接收方已变化。'}
              control={<span className={settings.authorized ? styles.ok : styles.warn}>{settings.authorized ? '已授权' : '未授权'}</span>} />
            {!settings.authorized && (
              <SettingsRow label="确认授权" layout="stacked" description="我确认上述数据范围、周期、回看窗口和固定模型接收方，并了解生成会把统计摘要发送给该模型。"
                control={<div className={styles.stack}>
                  <label className={styles.chip}><input type="checkbox" checked={consent} disabled={busy}
                    onChange={event => setConsent(event.target.checked)} />我已阅读并同意</label>
                  <Button variant="primary" size="sm" disabled={busy || !consent} icon={<ShieldCheck size={14} />} onClick={grant}>授权</Button>
                </div>} />
            )}
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
            <SettingsRow label="新增目标" layout="stacked"
              control={<div className={styles.stack}>
                <input value={goalTitle} maxLength={200} placeholder="例如：每周完成三次学习" disabled={busy}
                  onChange={event => setGoalTitle(event.target.value)} />
                <input value={goalConstraint} maxLength={500} placeholder="约束，例如：不牺牲睡眠" disabled={busy}
                  onChange={event => setGoalConstraint(event.target.value)} />
                <input value={goalCriterion} maxLength={300} placeholder="怎样算有进展" disabled={busy}
                  onChange={event => setGoalCriterion(event.target.value)} />
                <Button variant="outline" size="sm" icon={<Plus size={14} />} disabled={busy || !goalTitle.trim()} onClick={addGoal}>添加目标</Button>
              </div>} />
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

      <Modal open={Boolean(feedbackTarget)} title="提交反馈" onClose={() => setFeedbackTarget(null)} width={460}>
        <p className={styles.muted}>决策：{STATE_TEXT[feedbackTarget?.decision ?? ''] ?? ''}</p>
        <textarea value={feedbackText} maxLength={500} rows={3} placeholder="可选：补充原因或调整后的做法，不要填写敏感信息。"
          onChange={event => setFeedbackText(event.target.value)} />
        <div className={styles.modalActions}>
          <Button variant="ghost" size="sm" onClick={() => setFeedbackTarget(null)}>取消</Button>
          <Button variant="primary" size="sm" disabled={busy} onClick={feedback}>保存反馈</Button>
        </div>
      </Modal>

      <Modal open={Boolean(correctionTarget)} title="纠正或否定观察" onClose={() => setCorrectionTarget(null)} width={460}>
        <p className={styles.muted}>填写说明即为纠正；留空提交则否定了这条观察。被纠正或否定的观察不再作为召回证据。</p>
        <textarea value={correctionText} maxLength={500} rows={3} placeholder="例如：那段时间在出差，不能说明习惯变化。"
          onChange={event => setCorrectionText(event.target.value)} />
        <div className={styles.modalActions}>
          <Button variant="ghost" size="sm" onClick={() => setCorrectionTarget(null)}>取消</Button>
          <Button variant="outline" size="sm" disabled={busy} onClick={correct}>提交</Button>
        </div>
      </Modal>

      <Modal open={Boolean(detail)} title="复盘详情" onClose={() => setDetail(null)} width={720}>
        {detail && (
          <div className={styles.detail}>
            <p className={styles.muted}>{detail.coverageNote || detail.message || '没有可展示的内容。'}</p>
            {[...detail.observations, ...detail.followUps, ...detail.actions].map(item => (
              <article key={`${item.kind}-${item.topicKey}-${item.text}`} className={styles.card}>
                <div className={styles.cardHead}><strong>{item.text}</strong><span className={styles.badge}>{item.kind === 'ACTION' ? '建议' : item.kind === 'FOLLOW_UP' ? '跟进' : '观察'}</span></div>
                {item.rationale && <p className={styles.muted}>理由：{item.rationale}</p>}
                {item.criterion && <p className={styles.muted}>检查：{item.criterion}</p>}
                {item.uncertainty && <p className={styles.muted}>不确定：{item.uncertainty}</p>}
                <p className={styles.muted}>依据：{item.evidenceIds.join('、') || '无'}</p>
              </article>
            ))}
          </div>
        )}
      </Modal>
    </SettingsPageLayout>
  );
}
