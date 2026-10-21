import { useEffect, useRef, type CSSProperties } from 'react';
import { CaretLeftIcon, CaretRightIcon } from '@phosphor-icons/react';
import type { StudySession } from '../../types/study';
import styles from './StudyTimeline.module.css';

interface StudyTimelineProps {
  sessions: StudySession[];
  dateKey: string;
  now: number;
}

const HOURS = 24;
const TICKS = Array.from({ length: 13 }, (_, index) => index * 2);

function formatClock(instant: string): string {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(instant));
}

function formatDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  if (minutes < 1) return seconds > 0 ? '不足 1 分钟' : '0 分钟';
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return hours ? `${hours} 小时${rest ? ` ${rest} 分钟` : ''}` : `${minutes} 分钟`;
}

function position(session: StudySession, dateKey: string, now: number) {
  const dayStart = new Date(`${dateKey}T00:00:00`).getTime();
  const dayEnd = new Date(`${dateKey}T24:00:00`).getTime();
  const start = Math.max(dayStart, new Date(session.startedAt).getTime());
  const end = Math.min(dayEnd, session.endedAt ? new Date(session.endedAt).getTime() : now);
  return {
    left: Math.max(0, (start - dayStart) / (dayEnd - dayStart) * 100),
    width: Math.max(0, (end - start) / (dayEnd - dayStart) * 100),
  };
}

function palette(index: number): CSSProperties {
  const hue = Math.round((205 + index * 137.508) % 360);
  return {
    '--task-bg': `hsl(${hue} 27% 88%)`,
    '--task-accent': `hsl(${hue} 29% 58%)`,
    '--task-text': `hsl(${hue} 30% 31%)`,
    '--task-enter-delay': `${Math.min(index * 40, 160)}ms`,
  } as CSSProperties;
}

/** 全天学习轨迹：固定任务信息，横向滚动浏览 24 小时时间画布。 */
export function StudyTimeline({ sessions, dateKey, now }: StudyTimelineProps) {
  const viewportRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport || !sessions.length) return;
    const dayStart = new Date(`${dateKey}T00:00:00`).getTime();
    const earliest = Math.max(dayStart, Math.min(...sessions.map((session) => new Date(session.startedAt).getTime())));
    const canvas = viewport.firstElementChild as HTMLElement | null;
    const labelWidth = canvas ? Number.parseFloat(getComputedStyle(canvas).getPropertyValue('--label-width')) : 132;
    const trackWidth = viewport.scrollWidth - labelWidth;
    viewport.scrollLeft = Math.max(0, (earliest - dayStart) / 86_400_000 * trackWidth - 48);
  }, [dateKey, sessions]);

  const scroll = (direction: -1 | 1) => viewportRef.current?.scrollBy({ left: direction * 520, behavior: 'smooth' });

  return <div className={styles.timeline}>
    <div className={styles.viewport} ref={viewportRef} aria-label="全天学习轨迹，可横向滚动查看">
      <div className={styles.canvas}>
        <div className={styles.hours} aria-hidden="true">
          <span />
          <div>{TICKS.map((hour, index) => <time key={hour} style={{ left: `${hour / HOURS * 100}%` }} className={index === 0 ? styles.firstTick : index === TICKS.length - 1 ? styles.lastTick : ''}>{String(hour).padStart(2, '0')}</time>)}</div>
        </div>
        {sessions.map((session, index) => {
          const taskPosition = position(session, dateKey, now);
          const endTime = session.endedAt ? formatClock(session.endedAt) : '现在';
          const duration = formatDuration(session.durationSeconds);
          return <div className={styles.row} style={palette(index)} key={`${dateKey}:${session.id}`}>
            <div className={styles.label}><i /><strong>{session.content}</strong><span>{formatClock(session.startedAt)} — {endTime} · {duration}</span></div>
            <div className={styles.track}>
              <div className={`${styles.task} ${session.status === 'active' ? styles.activeTask : ''}`} style={{ left: `${taskPosition.left}%`, width: `${taskPosition.width}%` }}
                title={`${session.content} · ${session.category} · ${duration}`} role="img" aria-label={`${session.content}，${formatClock(session.startedAt)}至${endTime}，${duration}`}>
                <span>{session.category}</span>
              </div>
            </div>
          </div>;
        })}
      </div>
    </div>
    <div className={styles.navigation}><span>全天 00:00–24:00 · 横向滚动查看</span><div><button type="button" onClick={() => scroll(-1)} aria-label="时间轴向左滚动"><CaretLeftIcon size={14} weight="light" aria-hidden="true" /></button><button type="button" onClick={() => scroll(1)} aria-label="时间轴向右滚动"><CaretRightIcon size={14} weight="light" aria-hidden="true" /></button></div></div>
  </div>;
}
