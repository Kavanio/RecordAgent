import type { LifeCompanionView, LifeContent, LifeHabit, LifeHabitSuggestion, LifeSettings } from '../../../types/lifeReview';
import { CompanionPulseCard } from './CompanionPulseCard';
import { GoalBoard } from './GoalBoard';
import { RhythmCard } from './RhythmCard';
import { TodayReviewHero } from './TodayReviewHero';
import styles from './StatusTodayView.module.css';

interface StatusTodayViewProps {
  settings: LifeSettings;
  companion: LifeCompanionView | null;
  history: LifeContent[];
  habit: LifeHabit | null;
  suggestion: LifeHabitSuggestion | null;
  today: string;
  busy: boolean;
  generating: boolean;
  disabled: boolean;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
  onOpenSettings?: (tab?: 'review' | 'context') => void;
}

/** 「今天」分段：左主视觉卡片聚焦「今天的复盘 + 下一步小步」，右栏按「个人目标 → 活态节律 → 陪伴状态」由重到轻排布。 */
export function StatusTodayView({
  settings, companion, history, habit, suggestion, today, busy, generating, disabled, act, onOpenSettings,
}: StatusTodayViewProps) {
  return <div className={styles.page} role="tabpanel" id="status-panel-today" aria-labelledby="status-tab-today">
    <div className={styles.columns}>
      <div className={styles.primary}>
        <TodayReviewHero settings={settings} history={history} habit={habit} today={today}
          busy={busy} generating={generating} disabled={disabled} act={act} onOpenSettings={onOpenSettings} />
      </div>
      <aside className={styles.secondary} aria-label="目标、节律与陪伴">
        <GoalBoard />
        <RhythmCard timezone={settings.timezone} habit={habit} suggestion={suggestion} cognition={companion?.cognition ?? null}
          busy={busy} disabled={disabled} act={act} />
        <CompanionPulseCard companion={companion} timezone={settings.timezone} busy={busy} act={act} />
      </aside>
    </div>
  </div>;
}
