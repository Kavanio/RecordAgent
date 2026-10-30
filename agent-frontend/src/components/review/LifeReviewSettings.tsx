import { useState } from 'react';
import { lifeReviewApi } from '../../services/lifeReviewApi';
import type { LifeSource } from '../../types/lifeReview';
import { Button } from '../common/Button';
import { Select } from '../common/Select';
import { TextInput } from '../common/TextInput';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { SettingsPageLayout } from '../settings/SettingsPageLayout';
import { LifeContentCard } from './LifeContentCard';
import { LifeGoalSettings } from './LifeGoalSettings';
import { ReviewDisclosure } from './ReviewDisclosure';
import { ReviewEmptyState } from './ReviewEmptyState';
import { ReviewFormModal } from './ReviewFormModal';
import { useLifeReview } from './useLifeReview';
import styles from './LifeReviewSettings.module.css';

const SOURCES: { id: LifeSource; label: string }[] = [
  { id: 'BUSINESS', label: '待办与学习事实' },
  { id: 'JOURNAL', label: '每日手记正文' },
  { id: 'GOALS', label: '人工目标与主动反馈' },
  { id: 'CHAT', label: '普通聊天用户原文' },
  { id: 'FINANCE', label: '财务数值（含资产统计）' },
];

const CANDIDATE_STATES: Record<string, string> = {
  PENDING: '等待合适时机',
  PRESENTED: '已呈现，无需立即回复',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
};

