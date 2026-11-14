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

/** 「今天」分段：左栏直出今日复盘，右栏集中展示陪伴节奏、个人目标与作息节律。 */
export function StatusTodayView({
  settings, companion, history, habit, suggestion, today, busy, generating, disabled, act, onOpenSettings,
}: StatusTodayViewProps) {
  return <div className={styles.layout} role="tabpanel" id="status-panel-today" aria-labelledby="status-tab-today">
    <div className={styles.primary}>
      <TodayReviewHero settings={settings} history={history} habit={habit} today={today}
        busy={busy} generating={generating} disabled={disabled} act={act} onOpenSettings={onOpenSettings} />
    </div>
    <div className={styles.secondary}>
      <CompanionPulseCard companion={companion} timezone={settings.timezone} busy={busy} act={act} />
      <GoalBoard />
      <RhythmCard habit={habit} suggestion={suggestion} cognition={companion?.cognition ?? null}
        busy={busy} disabled={disabled} act={act} />
    </div>
  </div>;
}
