import { TopBarAction } from '../common/TopBarAction';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowRightIcon, BookOpenIcon, CaretLeftIcon, CaretRightIcon, ClockCounterClockwiseIcon, PlayIcon } from '@phosphor-icons/react';
import { createManualStudySession, deleteStudySession, fetchStudyCategories, fetchStudySessions, fetchStudyStatistics, finishStudySession, startStudySession, updateStudySession } from '../../services/studyApi';
import { formatLocalDate } from '../../services/dailyEvents';
import { useStudyRealtime } from '../../context/studyRealtimeState';
import type { SaveStudySessionInput, StudySession, StudyStatistics } from '../../types/study';
import { Button } from '../common/Button';
import { AnimatedNumber } from '../common/AnimatedNumber';
import { Skeleton } from '../common/Skeleton';
import { ChartSkeleton } from '../common/ChartSkeleton';
import { mergeCategoryOptions } from '../common/categoryOptions';
import { useMessage } from '../common/Message';
import { TopBar } from '../common/TopBar';
import { StudyRecordModal } from './StudyRecordModal';
import { StudyStartModal } from './StudyStartModal';
import { StudyHistoryDrawer } from './StudyHistoryDrawer';
import { StudyHeatmap } from './StudyHeatmap';
import { StudyTimeline } from './StudyTimeline';
import { ActiveStudyCard } from './ActiveStudyCard';
import { STUDY_CATEGORIES } from './studyCategories';
import styles from './StudyPage.module.css';

const TIMEZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;

