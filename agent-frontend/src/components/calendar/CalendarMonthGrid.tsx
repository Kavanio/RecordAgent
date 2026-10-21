import { useEffect, useRef, useState } from 'react';
import type { DailyDaySummary } from '../../types/dailyEvent';
import { formatLocalDate } from '../../services/dailyEvents';
import { CalendarDateCell } from './CalendarDateCell';
import { CalendarDayPreview } from './CalendarDayPreview';
import styles from './CalendarMonthGrid.module.css';

interface Props {
  days: Date[];
  month: number;
  selectedKey: string;
  todayKey: string;
  available: boolean;
  loading?: boolean;
  summaries: Record<string, DailyDaySummary>;
  studyByDate: Map<string, number>;
  recordsByDate: Map<string, number>;
  onSelect: (date: Date) => void;
}
/** 月历仅维护一个浮层和一组定时器，快速跨日期移动不会堆叠预览。 */
export function CalendarMonthGrid({
  days,
  month,
  selectedKey,
  todayKey,
  available,
  loading = false,
  summaries,
  studyByDate,
  recordsByDate,
  onSelect,
}: Props) {
  const [preview, setPreview] = useState<{
    date: Date;
    anchor: HTMLButtonElement;
  } | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cancel = () => {
    if (timer.current) clearTimeout(timer.current);
  };
  const close = () => {
    cancel();
    setPreview(null);
  };
  const leave = () => {
    cancel();
    timer.current = setTimeout(() => setPreview(null), 300);
  };
  useEffect(() => {
    setPreview(null);
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
  }, [days]);
  return (
    <>
      <div className={styles.grid} aria-busy={loading} aria-label={loading ? '正在读取月历摘要' : '月历日期'}>
        {days.map((date) => {
          const key = formatLocalDate(date);
          return (
            <CalendarDateCell
              key={key}
              date={date}
              dateKey={key}
              inMonth={date.getMonth() === month}
              today={key === todayKey}
              selected={key === selectedKey}
              available={available}
              loading={loading}
              summary={summaries[key]}
              studySeconds={studyByDate.get(key)}
              recordCount={recordsByDate.get(key)}
              previewOpen={preview?.date === date}
              onLeave={leave}
              onSelect={() => {
                close();
                onSelect(date);
              }}
              onPreview={(anchor, immediate) => {
                cancel();
                if (immediate) setPreview({ date, anchor });
                else
                  timer.current = setTimeout(
                    () => setPreview({ date, anchor }),
                    600,
                  );
              }}
            />
          );
        })}
      </div>
      {preview && (
        <CalendarDayPreview
          key={formatLocalDate(preview.date)}
          date={preview.date}
          id="calendar-day-preview"
          anchor={preview.anchor}
          onEnter={cancel}
          onLeave={leave}
          onClose={close}
        />
      )}
    </>
  );
}
