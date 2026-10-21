import { TopBarAction } from '../common/TopBarAction';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  CalendarDotsIcon,
  CaretLeftIcon,
  CaretRightIcon,
  ChecksIcon,
  ClockIcon,
  ImageIcon,
  MapPinIcon,
  NotePencilIcon,
  ReceiptIcon,
  WalletIcon,
} from '@phosphor-icons/react';
import type { CalendarDayEntry, CalendarJournal, CalendarRecordDraft } from '../../types/calendar';
import type { DailyDaySummary } from '../../types/dailyEvent';
import { createFinanceTransaction, fetchFinanceCategories, fetchFinanceOverview, fetchFinanceCashflowSummary } from '../../services/financeApi';
import {
  createDailyRecord,
  deleteDailyEvent,
  fetchDailyDay,
  fetchDailySummaries,
  formatLocalDate,
  setDailyTodoCompleted,
  updateDailyJournal,
} from '../../services/dailyEvents';
import type { CreateFinanceTransactionInput, FinanceAccount, FinanceCategoryOptions, FinanceCashflowSummary } from '../../types/finance';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import { Modal } from '../common/Modal';
import { LoadingOverlay } from '../common/LoadingOverlay';
import { Skeleton } from '../common/Skeleton';
import { TopBar } from '../common/TopBar';
import { formatStudyDuration } from './calendarPresentation';
import { CalendarMonthGrid } from './CalendarMonthGrid';
import { CalendarDayDetails } from './CalendarDayDetails';
import { CalendarStickyNotes } from './CalendarStickyNotes';
import { fetchStudyStatistics } from '../../services/studyApi';
import { fetchRecordDays } from '../../services/recordApi';
import { useStudyRealtime } from '../../context/studyRealtimeState';
import type { StudyStatistics } from '../../types/study';
import type { RecordDaySummary } from '../../types/record';
import { CalendarQuickCreate } from './CalendarQuickCreate';
import { DailyCashflowList } from './DailyCashflowList';
import { DailyRecordDeleteButton } from './DailyRecordDeleteButton';
import { DailyTodoList } from './DailyTodoList';
import { JournalEditorPage } from './JournalEditorPage';
import { TransactionModal } from '../finance/TransactionModal';
import { toCalendarDayEntry } from './dailyEventViewModel';
import styles from './CalendarView.module.css';

const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];
const WEEKDAY_FULL = '日一二三四五六';
const WEEK_STARTS_ON = 1;

interface DeleteTarget {
  id: string;
  version: number;
  kind: '待办' | '日程' | '花销' | '手记';
  title: string;
  recurrence?: 'none' | 'daily' | 'weekly' | 'monthly';
}

function startOfDay(date: Date): Date {
  const copy = new Date(date);
  copy.setHours(0, 0, 0, 0);
  return copy;
}

function addDays(date: Date, amount: number): Date {
  const copy = new Date(date);
  copy.setDate(copy.getDate() + amount);
  return copy;
}

function totalExpense(entry: CalendarDayEntry): number {
  return entry.expenses.reduce((total, expense) => total + expense.amount, 0);
}

function totalIncome(entry: CalendarDayEntry): number {
  return entry.incomes.reduce((total, income) => total + income.amount, 0);
}

function recurringDeleteImpact(recurrence: DeleteTarget['recurrence']): string {
  if (recurrence === 'monthly') return '，以后每个月都不会再出现';
  if (recurrence === 'weekly') return '，以后每周都不会再出现';
  if (recurrence === 'daily') return '，以后每天都不会再出现';
  return '';
}

const EMPTY_ENTRY: CalendarDayEntry = { todos: [], expenses: [], incomes: [], schedules: [], journals: [], photos: [], otherRecords: [] };

