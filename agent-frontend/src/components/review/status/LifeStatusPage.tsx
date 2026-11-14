import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, Settings2, Sparkles } from 'lucide-react';
import {
  dayTitle, greetingFor, isStatusTab, STATUS_TAB_PREFERENCE, zonedDay, zonedHour, type StatusTab,
} from '../../../features/review/statusPresentation';
import { readPreference, writePreference } from '../../../services/desktopPreferences';
import { fetchMemoryStatus } from '../../../services/experienceMemoryService';
import { fetchProfileMaintenance } from '../../../services/personalContextService';
import { Button } from '../../common/Button';
import { LoadingTree } from '../../common/LoadingTree';
import { TopBar } from '../../common/TopBar';
import { TopBarAction } from '../../common/TopBarAction';
import { useLifeReview } from '../useLifeReview';
import { StatusInsightView } from './StatusInsightView';
import { StatusTabs } from './StatusTabs';
import { StatusTimelineView } from './StatusTimelineView';
import { StatusTodayView } from './StatusTodayView';
import styles from './LifeStatusPage.module.css';

interface LifeStatusPageProps {
  onOpenSettings?: (tab?: 'review' | 'context') => void;
  initialTab?: StatusTab;
}

/** 侧边栏「状态」一级工作台：以今天 / 认知 / 足迹三分段展示每日复盘、目标、节律、四维认知与联系足迹。 */
export function LifeStatusPage({ onOpenSettings, initialTab }: LifeStatusPageProps = {}) {
  const state = useLifeReview();
  const { settings: s, busy, act } = state;

  const [tab, setTab] = useState<StatusTab>(() => {
    if (initialTab) return initialTab;
    const saved = readPreference(STATUS_TAB_PREFERENCE);
    return isStatusTab(saved) ? saved : 'today';
  });
  const [memorySummary, setMemorySummary] = useState<{ active: number; total: number } | null>(null);
  const [hasProposal, setHasProposal] = useState(false);

  const loadAuxStatus = useCallback(async () => {
    const [mem, maint] = await Promise.all([
      fetchMemoryStatus()
        .then(res => (res && typeof res.sourceTotal === 'number' && typeof res.sourceActive === 'number'
          ? { active: res.sourceActive, total: res.sourceTotal }
          : null))
        .catch(() => null),
      fetchProfileMaintenance()
        .then(res => Boolean(res && typeof res.lastResult === 'string' && res.pendingProposal))
        .catch(() => false),
    ]);
    setMemorySummary(mem);
    setHasProposal(maint);
  }, []);

  useEffect(() => {
    void loadAuxStatus();
  }, [loadAuxStatus]);

  function selectTab(next: StatusTab) {
    setTab(next);
    writePreference(STATUS_TAB_PREFERENCE, next);
  }

  async function refreshAll() {
    await Promise.all([state.load(), loadAuxStatus()]);
  }

  const timezone = s?.timezone || 'Asia/Shanghai';
  const today = zonedDay(timezone);
  const hour = zonedHour(timezone);
  const disabled = busy || state.dirty || !s?.enabled || !s.authorized;
  const reviewReady = Boolean(s?.enabled && s.authorized);

  return (
    <main className={styles.page}>
      <TopBar
        icon={<Sparkles size={19} />}
        title="状态"
        subtitle={`${dayTitle(today)} · ${greetingFor(hour)}`}
        actions={
          <div className={styles.headerActions}>
            <TopBarAction variant="ghost" icon={<RefreshCw size={14} />} disabled={busy || state.loading}
              title="刷新状态数据" onClick={() => void refreshAll()}>
              {state.loading ? '读取中…' : '刷新'}
            </TopBarAction>
            {onOpenSettings && (
              <>
                <TopBarAction variant="ghost" title="个人上下文设置" onClick={() => onOpenSettings('context')}>上下文</TopBarAction>
                <TopBarAction variant="ghost" iconOnly aria-label="生活复盘设置" icon={<Settings2 size={18} />}
                  onClick={() => onOpenSettings('review')}>
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
              <p role="alert" className={styles.error}>
                <span>{state.error}</span>
                <Button size="sm" onClick={() => void refreshAll()}>重新读取</Button>
              </p>
            )}

            {s && (
              <>
                <div className={styles.toolbar}>
                  <StatusTabs value={tab} onChange={selectTab} />
                  <div className={styles.pills} aria-label="状态摘要">
                    {reviewReady ? (
                      <span className={styles.quietStat} data-state="on"><i aria-hidden="true" />复盘已授权</span>
                    ) : (
                      <button type="button" className={styles.pillBtn} data-tone="warn"
                        disabled={!onOpenSettings} onClick={() => onOpenSettings?.('review')}>
                        <i aria-hidden="true" />{!s.enabled ? '复盘未启用 · 去设置' : '复盘未授权 · 去开启'}
                      </button>
                    )}

                    {state.companion && (
                      <button type="button" className={styles.quietBtn} data-state={state.companion.companionEnabled ? 'on' : 'off'}
                        onClick={() => selectTab('today')}>
                        <i aria-hidden="true" />{state.companion.companionEnabled ? '陪伴中' : '陪伴未开启'}
                      </button>
                    )}

                    {hasProposal && (
                      <button type="button" className={styles.pillBtn} data-tone="warn" onClick={() => selectTab('insight')}>
                        <i aria-hidden="true" />待确认画像建议
                      </button>
                    )}

                    {memorySummary && (
                      <button type="button" className={styles.quietBtn} onClick={() => selectTab('insight')}>
                        经历来源 {memorySummary.total}
                      </button>
                    )}
                  </div>
                </div>

                {tab === 'today' && (
                  <StatusTodayView settings={s} companion={state.companion} history={state.history}
                    habit={state.habit} suggestion={state.habitSuggestion} today={today}
                    busy={busy} generating={state.generating} disabled={disabled} act={act} onOpenSettings={onOpenSettings} />
                )}

                {tab === 'insight' && (
                  <StatusInsightView companion={state.companion} timezone={timezone} busy={busy}
                    memorySummary={memorySummary} onPendingProposalChange={setHasProposal} act={act} />
                )}

                {tab === 'timeline' && (
                  <StatusTimelineView settings={s} history={state.history} candidates={state.candidates}
                    nudges={state.nudges} archives={state.archives} busy={busy} disabled={disabled} act={act} />
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </main>
  );
}
