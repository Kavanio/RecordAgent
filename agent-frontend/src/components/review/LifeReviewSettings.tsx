import { useState } from 'react';
import { lifeReviewApi } from '../../services/lifeReviewApi';
import type { LifeSource } from '../../types/lifeReview';
import { useLifeReview } from './useLifeReview';
import { LifeContentCard } from './LifeContentCard';
import { LifeEventCandidateEditor } from './LifeEventCandidateEditor';
import { SettingsPageLayout } from '../settings/SettingsPageLayout';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { Button } from '../common/Button';
import { Select } from '../common/Select';
import { TextInput } from '../common/TextInput';
import styles from './LifeReviewSettings.module.css';

const SOURCES: { id: LifeSource; label: string }[] = [
  { id: 'BUSINESS', label: '待办与学习事实' }, { id: 'JOURNAL', label: '每日手记正文' },
  { id: 'GOALS', label: '人工目标与主动反馈' }, { id: 'CHAT', label: '普通聊天用户原文' }, { id: 'FINANCE', label: '财务数值（含资产统计）' },
];
const CANDIDATE_STATES: Record<string, string> = { PENDING: '等待合适时机', PRESENTED: '已呈现，无需立即回复',
  CANCELLED: '已取消', EXPIRED: '已过期' };
/** 生活复盘独立设置入口；保存、用途授权与主动投递明确分开。 */
export function LifeReviewSettings() {
  const state = useLifeReview();
  const { settings: s, busy, act, patch } = state;
  const [consent, setConsent] = useState(false);
  const [hour, setHour] = useState(12);
  const [habitSource, setHabitSource] = useState('');
  const disabled = busy || state.dirty || !s?.authorized;
  const provider = state.providers.find(p => p.id === s?.providerId);
  function today() {
    const parts = new Intl.DateTimeFormat('en', { timeZone: s?.timezone || 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
    return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)?.value || '').join('-');
  }
  return <SettingsPageLayout title="生活复盘与主动联系" description="先备好内容，再选择时机；没有回复也不会连着催。" loading={state.loading} loadingLabel="正在读取生活复盘">
    {state.error && <p role="alert" className={styles.error}>{state.error}<Button size="sm" onClick={() => void state.load()}>重新读取</Button></p>}
    {s && <>
      <SettingsGroup title="范围与固定模型" description="默认关闭。每日21点后可后台准备，按下方额度计费；扩大或修改设置后需要重新授权。">
        <SettingsRow label="启用生活复盘" control={<input aria-label="启用生活复盘" type="checkbox" checked={s.enabled} disabled={busy}
          onChange={e => patch({ enabled: e.target.checked })} />} />
        <SettingsRow label="允许读取的来源" description="仅用于生活复盘，不继承聊天记忆或统计摘要授权。回看材料仍按原始时间标注。" layout="stacked"
          control={<div className={styles.choices}>{SOURCES.map(source => <label key={source.id}>
            <input type="checkbox" checked={s.sources.includes(source.id)} disabled={busy} onChange={() => patch({ sources:
              s.sources.includes(source.id) ? s.sources.filter(id => id !== source.id) : [...s.sources, source.id] })} />{source.label}
          </label>)}</div>} />
        <SettingsRow label="模型连接" control={<Select aria-label="生活复盘模型连接" value={s.providerId} disabled={busy}
          options={[{ value: '', label: '请选择模型连接' }, ...state.providers.filter(p => p.isEnabled).map(p => ({ value: p.id, label: p.name }))]}
          onChange={e => patch({ providerId: e.target.value, modelName: '' })} />} />
        <SettingsRow label="固定模型" control={<Select aria-label="生活复盘固定模型" value={s.modelName} disabled={busy}
          options={[{ value: '', label: '请选择模型' }, ...(provider?.models || []).map(m => ({ value: m.name, label: m.name }))]}
          onChange={e => patch({ modelName: e.target.value })} />} />
        <SettingsRow label="时区" control={<TextInput aria-label="生活复盘时区" value={s.timezone} disabled={busy} onChange={e => patch({ timezone: e.target.value })} />} />
        <SettingsRow label="回看窗口（含复盘当天）" control={<TextInput aria-label="生活复盘回看天数" type="number" min={1} max={7}
          value={s.historyDays} disabled={busy} onChange={e => patch({ historyDays: Number(e.target.value) })} />} />
        <SettingsRow label="每日模型调用上限" description="失败调用同样占用额度；打开历史不会重新生成。" control={<TextInput aria-label="生活复盘每日上限"
          type="number" min={1} max={3} value={s.dailyLimit} disabled={busy} onChange={e => patch({ dailyLimit: Number(e.target.value) })} />} />
        <SettingsRow label="主动呈现" description="冷启动每天最多一次；同主题无新信息不重复，23:30后顺延到次日中午。"
          control={<input aria-label="主动呈现生活复盘" type="checkbox" checked={s.proactive} disabled={busy} onChange={e => patch({ proactive: e.target.checked })} />} />
        <SettingsRow label="投递到本人飞书" description={`${s.channelLabel}；目标只取已配置的本人白名单。飞书已读状态未知。`}
          control={<input aria-label="生活复盘飞书投递" type="checkbox" checked={s.feishu} disabled={busy || !s.channelAvailable}
            onChange={e => patch({ feishu: e.target.checked })} />} />
        <SettingsRow label="保存" description={state.dirty ? '有未保存更改；保存后再确认授权。' : '配置保存与授权分别执行。'}
          control={<Button size="sm" variant="primary" disabled={busy || !s.sources.length || !s.modelName}
            onClick={() => { setConsent(false); void act(() => lifeReviewApi.save(s), '设置已保存，请确认新的授权范围'); }}>保存生活复盘设置</Button>} />
      </SettingsGroup>
      <SettingsGroup title="独立用途授权" description="生成会把选择的来源材料发送给上述固定模型。改变范围或接收方需重新授权；撤权停止后续读取与生成，已发内容无法撤回。">
        <SettingsRow label="当前状态" description={s.authorized ? '当前保存版本已授权。' : '尚未授权。'}
          control={<span>{state.dirty ? '存在未保存更改' : s.authorized ? '已授权' : '未授权'}</span>} />
        {!s.authorized && <SettingsRow label="明确确认" layout="stacked" control={<div className={styles.stack}>
          <label><input type="checkbox" checked={consent} disabled={busy || state.dirty} onChange={e => setConsent(e.target.checked)} />
            我确认来源、{s.timezone} 时区、{s.historyDays}日窗口、每日{s.dailyLimit}次调用与固定模型；
            {s.proactive ? '允许按上述规则主动呈现；' : '仅桌面留档；'}{s.feishu ? '允许向已配置的本人飞书发送同一副本。' : '不投递飞书。'}</label>
          <Button size="sm" variant="primary" disabled={busy || state.dirty || !consent || !s.enabled || !s.revision}
            onClick={() => { setConsent(false); void act(() => lifeReviewApi.consent(s, true), '当前范围已单独授权'); }}>确认授权生活复盘</Button>
        </div>} />}
        {s.authorized && <SettingsRow label="撤销" control={<Button size="sm" disabled={busy || state.dirty}
          onClick={() => void act(() => lifeReviewApi.consent(s, false), '授权已撤销，未呈现候选已取消')}>撤销生活复盘授权</Button>} />}
      </SettingsGroup>
      <SettingsGroup title="内容留档" description="每份内容标明归属日、覆盖截止点与版本。补充产生新版本，保留已发送副本；准备中可稍后刷新。">
        <SettingsRow label="准备今天" description="使用当前已授权来源，后台调用固定模型；受当日调用上限保护。"
          control={<div className={styles.choices}><Button size="sm" variant="primary" disabled={disabled}
            onClick={() => void act(() => lifeReviewApi.prepare(today()), '已安排后台准备；已有版本会直接复用')}>准备今天的复盘</Button>
            <Button size="sm" disabled={busy || state.dirty} onClick={() => void state.load()}>刷新内容与状态</Button></div>} />
        {state.history.length === 0 && <p className={styles.muted}>还没有生活复盘。记录少也可以先留下已有材料。</p>}
        {state.history.map(c => <LifeContentCard key={c.id} content={c} timezone={c.timezone || s.timezone} busy={busy} authorized={!disabled} feishu={s.feishu} act={act} />)}
        {state.archives.map((a, i) => <details key={i}><summary>重置前的只读外发副本 {i + 1}</summary><p className={styles.archive}>{a}</p></details>)}
      </SettingsGroup>
      <SettingsGroup title="作息候选与纠正" description="只在你明确提供后使用，30天后失效；没有互动不代表没起床或身体异常。">
        <SettingsRow label="当前候选" description={state.habit ? `${state.habit.availableHour}点后 · ${state.habit.source} · ${state.habit.uncertainty} · 有效至${state.habit.expiresAt}` : '尚未形成作息候选，采用保守规则。'}
          control={state.habit && <Button size="sm" disabled={disabled} onClick={() => void act(() => lifeReviewApi.saveHabit(
            state.habit!.revision, state.habit!.availableHour, state.habit!.source, false), '作息候选已停用')}>停用候选</Button>} />
        <SettingsRow label="明确或纠正可呈现时间" layout="stacked" control={<div className={styles.stack}>
          <TextInput aria-label="生活复盘作息小时" type="number" min={12} max={23} value={hour} disabled={disabled} onChange={e => setHour(Number(e.target.value))} />
          <TextInput aria-label="生活复盘作息来源" value={habitSource} maxLength={300} disabled={disabled} placeholder="例如：我最近通常下午两点后有空" onChange={e => setHabitSource(e.target.value)} />
          <Button size="sm" disabled={disabled || !habitSource.trim()} onClick={() => void act(() => lifeReviewApi.saveHabit(
            state.habit?.revision || 0, hour, habitSource, true), '作息候选已保存，后续时机按新候选计算')}>保存作息候选</Button>
        </div>} />
      </SettingsGroup>
      <LifeEventCandidateEditor history={state.history} disabled={disabled || !s.proactive} act={act} />
      <SettingsGroup title="主动记录与反馈" description="等待、过期、取消与已呈现分别记录。未回复不会被判为失败，也不会触发追问。">
        {state.candidates.length === 0 && <p className={styles.muted}>暂无主动候选。</p>}
        {state.candidates.map(c => <SettingsRow key={c.id} label={c.topic} description={`${CANDIDATE_STATES[c.state] || c.state} · 最早${c.dueAt} · 到期${c.expiresAt}${c.feedback ? ` · ${c.feedback}` : ''}`}
          control={<div className={styles.choices}>{(c.state === 'PENDING' ? ['CANCEL'] : ['HELPFUL', 'NOT_RELEVANT']).map(decision =>
            <Button key={decision} size="sm" disabled={busy} onClick={() => void act(() => lifeReviewApi.feedback(c.id, decision), '反馈已记录')}>
              {decision === 'CANCEL' ? '取消候选' : decision === 'HELPFUL' ? '有帮助' : '不相关'}</Button>)}</div>} />)}
      </SettingsGroup>
    </>}
  </SettingsPageLayout>;
}
