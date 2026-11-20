import type { DailyDaySummary } from '../../types/dailyEvent';
import { calendarCellMetrics } from '../../features/calendar/calendarMetrics';
import { Skeleton } from '../common/Skeleton';
import styles from './CalendarDateCell.module.css';

interface Props {
  date: Date;
  dateKey: string;
  inMonth: boolean;
  today: boolean;
  selected: boolean;
  available: boolean;
  loading?: boolean;
  previewOpen: boolean;
  summary?: DailyDaySummary;
  recordCount?: number;
  recordChars?: number;
  tokenTotal?: number;
  onSelect: () => void;
  onPreview: (anchor: HTMLButtonElement, immediate: boolean) => void;
  onLeave: () => void;
}

/** 极简无边框圆角日期面块：顶部居中日期数字，下方展示当日核心量化指标。 */
export function CalendarDateCell({
  date,
  dateKey,
  inMonth,
  today,
  selected,
  available,
  loading = false,
  previewOpen,
  summary,
  recordCount = 0,
  recordChars = 0,
  tokenTotal = 0,
  onSelect,
  onPreview,
  onLeave,
}: Props) {
  const metrics = calendarCellMetrics(summary, recordCount, recordChars, tokenTotal);
  const hasData = inMonth && !loading && metrics.length > 0;

  return (
    <button
      type="button"
      className={[
        styles.cell,
        !inMonth ? styles.outside : '',
        hasData ? styles.hasData : '',
        selected ? styles.selected : '',
      ].filter(Boolean).join(' ')}
      onMouseEnter={(event) => onPreview(event.currentTarget, false)}
      onMouseLeave={onLeave}
      onFocus={(event) => {
        if (event.currentTarget.matches(':focus-visible'))
          onPreview(event.currentTarget, true);
      }}
      onBlur={onLeave}
      onClick={onSelect}
      aria-pressed={selected}
      aria-current={today ? 'date' : undefined}
      aria-expanded={previewOpen}
      aria-controls={previewOpen ? 'calendar-day-preview' : undefined}
      aria-label={
        loading
          ? `${dateKey}，正在读取摘要`
          : `${dateKey}，${metrics.map((metric) => metric.title).join('，') || '暂无记录'}${!available ? '，部分摘要暂不可用' : ''}`
      }
    >
      <span className={`${styles.number} ${today ? styles.today : ''}`}>
        {date.getDate()}
      </span>
      {inMonth && (
        <span className={styles.metrics}>
          {loading ? (
            <Skeleton variant="text" decorative />
          ) : (
            <>
              {metrics.map((metric) => (
                <span key={metric.id} className={styles.metricRow} title={metric.title}>
                  <span className={styles.metricLabel}>{metric.label}</span>
                  <span className={styles.metricValue}>{metric.value}</span>
                </span>
              ))}
              {!available && <span className={styles.unavailable}>部分摘要暂不可用</span>}
            </>
          )}
        </span>
      )}
    </button>
  );
}
