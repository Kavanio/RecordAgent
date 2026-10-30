import type { FullModelConfig } from '../../types/model';
import type { ReviewMemory, ReviewSettings } from '../../types/review';
import { DOMAINS, PERIODS, STATE_TEXT } from '../../features/review/statisticsReviewPresentation';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { Button } from '../common/Button';
import { Select } from '../common/Select';
import { TextInput } from '../common/TextInput';
import { ReviewDisclosure } from './ReviewDisclosure';
import { ReviewEmptyState } from './ReviewEmptyState';
import { ReviewFormModal } from './ReviewFormModal';
import styles from './ReportReview.module.css';

/** 报告邮件点评的配置弹窗；只编辑草稿，保存后旧授权失效。 */
export function ReportReviewConfigDialog({ open, busy, draft, providers, patch, toggle, onClose, onSave }: {
  open: boolean; busy: boolean; draft: ReviewSettings | null; providers: FullModelConfig['providers'];
  patch: (next: Partial<ReviewSettings>) => void;
  toggle: <T extends string>(values: T[], value: T) => T[];
  onClose: () => void; onSave: () => void;
}) {
  if (!draft) return null;
  const activeProvider = providers.find(provider => provider.id === draft.providerId);
  const models = (activeProvider?.models ?? []).filter(model => model.name && model.providerId !== '__none__');
  return <ReviewFormModal open={open} title="配置报告点评" busy={busy} onClose={onClose} footer={<>
    <Button size="sm" onClick={onClose}>取消</Button>
    <Button variant="primary" size="sm" disabled={busy || draft.periods.length === 0 || draft.domains.length === 0 || !draft.modelName}
      onClick={onSave}>保存配置</Button>
  </>}>
    <SettingsGroup title="生成范围" description="保存配置不会调用模型；扩大范围后需要重新授权。">
      <SettingsRow label="启用 AI 点评" description="关闭后报告邮件只发数据部分，已有数据保留。"
        control={<input type="checkbox" aria-label="启用报告邮件点评" checked={draft.enabled} disabled={busy}
          onChange={event => patch({ enabled: event.target.checked })} />} />
      <SettingsRow label="报告周期" layout="stacked" description="只有勾选的报告类型会追加点评。"
        control={<div className={styles.chips}>{PERIODS.map(item => (
          <label key={item.id} className={`${styles.chip} ${draft.periods.includes(item.id) ? styles.chipSelected : ''}`}>
            <input type="checkbox" checked={draft.periods.includes(item.id)} disabled={busy}
              onChange={() => patch({ periods: toggle(draft.periods, item.id) })} />{item.label}</label>))}</div>} />
      <SettingsRow label="数据范围" layout="stacked" description="只读统计数字，不读订单备注、资料正文或聊天。"
        control={<div className={styles.chips}>{DOMAINS.map(item => (
          <label key={item.id} className={`${styles.chip} ${draft.domains.includes(item.id) ? styles.chipSelected : ''}`}>
            <input type="checkbox" checked={draft.domains.includes(item.id)} disabled={busy}
              onChange={() => patch({ domains: toggle(draft.domains, item.id) })} />{item.label}</label>))}</div>} />
      <ReviewDisclosure title="高级设置" description="统计读取范围与费用保护。">
        <SettingsRow label="最多回看" description="只采集已结束自然日，最大 180 天。"
          control={<TextInput className={styles.number} aria-label="最多回看天数" type="number" min={1} max={180}
            value={draft.historyDays} disabled={busy} onChange={event => patch({ historyDays: Number(event.target.value) })} />} />
        <SettingsRow label="每天自动生成上限" description="用于限制付费调用；失败不会自动重试。"
          control={<TextInput className={styles.number} aria-label="每天自动生成上限" type="number" min={1} max={3}
            value={draft.dailyLimit} disabled={busy} onChange={event => patch({ dailyLimit: Number(event.target.value) })} />} />
      </ReviewDisclosure>
      <SettingsRow label="点评模型" layout="stacked" description="固定使用所选模型，不跟随聊天默认模型切换。"
        control={<div className={styles.selects}>
          <Select label="模型连接" placeholder="请选择模型连接" fieldSize="md" fullWidth value={draft.providerId} disabled={busy}
            description="选择已配置的服务连接。"
            options={providers.filter(provider => provider.isEnabled).map(provider => ({ value: provider.id, label: provider.name || provider.id }))}
            onChange={event => patch({ providerId: event.target.value, modelName: '' })} />
          <Select label="具体模型" placeholder="请选择具体模型" fieldSize="md" fullWidth value={draft.modelName}
            disabled={busy || !activeProvider || models.length === 0}
            description={!activeProvider ? '请先选择左侧模型连接。'
              : models.length === 0 ? '此连接暂无可选模型，请先到模型配置中添加。' : '选择该连接用于点评的模型。'}
            options={models.map(model => ({ value: model.name || model.id, label: model.name || model.id }))}
            onChange={event => patch({ modelName: event.target.value })} />
        </div>} />
    </SettingsGroup>
  </ReviewFormModal>;
}

