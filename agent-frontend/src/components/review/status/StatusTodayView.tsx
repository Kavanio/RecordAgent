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

/** 「今天」分段：顶部无框活态 24h 节律波形带；下方主次分明，左栏唯一焦点卡片突出「今天的复盘 + 下一步小步」，右栏安静排布「个人目标 + 主动陪伴」。 */
export function StatusTodayView({
  settings, companion, history, habit, suggestion, today, busy, generating, disabled, act, onOpenSettings,
}: StatusTodayViewProps) {
  return <div className={styles.page} role="tabpanel" id="status-panel-today" aria-labelledby="status-tab-today">
    <RhythmCard timezone={settings.timezone} habit={habit} suggestion={suggestion} cognition={companion?.cognition ?? null}
      busy={busy} disabled={disabled} act={act} />
    <div className={styles.columns}>
      <div className={styles.primary}>
        <TodayReviewHero settings={settings} history={history} habit={habit} today={today}
          busy={busy} generating={generating} disabled={disabled} act={act} onOpenSettings={onOpenSettings} />
      </div>
      <aside className={styles.secondary} aria-label="目标与陪伴">
        <GoalBoard />
        <CompanionPulseCard companion={companion} timezone={settings.timezone} busy={busy} act={act} />
      </aside>
    </div>
  </div>;
}