/** 生活复盘独立设置入口；保存、用途授权与主动投递明确分开。 */
export function LifeReviewSettings() {
  const state = useLifeReview();
  const { settings: s, busy, act, patch } = state;
  const [editor, setEditor] = useState<'settings' | 'consent' | 'habit' | null>(null);
  const [hour, setHour] = useState(12);
  const [habitSource, setHabitSource] = useState('');

  function closeEditor() {
    state.discard();
    setEditor(null);
  }

  const disabled = busy || state.dirty || !s?.enabled || !s.authorized;
  const provider = state.providers.find(p => p.id === s?.providerId);

  function today() {
    const parts = new Intl.DateTimeFormat('en', {
      timeZone: s?.timezone || 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(new Date());
    return ['year', 'month', 'day']
      .map(type => parts.find(part => part.type === type)?.value || '')
      .join('-');
  }

  return (
    <SettingsPageLayout
      title="生活复盘"
      description="回顾近期经历，在一份复盘里阅读和补充。"
      loading={state.loading}
      loadingLabel="正在读取生活复盘"
    >
      {state.error && (
        <p role="alert" className={styles.error}>
          {state.error}
          <Button size="sm" onClick={() => void state.load()}>重新读取</Button>
        </p>
      )}
      {s && (
        <>
          <SettingsGroup title="复盘设置" description="查看已保存的范围与运行状态，需要调整时再打开配置。">
            <SettingsRow
              label={s.enabled ? '生活复盘已启用' : '生活复盘未启用'}
              description={`${s.sources.map(id => SOURCES.find(source => source.id === id)?.label).join('、') || '尚未选择来源'} · ${s.modelName || '尚未选择模型'} · 回看${s.historyDays}日 · 每日≤${s.dailyLimit}次 · ${s.proactive ? '主动呈现' : '仅桌面留档'}${s.feishu ? ' · 飞书' : ''}`}
              control={<Button size="sm" disabled={busy} onClick={() => setEditor('settings')}>配置复盘</Button>}
            />
          </SettingsGroup>

          <ReviewFormModal
            open={editor === 'settings'}
            title="配置生活复盘"
            busy={busy}
            onClose={closeEditor}
            footer={
              <>
                <Button size="sm" onClick={closeEditor}>取消</Button>
                <Button
                  size="sm"
                  variant="primary"
                  disabled={busy || !s.sources.length || !s.modelName}
                  onClick={() => void act(async () => {
                    await lifeReviewApi.save(s);
                    setEditor(null);
                  }, '设置已保存，请确认新的授权范围')}
                >
                  保存生活复盘设置
                </Button>
              </>
            }
          >
            <SettingsGroup
              title="范围与固定模型"
              description="默认关闭。每日21点后可后台准备，按下方额度计费；扩大或修改设置后需要重新授权。"
            >
              <SettingsRow
                label="启用生活复盘"
                control={<input aria-label="启用生活复盘" type="checkbox" checked={s.enabled} disabled={busy} onChange={e => patch({ enabled: e.target.checked })} />}
              />
              <SettingsRow
                label="允许读取的来源"
                description="仅用于生活复盘，不继承聊天记忆或统计摘要授权。回看材料仍按原始时间标注。"
                layout="stacked"
                control={
                  <div className={styles.choices}>
                    {SOURCES.map(source => (
                      <label key={source.id}>
                        <input
                          type="checkbox"
                          checked={s.sources.includes(source.id)}
                          disabled={busy}
                          onChange={() => patch({
                            sources: s.sources.includes(source.id)
                              ? s.sources.filter(id => id !== source.id)
                              : [...s.sources, source.id],
                          })}
                        />
                        {source.label}
                      </label>
                    ))}
                  </div>
                }
              />
              <SettingsRow
                label="模型连接"
                control={
                  <Select
                    aria-label="生活复盘模型连接"
                    value={s.providerId}
                    disabled={busy}
                    options={[{ value: '', label: '请选择模型连接' }, ...state.providers.filter(p => p.isEnabled).map(p => ({ value: p.id, label: p.name }))]}
                    onChange={e => patch({ providerId: e.target.value, modelName: '' })}
                  />
                }
              />
              <SettingsRow
                label="固定模型"
                control={
                  <Select
                    aria-label="生活复盘固定模型"
                    value={s.modelName}
                    disabled={busy}
                    options={[{ value: '', label: '请选择模型' }, ...(provider?.models || []).map(m => ({ value: m.name, label: m.name }))]}
                    onChange={e => patch({ modelName: e.target.value })}
                  />
                }
              />
              <ReviewDisclosure variant="inline" title="高级设置" description="近期原文范围、时区与费用保护；不是长期记忆的保存期限。">
                <SettingsRow
                  label="时区"
                  control={<TextInput aria-label="生活复盘时区" value={s.timezone} disabled={busy} onChange={e => patch({ timezone: e.target.value })} />}
                />
                <SettingsRow
                  label="近期原文范围（含当天）"
                  description="每次最多读取最近7天原文；更早经历会按已授权记忆自动关联。"
                  control={<TextInput aria-label="生活复盘回看天数" type="number" min={1} max={7} value={s.historyDays} disabled={busy} onChange={e => patch({ historyDays: Number(e.target.value) })} />}
                />
                <SettingsRow
                  label="每日模型调用上限"
                  description="失败调用同样占用额度；打开历史不会重新生成。"
                  control={<TextInput aria-label="生活复盘每日上限" type="number" min={1} max={3} value={s.dailyLimit} disabled={busy} onChange={e => patch({ dailyLimit: Number(e.target.value) })} />}
                />
              </ReviewDisclosure>
              <SettingsRow
                label="主动呈现"
                description="冷启动每天最多一次；同主题无新信息不重复，23:30后顺延到次日中午。"
                control={<input aria-label="主动呈现生活复盘" type="checkbox" checked={s.proactive} disabled={busy} onChange={e => patch({ proactive: e.target.checked })} />}
              />
              <SettingsRow
                label="投递到本人飞书"
                description={`${s.channelLabel}；目标只取已配置的本人白名单。飞书已读状态未知。`}
                control={<input aria-label="生活复盘飞书投递" type="checkbox" checked={s.feishu} disabled={busy || !s.channelAvailable} onChange={e => patch({ feishu: e.target.checked })} />}
              />
            </SettingsGroup>
            <p className={styles.muted}>{state.dirty ? '有未保存更改；保存后再确认授权。' : '配置保存与授权分别执行。'}</p>
          </ReviewFormModal>

          <SettingsGroup title="用途授权" description="生成会把所选来源发送给上方固定模型；改变范围需重新授权，撤权不能撤回已发内容。">
            <SettingsRow
              label="当前状态"
              description={s.authorized ? '当前保存版本已授权。' : '尚未授权，不会读取材料或调用模型。'}
              control={
                s.authorized ? (
                  <Button size="sm" disabled={busy || state.dirty} onClick={() => void act(() => lifeReviewApi.consent(s, false), '授权已撤销，未呈现候选已取消')}>
                    撤销授权
                  </Button>
                ) : (
                  <Button size="sm" disabled={busy || !s.enabled || !s.revision} onClick={() => setEditor('consent')}>
                    核对并授权
                  </Button>
                )
              }
            />
            <ReviewFormModal open={editor === 'consent'} title="确认生活复盘授权" busy={busy} onClose={closeEditor}>
              <p className={styles.muted}>
                固定模型：{s.providerId} / {s.modelName}；来源：{s.sources.map(id => SOURCES.find(source => source.id === id)?.label).join('、')}。
                时区：{s.timezone} · 回看{s.historyDays}日 · 每日≤{s.dailyLimit}次调用 · {s.proactive ? '允许主动呈现' : '仅桌面留档'} · {s.feishu ? '允许投递本人飞书' : '不投递飞书'}。
              </p>
              {!s.authorized && (
                <SettingsRow
                  label="确认授权范围"
                  layout="stacked"
                  control={
                    <div className={styles.stack}>
                      <Button
                        size="sm"
                        variant="primary"
                        disabled={busy || state.dirty || !s.enabled || !s.revision}
                        onClick={() => void act(async () => {
                          await lifeReviewApi.consent(s, true);
                          setEditor(null);
                        }, '当前范围已单独授权')}
                      >
                        确认授权生活复盘
                      </Button>
                    </div>
                  }
                />
              )}
            </ReviewFormModal>
          </SettingsGroup>

          <LifeGoalSettings />

          <SettingsGroup title="我的复盘" description="每份内容标明归属日、覆盖截止点与版本。补充产生新版本，保留已发送副本；准备中可稍后刷新。">
            <SettingsRow
              label="准备今天"
              description={state.generating ? '正在后台调用模型生成复盘，预计需要 5～15 秒，完成后会自动刷新…' : `当前每日 ${state.habit?.active ? state.habit.availableHour : 21}:00 自动准备${s.proactive && s.feishu ? '并发送飞书' : ''}；也可随时手动触发。`}
              control={
                <div className={styles.choices}>
                  <Button size="sm" variant="primary" disabled={disabled || state.generating} onClick={() => {
                    const day = today();
                    const readyToday = state.history.find(c => c.day === day && c.state === 'READY');
                    void act(() => lifeReviewApi.prepare(day, readyToday ? readyToday.version : 0, Boolean(readyToday)), '已开始后台生成今日复盘，请稍候…');
                  }}>
                    {state.generating ? '正在生成复盘…' : '准备今天的复盘'}
                  </Button>
                  <Button size="sm" disabled={disabled} onClick={() => {
                    setHour(state.habit?.active ? state.habit.availableHour : 21);
                    setHabitSource(state.habit?.source || '');
                    setEditor('habit');
                  }}>
                    设置每日时间（{state.habit?.active ? `${state.habit.availableHour}:00` : '21:00'}）
                  </Button>
                  <Button size="sm" disabled={busy || state.dirty} onClick={() => void state.load()}>
                    刷新内容与状态
                  </Button>
                </div>
              }
            />
            {state.history.length === 0 && (
              <ReviewEmptyState title="还没有生活复盘" description="准备一份当天复盘后，内容和历史版本会保留在这里。" />
            )}
            {state.history.slice(0, 1).map(c => (
              <LifeContentCard key={c.id} content={c} timezone={c.timezone || s.timezone} busy={busy} authorized={!disabled} feishu={s.feishu} act={act} />
            ))}
            {state.history.length > 1 && (
              <ReviewDisclosure variant="inline" title={`历史版本（${state.history.length - 1}）`} description="旧版保留原内容；展开查看，不会重新生成。">
                {state.history.slice(1).map(c => (
                  <LifeContentCard key={c.id} content={c} timezone={c.timezone || s.timezone} busy={busy} authorized={!disabled} feishu={s.feishu} act={act} />
                ))}
              </ReviewDisclosure>
            )}
            {state.archives.map((a, i) => (
              <details key={i}>
                <summary>重置前的只读外发副本 {i + 1}</summary>
                <p className={styles.archive}>{a}</p>
              </details>
            ))}
          </SettingsGroup>

          <ReviewFormModal open={editor === 'habit'} title="设置每日复盘与发送时间" busy={busy} onClose={closeEditor}>
            <SettingsRow
              label="每日自动生成与发送时间（12:00 — 23:00 整点）"
              description="到达设定整点后自动准备今日复盘；若开启了主动呈现与飞书投递，生成完成后会自动发送。"
              layout="stacked"
              control={
                <div className={styles.stack}>
                  <Select
                    aria-label="生活复盘作息小时"
                    value={String(hour)}
                    disabled={disabled}
                    options={Array.from({ length: 12 }, (_, idx) => {
                      const h = idx + 12;
                      return { value: String(h), label: `每天 ${h}:00${h === 21 ? '（默认）' : ''}` };
                    })}
                    onChange={e => setHour(Number(e.target.value))}
                  />
                  <TextInput aria-label="生活复盘作息来源" value={habitSource} maxLength={300} disabled={disabled} placeholder="备注说明（可选，不填可直接保存）" onChange={e => setHabitSource(e.target.value)} />
                  <div className={styles.choices}>
                    <Button
                      size="sm"
                      variant="primary"
                      disabled={disabled}
                      onClick={() => void act(async () => {
                        await lifeReviewApi.saveHabit(state.habit?.revision || 0, hour, habitSource, true);
                        setEditor(null);
                      }, `已设为每天 ${hour}:00 自动复盘`)}
                    >
                      保存时间
                    </Button>
                    {state.habit?.active && (
                      <Button
                        size="sm"
                        disabled={disabled}
                        onClick={() => void act(async () => {
                          await lifeReviewApi.saveHabit(state.habit!.revision, 21, '恢复默认 21:00', false);
                          setEditor(null);
                        }, '已恢复默认每天 21:00')}
                      >
                        恢复默认 21:00
                      </Button>
                    )}
                  </div>
                </div>
              }
            />
          </ReviewFormModal>

          <ReviewDisclosure title="主动联系（可选）" description="不影响阅读复盘。展开后管理联系时间和发送记录。">
            <SettingsGroup title="作息候选与纠正" description="只在你明确提供后使用，可随时纠正或停用；没有互动不代表没起床或身体异常。">
              <SettingsRow
                label="当前候选"
                description={
                  state.habit
                    ? `${state.habit.active ? '生效中' : '已停用'} · ${state.habit.availableHour}点后 · ${state.habit.source} · ${state.habit.uncertainty} · 有效至${state.habit.expiresAt}`
                    : '尚未形成作息候选，默认每天 21:00 自动准备。'
                }
                control={
                  state.habit && (
                    <Button
                      size="sm"
                      disabled={disabled || !state.habit.active}
                      onClick={() => void act(() => lifeReviewApi.saveHabit(state.habit!.revision, state.habit!.availableHour, state.habit!.source, false), '作息候选已停用')}
                    >
                      停用候选
                    </Button>
                  )
                }
              />
              <SettingsRow
                label="调整可呈现时间"
                description="可选择 12:00—23:00 的整点自动准备与发送时间，备注可选。"
                control={
                  <Button
                    size="sm"
                    disabled={disabled}
                    onClick={() => {
                      setHour(state.habit?.availableHour || 21);
                      setHabitSource(state.habit?.source || '');
                      setEditor('habit');
                    }}
                  >
                    编辑作息
                  </Button>
                }
              />
            </SettingsGroup>

            <SettingsGroup title="主动记录与反馈" description="等待、过期、取消与已呈现分别记录。未回复不会被判为失败，也不会触发追问。">
              {state.candidates.length === 0 && (
                <ReviewEmptyState title="暂无主动候选" description="有值得呈现的新信息时，候选和后续反馈会记录在这里。" />
              )}
              {state.candidates.map(c => (
                <SettingsRow
                  key={c.id}
                  label={c.topic.startsWith('review-') ? `${c.topic.slice(7)} 生活复盘` : c.topic}
                  description={`${CANDIDATE_STATES[c.state] || c.state} · 最早${new Date(c.dueAt).toLocaleString('zh-CN', { timeZone: s.timezone })} · 到期${new Date(c.expiresAt).toLocaleString('zh-CN', { timeZone: s.timezone })}${c.feedback ? ` · ${c.feedback}` : ''}`}
                  control={
                    <div className={styles.choices}>
                      {(c.state === 'PENDING' ? ['CANCEL'] : ['HELPFUL', 'NOT_RELEVANT']).map(decision => (
                        <Button key={decision} size="sm" disabled={busy} onClick={() => void act(() => lifeReviewApi.feedback(c.id, decision), '反馈已记录')}>
                          {decision === 'CANCEL' ? '取消候选' : decision === 'HELPFUL' ? '有帮助' : '不相关'}
                        </Button>
                      ))}
                    </div>
                  }
                />
              ))}
            </SettingsGroup>
          </ReviewDisclosure>
        </>
      )}
    </SettingsPageLayout>
  );
}