/** 日历工作区：轻量月度摘要与按需加载的每日详情。 */
export const CalendarView: React.FC = () => {
  const { showMessage } = useMessage();
  const { syncGeneration } = useStudyRealtime();
  const [studyStats, setStudyStats] = useState<StudyStatistics | null>(null);
  const [recordDays, setRecordDays] = useState<RecordDaySummary[]>([]);
  const [cashflow, setCashflow] = useState<FinanceCashflowSummary | null>(null);
  const [recordsReady, setRecordsReady] = useState(false);
  const [summariesReady, setSummariesReady] = useState(false);
  const [overviewError, setOverviewError] = useState('');
  const [overviewRetry, setOverviewRetry] = useState(0);
  const today = useMemo(() => startOfDay(new Date()), []);
  const [cursor, setCursor] = useState(today);
  const [selected, setSelected] = useState(today);
  const [entries, setEntries] = useState<Record<string, CalendarDayEntry>>({});
  const [summaries, setSummaries] = useState<Record<string, DailyDaySummary>>({});
  const [isDayLoading, setIsDayLoading] = useState(true);
  const [dayError, setDayError] = useState('');
  const [dayRetry, setDayRetry] = useState(0);
  const [isMonthLoading, setIsMonthLoading] = useState(true);
  const [isOverviewLoading, setIsOverviewLoading] = useState(true);
  const [monthError, setMonthError] = useState('');
  const [isPreparingFinance, setIsPreparingFinance] = useState(false);
  const preparingFinance = useRef(false);
  const pendingTodos = useRef(new Set<string>());
  const [pendingTodoIds, setPendingTodoIds] = useState<ReadonlySet<string>>(new Set());
  const [journalEditorDate, setJournalEditorDate] = useState<Date | null>(null);
  const [journalEditorId, setJournalEditorId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<DeleteTarget | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [isCashflowModalOpen, setIsCashflowModalOpen] = useState(false);
  const [isFinanceTransactionModalOpen, setIsFinanceTransactionModalOpen] = useState(false);
  const [financeAccounts, setFinanceAccounts] = useState<FinanceAccount[]>([]);
  const [financeCategories, setFinanceCategories] = useState<FinanceCategoryOptions>({ expense: [], income: [] });

  const days = useMemo(() => {
    const firstOfMonth = new Date(cursor.getFullYear(), cursor.getMonth(), 1);
    const leadingOffset = (firstOfMonth.getDay() - WEEK_STARTS_ON + 7) % 7;
    const gridStart = addDays(firstOfMonth, -leadingOffset);
    const monthDays = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 0).getDate();
    const gridSize = Math.max(35, Math.ceil((leadingOffset + monthDays) / 7) * 7);
    return Array.from({ length: gridSize }, (_, index) => addDays(gridStart, index));
  }, [cursor]);

  useEffect(() => {
    let active = true;
    setStudyStats(null); setRecordDays([]); setOverviewError(''); setCashflow(null); setRecordsReady(false);
    setIsOverviewLoading(true);
    const from = formatLocalDate(new Date(cursor.getFullYear(), cursor.getMonth(), 1));
    const to = formatLocalDate(new Date(cursor.getFullYear(), cursor.getMonth() + 1, 0));
    void Promise.allSettled([
      fetchStudyStatistics(from, to, Intl.DateTimeFormat().resolvedOptions().timeZone),
      fetchRecordDays(from, to),
      fetchFinanceCashflowSummary(from, to),
    ]).then(([study, records, finances]) => {
      if (!active) return;
      setIsOverviewLoading(false);
      if (study.status === 'fulfilled') setStudyStats(study.value);
      if (records.status === 'fulfilled') { setRecordDays(records.value); setRecordsReady(true); }
      if (finances.status === 'fulfilled') setCashflow(finances.value);
      if ([study, records, finances].some(result => result.status === 'rejected')) setOverviewError('部分月度摘要读取失败');
    });
    return () => { active = false; };
  }, [cursor, syncGeneration, overviewRetry]);

  const monthRecords = useMemo(() => Object.values(summaries), [summaries]);
  const todayKey = formatLocalDate(today);
  const monthTodoRecords = monthRecords.filter((record) => record.date <= todayKey);
  const monthTodoTotal = monthTodoRecords.reduce((total, record) => total + record.todoCount, 0);
  const monthTodoCompleted = monthTodoRecords.reduce((total, record) => total + record.completedTodoCount, 0);
  const studyByDate = new Map(studyStats?.days.map(day => [day.date, day.durationSeconds]));
  const recordsByDate = new Map(recordDays.map(day => [day.date, day.count]));
  const monthRecordCount = recordDays.reduce((total, day) => total + day.count, 0);

  const selectedKey = formatLocalDate(selected);
  const selectedEntry = entries[selectedKey] ?? EMPTY_ENTRY;
  const selectedJournals = (selectedEntry.journals ?? (selectedEntry.journal ? [selectedEntry.journal] : [])).filter(journal => journal.source !== 'record');
  const completedCount = selectedEntry.todos.filter((todo) => todo.completed).length;
  const selectedCashflowCount = selectedEntry.expenses.length + selectedEntry.incomes.length;
  const hasDailyRecord = Boolean(
    selectedEntry.todos.length
    || selectedEntry.expenses.length
    || selectedEntry.incomes.length
    || selectedEntry.schedules.length
    || selectedJournals.length
    || selectedEntry.photos.length
    || selectedEntry.otherRecords?.length,
  );
  const selectedLabel = `${selected.getFullYear()}年${selected.getMonth() + 1}月${selected.getDate()}日 · 星期${WEEKDAY_FULL[selected.getDay()]}`;

  const moveMonth = (delta: number) => setCursor(new Date(cursor.getFullYear(), cursor.getMonth() + delta, 1));

  const goToday = () => {
    setCursor(today);
    setSelected(today);
  };

  const loadDay = useCallback(async (date: Date) => {
    const key = formatLocalDate(date);
    const day = await fetchDailyDay(date);
    setEntries((current) => ({ ...current, [key]: toCalendarDayEntry(day) }));
  }, []);

  const loadMonth = useCallback(async () => {
    const first = new Date(cursor.getFullYear(), cursor.getMonth(), 1);
    const last = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 0);
    const items = await fetchDailySummaries(first, last);
    setSummaries(Object.fromEntries(items.map((item) => [item.date, item])));
    setSummariesReady(true);
  }, [cursor]);

  useEffect(() => {
    let active = true;
    setIsDayLoading(true);
    setDayError('');
    fetchDailyDay(selected)
      .then((day) => {
        if (active) setEntries((current) => ({ ...current, [day.date]: toCalendarDayEntry(day) }));
      })
      .catch((error: unknown) => {
        if (active) setDayError(error instanceof Error ? error.message : '读取日记录失败');
      })
      .finally(() => {
        if (active) setIsDayLoading(false);
      });
    return () => { active = false; };
  }, [selected, dayRetry, syncGeneration]);

  useEffect(() => {
    let active = true;
    setSummaries({});
    setSummariesReady(false);
    setIsMonthLoading(true);
    setMonthError('');
    const first = new Date(cursor.getFullYear(), cursor.getMonth(), 1);
    const last = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 0);
    fetchDailySummaries(first, last)
      .then((items) => {
        if (active) { setSummaries(Object.fromEntries(items.map((item) => [item.date, item]))); setSummariesReady(true); }
      })
      .catch((error: unknown) => {
        if (active) setMonthError(error instanceof Error ? error.message : '读取月度摘要失败');
      })
      .finally(() => { if (active) setIsMonthLoading(false); });
    return () => { active = false; };
  }, [cursor, showMessage, overviewRetry]);

  const refreshSelectedAndMonth = async () => {
    await Promise.all([loadDay(selected), loadMonth()]);
    setOverviewRetry(value => value + 1);
  };

  const openFinanceTransactionModal = async () => {
    if (preparingFinance.current) return;
    preparingFinance.current = true;
    setIsPreparingFinance(true);
    try {
      const [overview, categories] = await Promise.all([fetchFinanceOverview(), fetchFinanceCategories()]);
      if (!overview.accounts.length) {
        showMessage('info', '暂无资产账户，请先添加一个账户后再记账。');
        return;
      }
      setFinanceAccounts(overview.accounts);
      setFinanceCategories(categories);
      setIsFinanceTransactionModalOpen(true);
    } catch (error: unknown) {
      showMessage('error', error instanceof Error ? error.message : '读取财务账户失败，请稍后重试');
    } finally {
      preparingFinance.current = false;
      setIsPreparingFinance(false);
    }
  };

  const saveFinanceTransaction = async (input: CreateFinanceTransactionInput) => {
    await createFinanceTransaction(input);
    setIsFinanceTransactionModalOpen(false);
    try {
      await refreshSelectedAndMonth();
    } catch (error: unknown) {
      showMessage('error', error instanceof Error ? `流水已保存，但日历刷新失败：${error.message}` : '流水已保存，但日历刷新失败');
    }
  };

  const toggleTodo = async (todoId: string) => {
    const todo = selectedEntry.todos.find((item) => item.id === todoId);
    if (!todo || pendingTodos.current.has(todoId)) return;
    pendingTodos.current.add(todoId);
    setPendingTodoIds(new Set(pendingTodos.current));
    try {
      await setDailyTodoCompleted(todo.id, !todo.completed, todo.version ?? 0, selected);
      await refreshSelectedAndMonth();
    } catch (error: unknown) {
      showMessage('error', error instanceof Error ? error.message : '修改待办失败');
    } finally {
      pendingTodos.current.delete(todoId);
      setPendingTodoIds(new Set(pendingTodos.current));
    }
  };

  const createRecord = async (draft: CalendarRecordDraft) => {
    // 写入失败交回表单展示并保留草稿；写入成功后的刷新失败不能诱导重复创建。
    await createDailyRecord(selected, draft);
    showMessage('success', '日历记录已保存');
    try {
      await refreshSelectedAndMonth();
    } catch (error: unknown) {
      showMessage('error', error instanceof Error ? `记录已保存，但刷新失败：${error.message}` : '记录已保存，但刷新失败');
    }
  };

  const requestDelete = (
    record: { id?: string; version?: number; recurrence?: 'none' | 'daily' | 'weekly' | 'monthly' },
    kind: DeleteTarget['kind'],
    title: string,
  ) => {
    if (!record.id || record.version === undefined) {
      showMessage('error', `缺少${kind}版本信息，无法安全删除，请刷新后重试`);
      return;
    }
    setDeleteTarget({ id: record.id, version: record.version, kind, title, recurrence: record.recurrence });
  };

  const closeDeleteDialog = () => {
    if (isDeleting) return;
    setDeleteTarget(null);
  };

  const confirmDelete = async () => {
    if (!deleteTarget || isDeleting) return;
    setIsDeleting(true);
    try {
      await deleteDailyEvent(deleteTarget.id, deleteTarget.version);
      setDeleteTarget(null);
      try {
        await refreshSelectedAndMonth();
      } catch (error: unknown) {
        showMessage('error', error instanceof Error ? `记录已删除，但刷新失败：${error.message}` : '记录已删除，但刷新失败');
      }
    } catch (error: unknown) {
      showMessage('error', error instanceof Error ? error.message : `删除${deleteTarget.kind}失败`);
    } finally {
      setIsDeleting(false);
    }
  };

  const saveJournal = async (journal: CalendarJournal) => {
    if (!journalEditorDate) return;
    try {
      const editorEntry = entries[formatLocalDate(journalEditorDate)];
      const journals = editorEntry?.journals ?? (editorEntry?.journal ? [editorEntry.journal] : []);
      const existing = journals.find((item) => item.id === journalEditorId);
      if (existing?.id) {
        await updateDailyJournal(journalEditorDate, { ...journal, id: existing.id, version: existing.version });
      } else {
        await createDailyRecord(journalEditorDate, {
          kind: 'journal', title: journal.title, excerpt: journal.excerpt, mood: journal.mood,
        });
      }
      await loadDay(journalEditorDate);
      await loadMonth();
      setOverviewRetry(value => value + 1);
      setSelected(journalEditorDate);
      setJournalEditorDate(null);
      setJournalEditorId(null);
    } catch (error: unknown) {
      showMessage('error', error instanceof Error ? error.message : '保存手记失败');
    }
  };

  if (journalEditorDate) {
    const journalKey = formatLocalDate(journalEditorDate);
    const journalEntry = entries[journalKey];
    const journals = journalEntry?.journals ?? (journalEntry?.journal ? [journalEntry.journal] : []);
    return (
      <JournalEditorPage
        date={journalEditorDate}
        initialJournal={journals.find((item) => item.id === journalEditorId)}
        onBack={() => { setJournalEditorDate(null); setJournalEditorId(null); }}
        onSave={saveJournal}
      />
    );
  }

  return (
    <main className={styles.workspace} aria-busy={isPreparingFinance}>
      <div className={styles.interactive} inert={isPreparingFinance}>
      <TopBar
        icon={<CalendarDotsIcon size={16} />}
        title="日历"
        subtitle="本地数据"
        actions={(
          <>
            <CalendarQuickCreate
              selectedDate={selected}
              onCreate={createRecord}
              onWriteJournal={() => {
                setJournalEditorId(selectedJournals.find((journal) => journal.source !== 'record')?.id ?? null);
                setJournalEditorDate(selected);
              }}
              onCreateFinance={() => { void openFinanceTransactionModal(); }}
            />
            <TopBarAction onClick={goToday}>今天</TopBarAction>
            <TopBarAction iconOnly title="上个月" aria-label="上个月" onClick={() => moveMonth(-1)}><CaretLeftIcon size={17} /></TopBarAction>
            <TopBarAction iconOnly title="下个月" aria-label="下个月" onClick={() => moveMonth(1)}><CaretRightIcon size={17} /></TopBarAction>
          </>
        )}
      />
      <div className={styles.page}>
        <div className={styles.content}>
          <div className={styles.calendarLayout}>
            <section className={styles.monthPanel} aria-label="月历">
              <div className={styles.monthTitleRow}>
                <h2>{cursor.getFullYear()}年{cursor.getMonth() + 1}月</h2>
                <span>悬停预览 · 点击选择</span>
              </div>
              <div className={styles.weekHeader}>
                {WEEKDAY_LABELS.map((label) => <span key={label}>{label}</span>)}
              </div>
              <CalendarMonthGrid days={days} month={cursor.getMonth()} selectedKey={selectedKey} todayKey={todayKey}
                available={summariesReady && recordsReady && studyStats !== null}
                loading={isMonthLoading || isOverviewLoading}
                summaries={summaries} studyByDate={studyByDate} recordsByDate={recordsByDate}
                onSelect={date => setSelected(startOfDay(date))} />
              <div className={styles.itemLegend} aria-label="事项类别">
                <span><i className={styles.todoMarker} />待办</span><span><i className={styles.scheduleMarker} />日程</span>
                <span><i className={styles.studyMarker} />学习</span><span><i className={styles.recordMarker} />资料</span>
                <span><i className={styles.expenseMarker} />支出</span>
              </div>
              <section className={styles.monthReview} aria-label="本月摘要">
                <div className={styles.monthReviewHeading}><h3>本月摘要</h3><span>待办统计截至今天</span></div>
                {(overviewError || monthError) && <p className={styles.monthReviewEmpty}>{monthError || overviewError} <button type="button" onClick={() => setOverviewRetry(value => value + 1)}>重试</button></p>}
                {isMonthLoading || isOverviewLoading ? <Skeleton variant="metrics" label="正在读取本月摘要…" /> : <dl className={styles.monthStats}>
                  <div><dt>学习时长</dt><dd>{studyStats ? formatStudyDuration(studyStats.totalDurationSeconds) : '—'}</dd></div>
                  <div><dt>完成待办</dt><dd>{summariesReady ? `${monthTodoCompleted} / ${monthTodoTotal}` : '—'}</dd></div>
                  <div><dt>资料篇数</dt><dd>{recordsReady ? `${monthRecordCount} 篇` : '—'}</dd></div>
                  <div><dt>收支结余</dt><dd>{cashflow ? `¥${(cashflow.income - cashflow.expense).toFixed(2)}` : '—'}</dd></div>
                </dl>}
              </section>
            </section>

          <section className={styles.dayPanel} aria-label={`${selectedLabel}的日记录`} aria-busy={isDayLoading}>
            <div className={styles.dayHeading}>
              <div>
                <h2>{selectedLabel}</h2>
                <p>{isDayLoading ? '正在读取当天记录…' : dayError ? '日记录尚未更新' : hasDailyRecord ? '当天记录' : '暂无记录'}</p>
              </div>
            </div>

            {dayError && <p className={styles.monthReviewEmpty} role="alert">{dayError} {entries[selectedKey] && '以下为上次读取的数据。'}<Button size="sm" variant="ghost" onClick={() => setDayRetry(value => value + 1)}>重新读取日记录</Button></p>}
            {!entries[selectedKey] ? (isDayLoading && <Skeleton variant="metrics" label="正在读取当天汇总…" />) : <div className={styles.summaryLine} aria-label="当天汇总">
              <span className={styles.todoSummary}><ChecksIcon size={16} /><span><small>待办完成</small><strong>{completedCount}/{selectedEntry.todos.length || 0}</strong></span></span>
              <span className={styles.expenseSummary}><WalletIcon size={16} /><span><small>今日支出</small><strong>¥{totalExpense(selectedEntry).toFixed(2)}</strong></span></span>
              <span className={styles.incomeSummary}><WalletIcon size={16} /><span><small>今日收入</small><strong>¥{totalIncome(selectedEntry).toFixed(2)}</strong></span></span>
              <span className={styles.scheduleSummary}><ClockIcon size={16} /><span><small>日程</small><strong>{selectedEntry.schedules.length} 个</strong></span></span>
            </div>}

            {isDayLoading && !entries[selectedKey] ? <Skeleton label="正在读取日记录…" /> : !entries[selectedKey] && dayError ? null : hasDailyRecord ? <div className={styles.recordSections}>
              <section className={styles.recordSection}>
                <div className={styles.sectionHeading}><ChecksIcon size={15} aria-hidden="true" /><h3>待办事项</h3>{pendingTodoIds.size > 0 && <span role="status">正在同步待办…</span>}</div>
                <DailyTodoList
                  todos={selectedEntry.todos}
                  onToggle={toggleTodo}
                  pendingIds={pendingTodoIds}
                  onDelete={(todo) => requestDelete(todo, '待办', todo.title)}
                />
              </section>

              <section className={styles.recordSection}>
                <div className={styles.sectionHeading}><ClockIcon size={15} aria-hidden="true" /><h3>日程</h3></div>
                {selectedEntry.schedules.length ? <div className={styles.scheduleList}>
                  {selectedEntry.schedules.map((schedule) => <div key={schedule.id} className={styles.scheduleItem}>
                    <span className={`${styles.scheduleDot} ${styles[`schedule${schedule.color[0].toUpperCase()}${schedule.color.slice(1)}`]}`} />
                    <span className={styles.scheduleTime}>
                      {schedule.startTime || schedule.endTime
                        ? <>{schedule.startTime ? `开始 ${schedule.startTime}` : '开始待定'}<br />{schedule.endTime ? `结束 ${schedule.endTime}` : '结束待定'}</>
                        : '时间待定'}
                    </span>
                    <span className={styles.scheduleBody}><strong>{schedule.title}</strong>{schedule.location && <small><MapPinIcon size={11} />{schedule.location}</small>}</span>
                    <DailyRecordDeleteButton label={`日程“${schedule.title}”`} onDelete={() => requestDelete(schedule, '日程', schedule.title)} />
                  </div>)}
                </div> : <p className={styles.emptyText}>今天没有日程安排。</p>}
              </section>

              <section className={`${styles.recordSection} ${styles.cashflowSection}`}>
                <div className={styles.sectionHeading}><ReceiptIcon size={15} aria-hidden="true" /><h3>收支明细</h3>
                  <span className={styles.cashflowTotals}>
                    <b className={styles.expenseText}>支出 ¥{totalExpense(selectedEntry).toFixed(2)}</b>
                    <b className={styles.incomeText}>收入 ¥{totalIncome(selectedEntry).toFixed(2)}</b>
                  </span>
                </div>
                {selectedCashflowCount ? (
                  <DailyCashflowList
                    expenses={selectedEntry.expenses}
                    incomes={selectedEntry.incomes}
                    maxItems={3}
                    onViewAll={() => setIsCashflowModalOpen(true)}
                    onDeleteExpense={(record) => requestDelete(record, '花销', record.category)}
                  />
                ) : <p className={styles.emptyText}>没有收支记录。</p>}
              </section>

              {(selectedJournals.length > 0 || selectedEntry.photos.length > 0) && <section className={styles.recordSection}>
                <div className={styles.sectionHeading}>
                  <NotePencilIcon size={15} aria-hidden="true" />
                  <h3>手记与图片</h3>
                </div>
                {selectedJournals.length > 0 && <div className={styles.journalList}>{selectedJournals.map((journal) => <div className={styles.journal} key={journal.id ?? `${journal.updatedAt}-${journal.title}`}>
                  <div className={styles.journalHeading}>{journal.title && <strong>{journal.title}</strong>}
                    {journal.source === 'record' ? <span className={styles.syncedLabel}>来自资料</span> : <div className={styles.sectionActions}>
                      <button type="button" className={styles.journalEditButton} onClick={() => { setJournalEditorId(journal.id ?? null); setJournalEditorDate(selected); }}>编辑</button>
                      <DailyRecordDeleteButton label="手记" onDelete={() => requestDelete(journal, '手记', journal.title || '无标题手记')} />
                    </div>}
                  </div>
                  <p>{journal.excerpt}</p>
                  {journal.source !== 'record' && journal.mood && <span>{journal.mood}</span>}
                </div>)}</div>}
                {selectedEntry.photos.length > 0 && <div className={styles.photoGrid}>
                  {selectedEntry.photos.map((photo) => <img key={photo.id} src={photo.url} alt={photo.alt} />)}
                  <span className={styles.photoCount}><ImageIcon size={13} />{selectedEntry.photos.length} 张</span>
                </div>}
              </section>}

              {Boolean(selectedEntry.otherRecords?.some(record => record.type !== '学习')) && <section className={styles.recordSection}>
                <div className={styles.sectionHeading}><NotePencilIcon size={15} aria-hidden="true" /><h3>其他记录</h3></div>
                <div className={styles.scheduleList}>
                  {selectedEntry.otherRecords?.filter(record => record.type !== '学习').map((record) => <div key={record.id} className={styles.scheduleItem}>
                    <span className={`${styles.scheduleDot} ${styles.scheduleViolet}`} />
                    <span className={styles.scheduleTime}>{record.type}</span>
                    <span className={styles.scheduleBody}><strong>{record.title}</strong></span>
                  </div>)}
                </div>
              </section>}
            </div> : <div className={styles.emptyDay}>
              <CalendarDotsIcon size={20} aria-hidden="true" />
              <strong>暂无待办、日程或收支记录</strong>
              <p>学习、资料和资产变动在下方独立汇总。</p>
            </div>}
            <CalendarDayDetails key={selectedKey} date={selected} revision={overviewRetry} />
          </section>
          </div>
        </div>
      </div>
      <CalendarStickyNotes
        focusDate={selected}
        revision={overviewRetry}
        onChanged={refreshSelectedAndMonth}
      />
      </div>
      <LoadingOverlay active={isPreparingFinance} label="正在读取财务账户与分类…" />
      <TransactionModal
        open={isFinanceTransactionModalOpen}
        accounts={financeAccounts}
        categories={financeCategories}
        defaultDate={selectedKey}
        onClose={() => setIsFinanceTransactionModalOpen(false)}
        onSubmit={saveFinanceTransaction}
      />
      <Modal
        open={isCashflowModalOpen}
        title="当日收支明细"
        onClose={() => setIsCashflowModalOpen(false)}
        width={560}
        centered
      >
        <div className={styles.cashflowDialogSummary}>
          <span>{selectedLabel}</span>
          <strong>共 {selectedCashflowCount} 条</strong>
        </div>
        <div className={styles.cashflowDialogList}>
          <DailyCashflowList
            expenses={selectedEntry.expenses}
            incomes={selectedEntry.incomes}
            onDeleteExpense={(record) => requestDelete(record, '花销', record.category)}
          />
        </div>
      </Modal>
      <Modal
        open={deleteTarget !== null}
        busy={isDeleting}
        busyLabel="正在删除日历记录…"
        title={deleteTarget?.recurrence && deleteTarget.recurrence !== 'none'
          ? '删除整个重复待办？'
          : `删除${deleteTarget?.kind ?? '记录'}？`}
        onClose={closeDeleteDialog}
        width={420}
        centered
      >
        <div className={styles.deleteDialogBody}>
          <p className={styles.deleteDescription}>
            “{deleteTarget?.title}”将被永久删除{recurringDeleteImpact(deleteTarget?.recurrence)}，此操作无法撤销。
          </p>
          <div className={styles.deleteDialogActions}>
            <Button type="button" variant="outline" onClick={closeDeleteDialog} disabled={isDeleting} autoFocus>
              取消
            </Button>
            <Button type="button" variant="danger" onClick={() => void confirmDelete()} disabled={isDeleting}>
              {isDeleting ? '正在删除…' : deleteTarget?.recurrence && deleteTarget.recurrence !== 'none'
                ? '删除整个重复待办'
                : `删除${deleteTarget?.kind ?? '记录'}`}
            </Button>
          </div>
        </div>
      </Modal>
    </main>
  );
};

export default CalendarView;
