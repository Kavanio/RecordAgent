import { useCallback, useEffect, useState } from 'react';
import type { FullModelConfig } from '../../types/model';
import type {
  ReviewAction, ReviewDetail, ReviewMemory, ReviewObservation, ReviewSettings, SaveReviewFeedback,
} from '../../types/review';
import {
  captureReviewFacts, correctReviewObservation, eraseReviewData, fetchReviewMemory, fetchReviewSettings,
  grantReviewConsent, previewReview, revokeReviewConsent, saveReviewSettings, submitReviewFeedback,
} from '../../services/reviewApi';
import { fetchFullModelConfig } from '../../services/api';
import { PERIODS, STATE_TEXT, requestId, yesterday } from '../../features/review/statisticsReviewPresentation';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { useMessage } from '../common/Message';
import { ReviewDisclosure } from './ReviewDisclosure';
import { ReviewFormModal } from './ReviewFormModal';
import { ReportReviewConfigDialog, ReportReviewConsentDialog, ReportReviewMemoryDialog } from './ReportReviewDialogs';
import styles from './ReportReview.module.css';

/** 报告邮件里追加的 AI 点评；挂在自动任务设置中，不是独立复盘页。 */
export function ReportReviewSettings() {
  const [settings, setSettings] = useState<ReviewSettings | null>(null);
  const [memory, setMemory] = useState<ReviewMemory>({ actions: [], observations: [] });
  const [providers, setProviders] = useState<FullModelConfig['providers']>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [draft, setDraft] = useState<ReviewSettings | null>(null);
  const [editor, setEditor] = useState<'config' | 'consent' | 'memory' | 'preview' | 'erase' | null>(null);
  const [consent, setConsent] = useState(false);
  const [preview, setPreview] = useState<ReviewDetail | null>(null);
  const [feedbackTarget, setFeedbackTarget] = useState<{ action: ReviewAction; decision: SaveReviewFeedback['decision'] } | null>(null);
  const [feedbackText, setFeedbackText] = useState('');
  const [correctionTarget, setCorrectionTarget] = useState<ReviewObservation | null>(null);
  const [correctionText, setCorrectionText] = useState('');
  const { showMessage } = useMessage();

  const load = useCallback(async () => {
    setError('');
    try {
      const [nextSettings, nextMemory, config] = await Promise.all([
        fetchReviewSettings(), fetchReviewMemory(), fetchFullModelConfig().catch(() => null),
      ]);
      setSettings(nextSettings);
      setMemory(nextMemory);
      setProviders(config?.providers ?? []);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '报告点评设置读取失败');
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function act(work: () => Promise<unknown>, success?: string) {
    if (busy) return;
    setBusy(true);
    try {
      await work();
      if (success) showMessage('success', success);
      await load();
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '操作失败');
    } finally {
      setBusy(false);
    }
  }
  function patch(next: Partial<ReviewSettings>) {
    setDraft(current => (current ? { ...current, ...next } : current));
  }
  function toggle<T extends string>(values: T[], value: T): T[] {
    return values.includes(value) ? values.filter(item => item !== value) : [...values, value];
  }
  function save() {
    if (!draft) return;
    void act(async () => {
      await saveReviewSettings({ expectedRevision: draft.revision, enabled: draft.enabled, periods: draft.periods,
        domains: draft.domains, historyDays: draft.historyDays, dailyLimit: draft.dailyLimit,
        providerId: draft.providerId, modelName: draft.modelName });
      setDraft(null); setEditor(null);
    }, '点评配置已保存；修改范围后需要重新授权');
  }
  function grant() {
    if (!settings || !consent) return;
    void act(async () => {
      await grantReviewConsent(settings.revision, settings.policyVersion, true);
      setConsent(false); setEditor(null);
    }, '已按当前范围授予报告点评权限');
  }
  function revoke() {
    if (!settings?.grant) return;
    void act(() => revokeReviewConsent(settings.grant!.id, settings.grant!.revision),
      '已撤销授权，未发送的报告邮件将只含数据部分');
  }
  function runPreview() {
    setEditor(null);
    if (!settings) return;
    const day = yesterday();
    void act(async () => {
      const result = await previewReview({ requestId: requestId('preview'), period: 'DAILY_REPORT',
        from: day, to: day, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, confirmedPaidCall: true });
      setPreview(result);
      showMessage(result.state === 'SUCCEEDED' ? 'success' : 'info',
        result.state === 'SUCCEEDED' ? '预览已生成，不会发送邮件' : result.message || '本次未生成，数据不受影响');
    });
  }
  function feedback() {
    if (!feedbackTarget) return;
    const { action, decision } = feedbackTarget;
    void act(async () => {
      await submitReviewFeedback(action.id, { requestId: requestId('feedback'), expectedRevision: action.revision,
        decision, title: action.title, criterion: action.criterion, checkOn: action.checkOn, feedback: feedbackText.trim() });
      setFeedbackTarget(null); setFeedbackText('');
    }, '反馈已保存');
  }
  function correct() {
    if (!correctionTarget) return;
    void act(async () => {
      await correctReviewObservation(correctionTarget.id, { requestId: requestId('correction'),
        expectedRevision: correctionTarget.revision, decision: correctionText.trim() ? 'CORRECTED' : 'REJECTED',
        correction: correctionText.trim() });
      setCorrectionTarget(null); setCorrectionText('');
    }, '观察已处理，后续不会再作为自动召回证据');
  }

  return <>
    <SettingsGroup title="报告邮件 AI 点评" description="日报、周报、月报邮件可在数据后追加一段有依据的复盘。默认关闭，需单独授权并固定模型，与聊天记忆无关。">
      <SettingsRow label={settings?.enabled ? '已启用 AI 点评' : '未启用 AI 点评'}
        description={settings
          ? `${settings.periods.map(id => PERIODS.find(period => period.id === id)?.label).filter(Boolean).join('、') || '未选择周期'} · ${settings.modelName || '未选择模型'} · 回看${settings.historyDays}天 · 每日≤${settings.dailyLimit}次`
          : '正在读取…'}
        control={<Button size="sm" disabled={busy || !settings} onClick={() => { setDraft(settings); setEditor('config'); }}>配置点评</Button>} />
      <SettingsRow label="用途授权" description={settings?.authorized
        ? `已授权 ${settings.grant?.periods.map(id => PERIODS.find(period => period.id === id)?.label || id).join('、') || ''}；撤销立即生效，已发出的邮件无法撤回。`
        : '尚未授权，报告邮件只发数据部分。'}
        control={settings?.authorized
          ? <Button size="sm" disabled={busy} onClick={revoke}>撤销授权</Button>
          : <Button size="sm" disabled={busy || !settings} onClick={() => { setConsent(false); setEditor('consent'); }}>核对并授权</Button>} />
      <SettingsRow label="建议与观察" description="模型给出的长期建议与观察；反馈由你提交，未反馈不算失败。"
        control={<div className={styles.actions}>
          <Button size="sm" disabled={busy} onClick={() => setEditor('memory')}>查看与反馈</Button>
          <Button size="sm" variant="outline" disabled={busy || !settings?.authorized} onClick={() => setEditor('preview')}>试生成一次</Button>
        </div>} />
      {error && <p role="alert" className={styles.error}>{error}<Button size="sm" onClick={() => void load()}>重新读取</Button></p>}
    </SettingsGroup>
    <ReviewDisclosure title="点评数据维护" description="手动采集用于排查；清除会删除本机摘要、目标和建议。">
      <SettingsRow label="采集昨天的事实摘要" description="只读取授权范围，不调用模型。"
        control={<Button variant="outline" size="sm" disabled={busy || !settings?.authorized}
          onClick={() => void act(() => captureReviewFacts(yesterday(), yesterday(), Intl.DateTimeFormat().resolvedOptions().timeZone), '已采集昨天的事实摘要')}>采集昨天</Button>} />
      <SettingsRow label="清除点评数据" description="撤销授权并删除本机复盘摘要、目标和建议；聊天与账本不受影响。"
        control={<Button variant="danger" size="sm" disabled={busy || !settings} onClick={() => setEditor('erase')}>清除</Button>} />
    </ReviewDisclosure>

    <ReportReviewConfigDialog open={editor === 'config'} busy={busy} draft={draft} providers={providers}
      patch={patch} toggle={toggle} onClose={() => { setDraft(null); setEditor(null); }} onSave={save} />
    <ReportReviewConsentDialog open={editor === 'consent'} busy={busy} settings={settings} consent={consent}
      setConsent={setConsent} onClose={() => { setConsent(false); setEditor(null); }} onGrant={grant} />
    <ReportReviewMemoryDialog open={editor === 'memory'} busy={busy} memory={memory}
      onClose={() => setEditor(null)} onFeedback={(action, decision) => { setFeedbackTarget({ action, decision: decision as SaveReviewFeedback['decision'] }); setFeedbackText(''); }}
      onCorrect={observation => { setCorrectionTarget(observation); setCorrectionText(''); }} />

    <ReviewFormModal open={feedbackTarget !== null} title="提交反馈" busy={busy} onClose={() => setFeedbackTarget(null)} footer={<>
      <Button size="sm" onClick={() => setFeedbackTarget(null)}>取消</Button>
      <Button variant="primary" size="sm" disabled={busy} onClick={feedback}>保存反馈</Button>
    </>}>
      <p className={styles.muted}>决策：{feedbackTarget ? STATE_TEXT[feedbackTarget.decision] || feedbackTarget.decision : ''}</p>
      <textarea className={styles.textarea} aria-label="反馈说明" value={feedbackText} maxLength={500} rows={3}
        placeholder="可选：补充原因或调整后的做法，不要填写敏感信息。" onChange={event => setFeedbackText(event.target.value)} />
    </ReviewFormModal>
    <ReviewFormModal open={correctionTarget !== null} title="纠正或否定观察" busy={busy} onClose={() => setCorrectionTarget(null)} footer={<>
      <Button size="sm" onClick={() => setCorrectionTarget(null)}>取消</Button>
      <Button variant="outline" size="sm" disabled={busy} onClick={correct}>提交</Button>
    </>}>
      <p className={styles.muted}>填写说明即为纠正；留空提交则否定了这条观察。被纠正或否定的观察不再作为召回证据。</p>
      <textarea className={styles.textarea} aria-label="观察纠正说明" value={correctionText} maxLength={500} rows={3}
        placeholder="例如：那段时间在出差，不能说明习惯变化。" onChange={event => setCorrectionText(event.target.value)} />
    </ReviewFormModal>
    <ReviewFormModal open={preview !== null} title="点评预览" onClose={() => setPreview(null)}>
      {preview && <>
        <p className={styles.muted}>{preview.coverageNote || preview.message || '没有可展示的内容。'}</p>
        {[...preview.observations, ...preview.followUps, ...preview.actions].map(item => <article key={`${item.kind}-${item.topicKey}-${item.text}`} className={styles.card}>
          <div className={styles.cardHead}><strong>{item.text}</strong>
            <span className={styles.badge}>{item.kind === 'ACTION' ? '建议' : item.kind === 'FOLLOW_UP' ? '跟进' : '观察'}</span></div>
          {item.rationale && <p className={styles.muted}>理由：{item.rationale}</p>}
          {item.criterion && <p className={styles.muted}>检查：{item.criterion}</p>}
        </article>)}
      </>}
    </ReviewFormModal>
    <ConfirmDialog open={editor === 'preview'} title="试生成一次点评" tone="info" confirmText="确认生成"
      description="使用昨天的日报周期调用一次模型，可能产生费用；只生成预览，不发送邮件，也不保存为长期建议。"
      onClose={() => setEditor(null)} onConfirm={runPreview} />
    <ConfirmDialog open={editor === 'erase'} title="清除点评数据" tone="danger" confirmText="确认清除" confirmVariant="danger"
      busy={busy} description="将撤销授权、关闭点评，并删除本机复盘摘要、目标、建议和观察。聊天记录和账本不受影响。"
      onClose={() => setEditor(null)} onConfirm={() => { if (!settings) return;
        void act(async () => { await eraseReviewData(settings.revision, true); setEditor(null); }, '点评数据已清除'); }} />
  </>;
}
