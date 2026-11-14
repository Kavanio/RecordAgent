import { useState } from 'react';
import { RefreshCw, Settings2, Sparkles } from 'lucide-react';
import { lifeReviewApi } from '../../services/lifeReviewApi';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { LoadingTree } from '../common/LoadingTree';
import { TopBar } from '../common/TopBar';
import { TopBarAction } from '../common/TopBarAction';
import { MemoryDocumentManager } from '../memory/MemoryDocumentManager';
import { PersonalProfileSection } from '../settings/PersonalProfileSection';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { LifeCompanionSection } from './LifeCompanionSection';
import { LifeContentCard } from './LifeContentCard';
import { LifeGoalSettings } from './LifeGoalSettings';
import { LifeOutreachSection } from './LifeOutreachSection';
import { ReviewDisclosure } from './ReviewDisclosure';
import { ReviewEmptyState } from './ReviewEmptyState';
import { useLifeReview } from './useLifeReview';
import reviewStyles from './LifeReviewSettings.module.css';
import styles from './LifeStatusPage.module.css';

interface LifeStatusPageProps {
  onOpenSettings?: (tab?: 'review' | 'context') => void;
}

/** 侧边栏「状态」一级工作台：集中管理每日复盘、个人目标、Agent 系统性认知、个人画像与经历来源、作息及主动提醒记录。 */
export function LifeStatusPage({ onOpenSettings }: LifeStatusPageProps = {}) {
  const state = useLifeReview();
  const { settings: s, busy, act } = state;
  const [habitModalOpen, setHabitModalOpen] = useState(false);
  const [hour, setHour] = useState(21);
  const [habitSource, setHabitSource] = useState('');
  const [refreshOpen, setRefreshOpen] = useState(false);

  const disabled = busy || state.dirty || !s?.enabled || !s.authorized;

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

  const orderedHistory = [...state.history].sort((a, b) => b.day.localeCompare(a.day) || b.version - a.version);
  const todayContent = orderedHistory.find(c => c.day === today());

  return (
    <main className={styles.page}>
      <TopBar
        icon={<Sparkles size={19} />}
        title="状态"
        subtitle="日常复盘、个人目标与自我认知"
        actions={
          <div className={styles.headerActions}>
            <TopBarAction
              variant="ghost"
              icon={<RefreshCw size={14} />}
              disabled={busy || state.loading}
              onClick={() => void state.load()}
            >
              {state.loading ? '正在读取…' : '刷新数据'}
            </TopBarAction>
            {onOpenSettings && (
              <>
                <TopBarAction variant="ghost" onClick={() => onOpenSettings('context')}>
                  个人上下文设置
                </TopBarAction>
                <TopBarAction
                  variant="ghost"
                  iconOnly
                  aria-label="生活复盘设置"
                  icon={<Settings2 size={18} />}
                  onClick={() => onOpenSettings('review')}
                >
                  <span className={styles.visuallyHidden}>生活复盘设置</span>
                </TopBarAction>
              </>
            )}
          </div>
        }
      />

      <div className={styles.workspace}>
        <div className={styles.content}>
          <div className={styles.contentInner}>
            {state.loading && !s && (
              <div className={styles.loadingCenter}>
                <LoadingTree size="large" label="正在读取个人状态与复盘…" />
              </div>
            )}

            {state.error && (
              <p role="alert" className={reviewStyles.error}>
                {state.error}
                <Button size="sm" onClick={() => void state.load()}>重新读取</Button>
              </p>
            )}

            {s && (
              <>
                <SettingsGroup title="我的复盘" description="每份内容标明归属日、覆盖截止点与版本。补充产生新版本，保留已发送副本；准备中可稍后刷新。">
                  {(!s.enabled || !s.authorized) && (
                    <SettingsRow
                      label={!s.enabled ? '生活复盘尚未启用' : '生活复盘尚未完成用途授权'}
                      description="启用并确认读取范围与模型授权后，即可按设定时间或手动准备今日复盘。"
                      control={
                        onOpenSettings ? (
                          <Button size="sm" variant="outline" onClick={() => onOpenSettings('review')}>
                            前往复盘设置
                          </Button>
                        ) : null
                      }
                    />
                  )}
                  <SettingsRow
                    label="准备今天"
                    description={state.generating ? '正在后台调用模型生成复盘，完成后会自动刷新…' : `每日 ${state.habit?.active ? state.habit.availableHour : 21}:00 自动准备。已有当天版本时直接查看；更新资料请点“更新今天的复盘”。`}
                    control={
                      <div className={reviewStyles.choices}>
                        <Button size="sm" variant="primary" disabled={disabled || state.generating} onClick={() => {
                          const day = today();
                          const readyToday = state.history.some(c => c.day === day && c.state === 'READY');
                          void act(() => lifeReviewApi.prepare(day, 0, false), readyToday ? '正在查看今天已有版本；需要重新采集资料时请点“更新今天的复盘”' : '已开始后台生成今日复盘，请稍候…');
                        }}>
                          {state.generating ? '正在生成复盘…' : '准备今天的复盘'}
                        </Button>
                        {todayContent && !state.generating && (
                          <Button size="sm" disabled={disabled || todayContent.state === 'UNKNOWN'} onClick={() => setRefreshOpen(true)}>
                            更新今天的复盘
                          </Button>
                        )}
                        <Button size="sm" disabled={disabled} onClick={() => {
                          setHour(state.habit?.active ? state.habit.availableHour : 21);
                          setHabitSource(state.habit?.source || '');
                          setHabitModalOpen(true);
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
                  {!todayContent && orderedHistory.length > 0 && <p className={reviewStyles.muted}>今天尚无复盘，以下为最近一天的记录。</p>}
                  {orderedHistory.slice(0, 1).map(c => (
                    <LifeContentCard key={c.id} content={c} timezone={c.timezone || s.timezone} busy={busy} authorized={!disabled} feishu={s.feishu} act={act} />
                  ))}
                  {state.history.length > 1 && (
                    <ReviewDisclosure variant="inline" title={`历史版本（${state.history.length - 1}）`} description="旧版保留原内容；展开查看，不会重新生成。">
                      {orderedHistory.slice(1).map(c => (
                        <LifeContentCard key={c.id} content={c} timezone={c.timezone || s.timezone} busy={busy} authorized={!disabled} feishu={s.feishu} act={act} />
                      ))}
                    </ReviewDisclosure>
                  )}
                  {state.archives.map((a, i) => (
                    <details key={i}>
                      <summary>重置前的只读外发副本 {i + 1}</summary>
                      <p className={reviewStyles.archive}>{a}</p>
                    </details>
                  ))}
                </SettingsGroup>

                <ConfirmDialog
                  open={refreshOpen}
                  title="更新今天的复盘"
                  tone="info"
                  confirmText="确认更新"
                  busy={busy}
                  description="重新采集已授权范围内截至现在的资料，并生成新版本，可能占用一次模型调用额度。旧版本与已发送副本保留。"
                  onClose={() => setRefreshOpen(false)}
                  onConfirm={() => {
                    setRefreshOpen(false);
                    if (todayContent) void act(() => lifeReviewApi.prepare(today(), todayContent.version, true), '已安排更新今天的复盘');
                  }}
                />

                <LifeGoalSettings />

                {state.companion && (
                  <LifeCompanionSection
                    companion={state.companion}
                    timezone={s.timezone}
                    busy={busy}
                    act={act}
                  />
                )}

                <PersonalProfileSection showMaintenanceToggle={false} />
                <MemoryDocumentManager />

                <LifeOutreachSection
                  settings={s}
                  habit={state.habit}
                  habitSuggestion={state.habitSuggestion}
                  candidates={state.candidates}
                  nudges={state.nudges}
                  busy={busy}
                  disabled={disabled}
                  habitModalOpen={habitModalOpen}
                  hour={hour}
                  habitSource={habitSource}
                  onHourChange={setHour}
                  onHabitSourceChange={setHabitSource}
                  onOpenHabitModal={(initialHour, initialSource) => {
                    setHour(initialHour);
                    setHabitSource(initialSource);
                    setHabitModalOpen(true);
                  }}
                  onCloseHabitModal={() => setHabitModalOpen(false)}
                  act={act}
                />
              </>
            )}
          </div>
        </div>
      </div>
    </main>
  );
}