function shiftedDate(days: number): Date { const date = new Date(); date.setHours(12, 0, 0, 0); date.setDate(date.getDate() + days); return date; }
function shiftDateKey(dateKey: string, days: number): string {
  const date = new Date(`${dateKey}T12:00:00`);
  date.setDate(date.getDate() + days);
  return formatLocalDate(date);
}
function startOfMonth(): Date { const date = new Date(); date.setDate(1); date.setHours(12, 0, 0, 0); return date; }
function heatmapStart(): Date {
  const date = shiftedDate(-364);
  date.setDate(date.getDate() - date.getDay());
  return date;
}
function formatDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  if (minutes < 1) return seconds > 0 ? '不足 1 分钟' : '0 分钟';
  const hours = Math.floor(minutes / 60); const rest = minutes % 60;
  return hours ? `${hours} 小时${rest ? ` ${rest} 分钟` : ''}` : `${minutes} 分钟`;
}
function formatClock(instant: string): string { return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(instant)); }
function weekday(date: string): string { return new Intl.DateTimeFormat('zh-CN', { weekday: 'short' }).format(new Date(`${date}T12:00:00`)).replace('周', ''); }
function formatTimelineDate(dateKey: string, todayKey: string): string {
  const date = new Date(`${dateKey}T12:00:00`);
  const formatted = new Intl.DateTimeFormat('zh-CN', {
    ...(date.getFullYear() === new Date().getFullYear() ? {} : { year: 'numeric' as const }),
    month: 'long', day: 'numeric', weekday: 'short',
  }).format(date);
  return dateKey === todayKey ? `今天 · ${formatted}` : formatted;
}
/** 学习记录工作台：将即时打卡、时间轴、历史维护和统计集中在单一页面。 */
export function StudyPage() {
  const { showMessage } = useMessage();
  const { activeSession: active, syncGeneration, updatedAt } = useStudyRealtime();
  const [sessions, setSessions] = useState<StudySession[]>([]);
  const [weekStats, setWeekStats] = useState<StudyStatistics | null>(null);
  const [previousWeekStats, setPreviousWeekStats] = useState<StudyStatistics | null>(null);
  const [monthStats, setMonthStats] = useState<StudyStatistics | null>(null);
  const [heatmapStats, setHeatmapStats] = useState<StudyStatistics | null>(null);
  const [heatmapError, setHeatmapError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const reloadId = useRef(0);
  const [rememberedCategories, setRememberedCategories] = useState<string[]>([]);
  const [now, setNow] = useState(Date.now()); const [loading, setLoading] = useState(true); const [saving, setSaving] = useState(false);
  const todayKey = formatLocalDate(new Date());
  const [timelineDate, setTimelineDate] = useState(todayKey); const [timelineSessions, setTimelineSessions] = useState<StudySession[]>([]);
  // 同日期 SSE 刷新保留已展示的轨迹节点，避免加载占位导致入场动画重播。
  const [timelineSessionsDate, setTimelineSessionsDate] = useState<string | null>(null);
  const [timelineLoading, setTimelineLoading] = useState(true); const [timelineError, setTimelineError] = useState<string | null>(null); const [timelineRefresh, setTimelineRefresh] = useState(0);
  const [historyOpen, setHistoryOpen] = useState(false); const [recordError, setRecordError] = useState<string | null>(null);
  const [startModalOpen, setStartModalOpen] = useState(false); const [startError, setStartError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false); const [editing, setEditing] = useState<StudySession | null>(null); const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);

  const reload = useCallback(async () => {
    const id = ++reloadId.current;
    setLoading(true); setLoadError(null); setHeatmapError(null); const today = formatLocalDate(new Date());
    try {
      const [recent, nextWeek, previousWeek, nextMonth, nextHeatmap, categories] = await Promise.all([
        fetchStudySessions(formatLocalDate(shiftedDate(-30)), today, TIMEZONE),
        fetchStudyStatistics(formatLocalDate(shiftedDate(-6)), today, TIMEZONE),
        fetchStudyStatistics(formatLocalDate(shiftedDate(-13)), formatLocalDate(shiftedDate(-7)), TIMEZONE),
        fetchStudyStatistics(formatLocalDate(startOfMonth()), today, TIMEZONE),
        fetchStudyStatistics(formatLocalDate(heatmapStart()), today, TIMEZONE)
          .then((value) => ({ value, error: null }))
          .catch((cause: unknown) => ({ value: null, error: cause instanceof Error ? cause.message : '热力图加载失败' })),
        fetchStudyCategories(),
      ]);
      if (id !== reloadId.current) return;
      setSessions(recent); setWeekStats(nextWeek); setPreviousWeekStats(previousWeek); setMonthStats(nextMonth); setHeatmapStats(nextHeatmap.value); setHeatmapError(nextHeatmap.error); setRememberedCategories(categories);
    } catch (cause) {
      if (id === reloadId.current) setLoadError(cause instanceof Error ? cause.message : '学习记录加载失败，请稍后重试。');
    }
    finally { if (id === reloadId.current) setLoading(false); }
  }, []);
  useEffect(() => {
    void reload();
    return () => { reloadId.current += 1; };
  }, [reload, syncGeneration]);
  useEffect(() => { if (!active) return; setNow(Date.now()); const timer = window.setInterval(() => setNow(Date.now()), 1_000); return () => window.clearInterval(timer); }, [active]);
  useEffect(() => {
    let current = true;
    setTimelineLoading(true); setTimelineError(null);
    fetchStudySessions(timelineDate, timelineDate, TIMEZONE)
      .then((records) => { if (current) { setTimelineSessions(records); setTimelineSessionsDate(timelineDate); } })
      .catch((cause: unknown) => { if (current) setTimelineError(cause instanceof Error ? cause.message : '轨迹加载失败，请稍后重试。'); })
      .finally(() => { if (current) setTimelineLoading(false); });
    return () => { current = false; };
  }, [timelineDate, timelineRefresh, syncGeneration]);

  const elapsed = active ? Math.max(0, Math.floor((now - new Date(active.startedAt).getTime()) / 1_000)) : 0;
  const todayStat = weekStats?.days.find((day) => day.date === todayKey);
  const activeGrowth = active ? Math.max(0, Math.floor((now - updatedAt) / 1_000)) : 0;
  const todaySeconds = (todayStat?.durationSeconds ?? 0) + activeGrowth;
  const completedSessions = sessions.filter((session) => session.endedAt !== null);
  const timelineDaySessions = useMemo(() => {
    const timelineStart = new Date(`${timelineDate}T00:00:00`).getTime();
    const timelineEnd = new Date(`${timelineDate}T24:00:00`).getTime();
    return timelineSessions.filter((session) => new Date(session.startedAt).getTime() < timelineEnd
      && (!session.endedAt || new Date(session.endedAt).getTime() > timelineStart))
      .sort((first, second) => new Date(first.startedAt).getTime() - new Date(second.startedAt).getTime());
  }, [timelineDate, timelineSessions]);
  const liveWeekTotal = (weekStats?.totalDurationSeconds ?? 0) + activeGrowth;
  const liveMonthTotal = (monthStats?.totalDurationSeconds ?? 0) + activeGrowth;
  const liveWeekAverage = weekStats
    ? Math.floor(liveWeekTotal / Math.max(1, weekStats.days.length))
    : 0;
  const liveDayDuration = (day: StudyStatistics['days'][number]) => day.durationSeconds
    + (day.date === todayKey ? activeGrowth : 0);
  const chartMax = Math.max(1, ...(weekStats?.days.map(liveDayDuration) ?? [1]));
  const previousTotal = previousWeekStats?.totalDurationSeconds ?? 0;
  const weekChange = previousTotal > 0 ? Math.round((liveWeekTotal - previousTotal) / previousTotal * 100) : null;
  const topCategory = useMemo(() => {
    const totals = new Map<string, number>(); completedSessions.forEach((session) => totals.set(session.category, (totals.get(session.category) ?? 0) + session.durationSeconds));
    return [...totals.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;
  }, [completedSessions]);
  const categoryOptions = mergeCategoryOptions(STUDY_CATEGORIES, rememberedCategories);

  const begin = async (content: string, category: string) => {
    setSaving(true); setStartError(null);
    try { await startStudySession(content, category, TIMEZONE); setStartModalOpen(false); showMessage('success', '学习已开始'); }
    catch (cause) { setStartError(cause instanceof Error ? cause.message : '开始学习失败'); } finally { setSaving(false); }
  };
  const finish = async () => {
    if (!active) return; setSaving(true);
    try { await finishStudySession(active.id, active.version); showMessage('success', '本次学习已记录'); }
    catch (cause) { showMessage('error', cause instanceof Error ? cause.message : '结束学习失败'); } finally { setSaving(false); }
  };
  const openManual = () => { setEditing(null); setRecordError(null); setModalOpen(true); };
  const openEdit = (session: StudySession) => { setEditing(session); setRecordError(null); setModalOpen(true); };
  const saveRecord = async (input: SaveStudySessionInput) => {
    setSaving(true); setRecordError(null);
    try { if (editing) await updateStudySession(editing.id, editing.version, input); else await createManualStudySession(input); setModalOpen(false); setEditing(null); showMessage('success', editing ? '学习记录已更新' : '补卡完成'); }
    catch (cause) { setRecordError(cause instanceof Error ? cause.message : '学习记录保存失败'); } finally { setSaving(false); }
  };
  const remove = async (session: StudySession) => {
    if (confirmDeleteId !== session.id) { setConfirmDeleteId(session.id); return; } setSaving(true);
    try { await deleteStudySession(session.id, session.version); setConfirmDeleteId(null); showMessage('success', '学习记录已删除'); }
    catch (cause) { showMessage('error', cause instanceof Error ? cause.message : '删除失败'); } finally { setSaving(false); }
  };

  return <main className={styles.workspace}>
    <TopBar title="记录" subtitle="学习与专注" icon={<BookOpenIcon size={15} />} actions={<>
      <TopBarAction variant="outline" onClick={openManual}>补卡</TopBarAction>
      <TopBarAction variant="primary" icon={<PlayIcon size={13} weight="fill" />}
        onClick={() => { setStartError(null); setStartModalOpen(true); }} disabled={Boolean(active)}>{active ? '学习中' : '开始学习'}</TopBarAction>
    </>} />
    <div className={styles.page}><div className={styles.content}>
      <header className={styles.pageHeading}><div><h1>让专注有迹可循</h1><p>记录每一次投入，看见日积月累的进步。</p></div><span className={styles.pageDate}>{formatTimelineDate(todayKey, todayKey)}</span></header>
      {loadError && <div className={styles.timelineError} role="alert"><span>{loadError}{weekStats && '，当前显示上次读取的统计。'}</span><Button size="sm" variant="outline" onClick={() => void reload()}>重新加载记录</Button></div>}
      <section className={styles.overview} aria-label="学习概览" aria-busy={loading}>
        {!weekStats ? (loading && <div className={styles.overviewPlaceholder}><Skeleton variant="metrics" label="正在汇总学习记录…" /></div>) : <>
        <div className={styles.todayBlock}><span>今天已学习</span><strong><AnimatedNumber value={todaySeconds} ready={weekStats !== null} format={formatDuration} /></strong><small><AnimatedNumber value={todayStat?.sessionCount ?? 0} ready={weekStats !== null} /> 段已完成记录</small></div>
        <dl className={styles.periodStats}><div><dt>近 7 天</dt><dd><AnimatedNumber value={liveWeekTotal} ready={weekStats !== null} format={formatDuration} /></dd></div><div><dt>本月累计</dt><dd><AnimatedNumber value={liveMonthTotal} ready={monthStats !== null} format={formatDuration} /></dd></div><div><dt>本月学习天数</dt><dd><AnimatedNumber value={monthStats?.studyDays ?? 0} ready={monthStats !== null} /> 天</dd></div></dl>
        </>}
      </section>
      <div className={styles.mainLayout}><div className={styles.primaryColumn}>
        {active && <section className={styles.section}><Heading title="正在学习" subtitle="这段时间会持续计入今天的学习记录" />
          <ActiveStudyCard session={active} elapsedSeconds={elapsed} saving={saving} onFinish={() => void finish()} />
        </section>}
        <section className={styles.section}><Heading title="学习轨迹" subtitle={formatTimelineDate(timelineDate, todayKey)} side={<div className={styles.dateNavigation}>
          {timelineDate !== todayKey && <button type="button" className={styles.todayAction} onClick={() => setTimelineDate(todayKey)}>今天</button>}
          <button type="button" className={styles.dateArrow} aria-label="前一天" title="前一天" onClick={() => setTimelineDate((date) => shiftDateKey(date, -1))}><CaretLeftIcon size={13} /></button>
          <label className={styles.datePicker} title="选择日期"><span>{timelineDate.slice(5).replace('-', '.')}</span><input type="date" value={timelineDate} max={todayKey} aria-label="选择轨迹日期" onChange={(event) => { if (event.target.value) setTimelineDate(event.target.value); }} /></label>
          <button type="button" className={styles.dateArrow} aria-label="后一天" title="后一天" disabled={timelineDate >= todayKey} onClick={() => setTimelineDate((date) => shiftDateKey(date, 1))}><CaretRightIcon size={13} /></button>
        </div>} />
          {timelineLoading && timelineSessionsDate !== timelineDate ? <Skeleton variant="timeline" label="正在读取这一天的轨迹…" /> : timelineError ? <div className={styles.timelineError} role="alert"><span>{timelineError}</span><button type="button" onClick={() => setTimelineRefresh((value) => value + 1)}>重试</button></div> : timelineDaySessions.length ? <StudyTimeline sessions={timelineDaySessions} dateKey={timelineDate} now={now} /> : <div className={styles.timelineEmpty}><ClockCounterClockwiseIcon size={22} /><div><strong>{timelineDate === todayKey ? '今天的专注，从这里开始' : '这一天还没有学习记录'}</strong><p>{timelineDate === todayKey ? '开始一次学习，自动记录你的时间轨迹。' : '可以通过补卡，记录这一天的投入。'}</p></div><Button size="sm" variant="ghost" onClick={timelineDate === todayKey ? () => { setStartError(null); setStartModalOpen(true); } : openManual} disabled={timelineDate === todayKey && Boolean(active)}>{timelineDate === todayKey ? (active ? '学习中' : '开始学习') : '补卡'}</Button></div>}
        </section>
        <section className={styles.section}><Heading title="最近记录" subtitle="最近完成的学习片段" side={completedSessions.length > 0 ? <button className={styles.linkButton} type="button" onClick={() => setHistoryOpen(true)}>查看全部<ArrowRightIcon size={12} /></button> : undefined} />
          {loading && !weekStats ? <Skeleton label="正在读取最近的学习记录…" /> : !weekStats && loadError ? null : completedSessions.length ? <RecentSessions sessions={completedSessions.slice(0, 4)} onSelect={openEdit} /> : <div className={styles.empty}><ClockCounterClockwiseIcon size={19} /><strong>还没有学习记录</strong><span>开始一次学习，或使用右上角补卡。</span></div>}
        </section>
      </div><aside className={styles.insightColumn}>
        <section className={styles.sideSection}><Heading title="近 7 天节奏" subtitle="每日学习时长 · 分钟" />{!weekStats ? (loading ? <div className={styles.chartPlaceholder}><ChartSkeleton variant="bars" label="正在读取学习节奏…" /></div> : <p className={styles.insight}>学习统计暂不可用，请重试。</p>) : <><div className={styles.chart}>{weekStats?.days.map((day) => { const duration = liveDayDuration(day); return <div className={styles.chartDay} key={day.date} role="img" aria-label={`${day.date}，${formatDuration(duration)}`}><strong>{duration ? Math.round(duration / 60) : '—'}</strong><div className={styles.barArea}><i className={day.date === todayKey ? styles.todayBar : ''} style={{ height: `${duration / chartMax * 100}%` }} /></div><span>{weekday(day.date)}</span></div>; })}</div><dl className={styles.smallStats}><div><dt>日均时长</dt><dd>{formatDuration(liveWeekAverage)}</dd></div><div><dt>学习天数</dt><dd>{weekStats?.studyDays ?? 0} / 7 天</dd></div><div><dt>较前 7 天</dt><dd>{weekChange === null ? '暂无对比' : `${weekChange >= 0 ? '↑' : '↓'} ${Math.abs(weekChange)}%`}</dd></div><div><dt>完成次数</dt><dd>{weekStats?.sessionCount ?? 0} 次</dd></div></dl></>}</section>
        <section className={styles.sideSection}><Heading title="近期观察" />{!weekStats ? (loading ? <Skeleton variant="text" label="正在整理学习观察…" /> : <p className={styles.insight}>等待统计恢复。</p>) : <p className={styles.insight}>近 7 天学习了 <em>{weekStats?.studyDays ?? 0} 天</em>{weekChange !== null && <>，相比前 7 天{weekChange >= 0 ? '增加' : '减少'}了 <em>{formatDuration(Math.abs(liveWeekTotal - previousTotal))}</em></>}。{topCategory ? <>最近投入最多的是“<em>{topCategory}</em>”。</> : '完成第一段学习后，这里会生成观察。'}</p>}</section>
      </aside></div>
      <section className={styles.heatmapSection}><Heading title="学习热力图" subtitle="最近一年 · 颜色按每日学习时长加深" side={heatmapStats ? <span className={styles.heatmapSummary}>{heatmapStats.studyDays} 个学习日 · {formatDuration(heatmapStats.totalDurationSeconds)}</span> : undefined} />
        {heatmapStats ? <StudyHeatmap days={heatmapStats.days} to={todayKey} /> : heatmapError || (!loading && loadError) ? <div className={styles.heatmapError}><span>{heatmapError || '学习热力图暂不可用'}</span><button type="button" onClick={() => void reload()}>重新加载</button></div> : <Skeleton variant="heatmap" label="正在生成学习热力图…" />}
      </section>
    </div></div>
    <StudyStartModal open={startModalOpen} saving={saving} error={startError} categories={categoryOptions}
      onClose={() => { if (!saving) { setStartModalOpen(false); setStartError(null); } }} onStart={begin} />
    <StudyRecordModal open={modalOpen} session={editing} saving={saving} error={recordError} categories={categoryOptions} onClose={() => { if (!saving) { setModalOpen(false); setEditing(null); setRecordError(null); } }} onSave={saveRecord} />
    <StudyHistoryDrawer open={historyOpen} sessions={completedSessions} loading={loading && !weekStats} saving={saving} confirmDeleteId={confirmDeleteId}
      onClose={() => { setHistoryOpen(false); setConfirmDeleteId(null); }} onEdit={(session) => { setHistoryOpen(false); openEdit(session); }} onDelete={(session) => void remove(session)} />
  </main>;
}

function RecentSessions({ sessions, onSelect }: { sessions: StudySession[]; onSelect: (session: StudySession) => void }) {
  return <div className={styles.recentList}>
    {sessions.map((session) => <button type="button" className={styles.sessionRow} key={session.id} onClick={() => onSelect(session)}>
      <time className={styles.sessionDate} dateTime={session.startedAt}>{new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(new Date(session.startedAt))}</time>
      <div className={styles.sessionContent}><strong>{session.content || '自由学习'}</strong><span><em>{session.category}</em>{formatClock(session.startedAt)} — {session.endedAt ? formatClock(session.endedAt) : ''}</span></div>
      <b>{formatDuration(session.durationSeconds)}</b><CaretRightIcon size={12} aria-hidden="true" />
    </button>)}
  </div>;
}

function Heading({ title, subtitle, side }: { title: string; subtitle?: string; side?: React.ReactNode }) {
  return <div className={styles.sectionHeading}><div><h2>{title}</h2>{subtitle && <span>{subtitle}</span>}</div>{side && <div className={styles.sectionSide}>{side}</div>}</div>;
}

export default StudyPage;