/** 授权确认弹窗；保存配置不等于授权。 */
export function ReportReviewConsentDialog({ open, busy, settings, consent, setConsent, onClose, onGrant }: {
  open: boolean; busy: boolean; settings: ReviewSettings | null; consent: boolean;
  setConsent: (value: boolean) => void; onClose: () => void; onGrant: () => void;
}) {
  if (!settings) return null;
  return <ReviewFormModal open={open} title="确认报告点评授权" busy={busy} onClose={onClose}>
    <p className={styles.muted}>固定模型：{settings.providerId} / {settings.modelName} · 周期：
      {settings.periods.map(id => PERIODS.find(period => period.id === id)?.label || id).join('、') || '未选择'} ·
      回看{settings.historyDays}天 · 每日上限{settings.dailyLimit}次</p>
    <SettingsRow label="确认授权" layout="stacked"
      description="我确认上述数据范围、周期、回看窗口和固定模型接收方，并了解生成会把统计摘要发送给该模型。"
      control={<div className={styles.stack}>
        <label className={styles.chip}><input type="checkbox" checked={consent} disabled={busy}
          onChange={event => setConsent(event.target.checked)} />我已阅读并同意</label>
        <Button variant="primary" size="sm" disabled={busy || !consent} onClick={onGrant}>授权</Button>
      </div>} />
  </ReviewFormModal>;
}

/** 长期建议与观察的只读列表；反馈与纠正由父组件发起。 */
export function ReportReviewMemoryDialog({ open, busy, memory, onClose, onFeedback, onCorrect }: {
  open: boolean; busy: boolean; memory: ReviewMemory; onClose: () => void;
  onFeedback: (action: ReviewMemory['actions'][number], decision: string) => void;
  onCorrect: (observation: ReviewMemory['observations'][number]) => void;
}) {
  return <ReviewFormModal open={open} title="建议与观察" busy={busy} onClose={onClose}>
    {memory.actions.length === 0 && memory.observations.length === 0 &&
      <ReviewEmptyState title="还没有建议" description="报告邮件点评产生建议或观察后，可以在这里查看并反馈。" />}
    {memory.actions.map(action => <div key={action.id} className={styles.card}>
      <div className={styles.cardHead}><strong>{action.title}</strong>
        <span className={styles.badge}>{STATE_TEXT[action.state] ?? action.state}</span></div>
      {action.rationale && <p className={styles.muted}>{action.rationale}</p>}
      {action.criterion && <p className={styles.muted}>检查：{action.criterion}{action.checkOn ? ` · ${action.checkOn}` : ''}</p>}
      {action.feedback && <p className={styles.muted}>我的反馈：{action.feedback}</p>}
      <div className={styles.actions}>
        {['ACCEPTED', 'DECLINED', 'DEFERRED'].map(decision => <Button key={decision} variant="outline" size="sm" disabled={busy}
          onClick={() => onFeedback(action, decision)}>{decision === 'ACCEPTED' ? '采纳' : decision === 'DECLINED' ? '不适合' : '暂不决定'}</Button>)}
        {['ACCEPTED', 'COMPLETED', 'PARTIAL', 'NOT_DONE', 'UNKNOWN'].includes(action.state) &&
          ['COMPLETED', 'PARTIAL', 'NOT_DONE', 'UNKNOWN'].map(decision => <Button key={decision} variant="ghost" size="sm" disabled={busy}
            onClick={() => onFeedback(action, decision)}>{decision === 'COMPLETED' ? '完成' : decision === 'PARTIAL' ? '部分完成'
              : decision === 'NOT_DONE' ? '未完成' : '无法判断'}</Button>)}
      </div>
    </div>)}
    {memory.observations.map(observation => <div key={observation.id} className={styles.card}>
      <div className={styles.cardHead}><strong>{observation.content}</strong>
        <span className={styles.badge}>{STATE_TEXT[observation.state] ?? observation.state}</span></div>
      {observation.uncertainty && <p className={styles.muted}>仍不确定：{observation.uncertainty}</p>}
      {observation.correction && <p className={styles.muted}>纠正：{observation.correction}</p>}
      {observation.state === 'CANDIDATE' && <div className={styles.actions}>
        <Button variant="outline" size="sm" disabled={busy} onClick={() => onCorrect(observation)}>纠正或否定</Button>
      </div>}
    </div>)}
  </ReviewFormModal>;
}
