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
import { LifeOutreachSection } from './LifeOutreachSection';
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

const IDLE_CHECKIN_OPTIONS = [
  { value: '0', label: '关闭（不主动问候）' },
  { value: '2', label: '连续 2 天无互动后问候一次' },
  { value: '3', label: '连续 3 天无互动后问候一次' },
  { value: '5', label: '连续 5 天无互动后问候一次' },
  { value: '7', label: '连续 7 天无互动后问候一次' },
];

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
              description={`${s.sources.map(id => SOURCES.find(source => source.id === id)?.label).join('、') || '尚未选择来源'} · ${s.modelName || '尚未选择模型'} · 回看${s.historyDays}日 · 每日≤${s.dailyLimit}次 · ${s.proactive ? '主动呈现' : '仅桌面留档'}${s.feishu ? ' · 飞书' : ''}${s.idleCheckinDays ? ` · ${s.idleCheckinDays}天未互动问候` : ''}`}
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
                control={
                  <input
                    aria-label="主动呈现生活复盘"
                    type="checkbox"
                    checked={s.proactive}
                    disabled={busy}
                    onChange={e => patch({ proactive: e.target.checked, idleCheckinDays: e.target.checked ? s.idleCheckinDays : 0 })}
                  />
                }
              />
              <SettingsRow
                label="投递到本人飞书"
                description={`${s.channelLabel}；目标只取已配置的本人白名单。飞书已读状态未知。`}
                control={
                  <input
                    aria-label="生活复盘飞书投递"
                    type="checkbox"
                    checked={s.feishu}
                    disabled={busy || !s.channelAvailable}
                    onChange={e => patch({ feishu: e.target.checked, idleCheckinDays: e.target.checked ? s.idleCheckinDays : 0 })}
                  />
                }
              />
              <SettingsRow
                label="久未互动飞书问候"
                description="需同时开启主动呈现与本人飞书。连续 N 天无聊天或手记互动时发送一句固定温和问候（不调用模型，未回复绝不催促）。"
                control={
                  <Select
                    aria-label="久未互动问候天数"
                    value={String(s.idleCheckinDays || 0)}
                    disabled={busy || !s.proactive || !s.feishu}
                    options={IDLE_CHECKIN_OPTIONS}
                    onChange={e => patch({ idleCheckinDays: Number(e.target.value) })}
                  />
                }
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
                时区：{s.timezone} · 回看{s.historyDays}日 · 每日≤{s.dailyLimit}次调用 · {s.proactive ? '允许主动呈现' : '仅桌面留档'} · {s.feishu ? '允许投递本人飞书' : '不投递飞书'}{s.idleCheckinDays ? ` · 连续${s.idleCheckinDays}天无互动飞书问候一次` : ''}。
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
                    const readyToday = state.history.some(c => c.day === day && c.state === 'READY');
                    void act(() => lifeReviewApi.prepare(day, 0, false), readyToday ? '今日复盘已就绪；如需追加内容请点卡片下方「补充经历」' : '已开始后台生成今日复盘，请稍候…');
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

          <LifeOutreachSection
            settings={s}
            habit={state.habit}
            habitSuggestion={state.habitSuggestion}
            candidates={state.candidates}
            nudges={state.nudges}
            busy={busy}
            disabled={disabled}
            habitModalOpen={editor === 'habit'}
            hour={hour}
            habitSource={habitSource}
            onHourChange={setHour}
            onHabitSourceChange={setHabitSource}
            onOpenHabitModal={(initialHour, initialSource) => {
              setHour(initialHour);
              setHabitSource(initialSource);
              setEditor('habit');
            }}
            onCloseHabitModal={closeEditor}
            act={act}
          />
        </>
      )}
    </SettingsPageLayout>
  );
}
