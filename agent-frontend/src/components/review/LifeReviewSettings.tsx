import { useState } from 'react';
import { lifeReviewApi } from '../../services/lifeReviewApi';
import type { LifeSource } from '../../types/lifeReview';
import { Button } from '../common/Button';
import { Select } from '../common/Select';
import { TextInput } from '../common/TextInput';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { SettingsPageLayout } from '../settings/SettingsPageLayout';
import { LifeSignalSettingsRows } from './LifeSignalSettingsRows';
import { ReviewDisclosure } from './ReviewDisclosure';
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

interface LifeReviewSettingsProps {
  onOpenStatus?: () => void;
}

/** 生活复盘独立设置入口；专注配置范围、提醒信号与用途授权，日常复盘内容与认知维护位于左侧「状态」模块。 */
export function LifeReviewSettings({ onOpenStatus }: LifeReviewSettingsProps = {}) {
  const state = useLifeReview();
  const { settings: s, busy, act, patch } = state;
  const [editor, setEditor] = useState<'settings' | 'consent' | null>(null);

  function closeEditor() {
    state.discard();
    setEditor(null);
  }

  const provider = state.providers.find(p => p.id === s?.providerId);

  return (
    <SettingsPageLayout
      title="生活复盘"
      description="配置生活复盘读取范围、固定模型、主动提醒与用途授权。"
      actions={
        <Button
          size="sm"
          variant="outline"
          disabled={busy || state.loading}
          onClick={() => void state.load()}
        >
          {state.loading ? '正在读取…' : '刷新数据'}
        </Button>
      }
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
              description={`${s.sources.map(id => SOURCES.find(source => source.id === id)?.label).join('、') || '尚未选择来源'} · ${s.modelName || '尚未选择模型'} · 回看${s.historyDays}日 · 每日≤${s.dailyLimit}次 · ${s.proactive ? '主动呈现' : '仅桌面留档'}${s.feishu ? ' · 飞书' : ''}${s.idleCheckinDays ? ` · ${s.idleCheckinDays}天未互动问候` : ''}${s.weatherAlertEnabled ? ' · 天气提醒' : ''}${s.expenseAlertEnabled ? ' · 支出提醒' : ''}${s.serverAlertEnabled ? ' · 服务器提醒' : ''}${s.mailAlertEnabled ? ` · 邮件提醒${s.mailStatus ? `：${s.mailStatus}` : ''}` : ''}`}
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
                          onChange={() => {
                            const nextSources = s.sources.includes(source.id)
                              ? s.sources.filter(id => id !== source.id)
                              : [...s.sources, source.id];
                            patch({
                              sources: nextSources,
                              expenseAlertEnabled: nextSources.includes('FINANCE') ? s.expenseAlertEnabled : false,
                            });
                          }}
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
                    options={[
                      { value: '', label: '请选择模型连接' },
                      { value: 'ACTIVE', label: '跟随当前聊天默认模型（推荐）' },
                      ...state.providers.filter(p => p.isEnabled).map(p => ({ value: p.id, label: p.name })),
                    ]}
                    onChange={e => patch({
                      providerId: e.target.value,
                      modelName: e.target.value === 'ACTIVE' ? 'ACTIVE' : '',
                    })}
                  />
                }
              />
              <SettingsRow
                label="固定模型"
                control={
                  <Select
                    aria-label="生活复盘固定模型"
                    value={s.modelName}
                    disabled={busy || s.providerId === 'ACTIVE'}
                    options={
                      s.providerId === 'ACTIVE'
                        ? [{ value: 'ACTIVE', label: '自动跟随当前聊天模型' }]
                        : [{ value: '', label: '请选择模型' }, ...(provider?.models || []).map(m => ({ value: m.name, label: m.name }))]
                    }
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
              <LifeSignalSettingsRows settings={s} busy={busy} patch={patch} />
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
                  <Button size="sm" disabled={busy || state.dirty || !s.enabled || !s.revision} onClick={() => setEditor('consent')}>
                    核对并授权
                  </Button>
                )
              }
            />
            <ReviewFormModal open={editor === 'consent'} title="确认生活复盘授权" busy={busy} onClose={closeEditor}>
              <p className={styles.muted}>
                固定模型：{s.providerId} / {s.modelName}；来源：{s.sources.map(id => SOURCES.find(source => source.id === id)?.label).join('、')}。
                时区：{s.timezone} · 回看{s.historyDays}日 · 每日≤{s.dailyLimit}次调用 · {s.proactive ? '允许主动呈现' : '仅桌面留档'} · {s.feishu ? '允许投递本人飞书' : '不投递飞书'}{s.idleCheckinDays ? ` · 连续${s.idleCheckinDays}天无互动飞书问候一次` : ''}{s.weatherAlertEnabled ? ' · 开启天气变化提醒' : ''}{s.expenseAlertEnabled ? ' · 开启支出变化提醒' : ''}{s.serverAlertEnabled ? ` · 开启服务器状态提醒（${s.serverProbeName || '应用服务器'}）` : ''}{s.mailAlertEnabled ? ` · 开启重要邮件提醒（${s.imapHost || 'IMAP'}）` : ''}。
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

          <SettingsGroup title="状态与内容" description="每日复盘正文、个人目标、Agent 系统性认知、个人画像、经历来源管理与主动联系记录已集中在左侧「状态」模块维护。">
            <SettingsRow
              label="日常状态与复盘"
              description={`每日 ${state.habit?.active ? state.habit.availableHour : 21}:00 自动准备复盘；当前已保存 ${state.history.length} 份复盘版本。`}
              control={
                onOpenStatus ? (
                  <Button size="sm" variant="outline" disabled={busy || state.dirty} onClick={onOpenStatus}>
                    前往状态
                  </Button>
                ) : null
              }
            />
          </SettingsGroup>
        </>
      )}
    </SettingsPageLayout>
  );
}
