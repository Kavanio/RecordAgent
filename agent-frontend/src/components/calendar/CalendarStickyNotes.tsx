import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createDailyRecord, deleteDailyEvent, fetchRecurringTodos, setDailyTodoCompleted } from '../../services/dailyEvents';
import { getFeaturePreferences, subscribeFeaturePreferences } from '../../services/featurePreferences';
import {
  getCalendarStickyNoteExpansion,
  setCalendarStickyNoteExpanded,
  subscribeCalendarStickyNoteExpansion,
  type CalendarStickyNoteKind,
} from '../../services/calendarStickyNoteState';
import type { RecurringTodoSummary } from '../../types/dailyEvent';
import { useMessage } from '../common/Message';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { StickyTodoNote, type StickyTodoItem } from './StickyTodoNote';
import styles from './CalendarStickyNotes.module.css';

interface CalendarStickyNotesProps {
  focusDate: Date;
  revision: number;
  onChanged: () => Promise<void>;
}

interface BoardSize {
  width: number;
  height: number;
}

function asStickyItems(todos: RecurringTodoSummary[], pendingIds: Set<string>): StickyTodoItem[] {
  return todos.map((todo) => ({
    id: todo.id,
    text: todo.title,
    done: todo.completed,
    pending: pendingIds.has(todo.id),
  }));
}

/** 读取真实周期待办，并在整个日历工作区内提供可拖拽便签。 */
export function CalendarStickyNotes({ focusDate, revision, onChanged }: CalendarStickyNotesProps) {
  const { showMessage } = useMessage();
  const boardRef = useRef<HTMLDivElement>(null);
  const [todos, setTodos] = useState<RecurringTodoSummary[]>([]);
  const [pendingIds, setPendingIds] = useState<Set<string>>(new Set());
  const pendingRef = useRef(new Set<string>());
  const [deleteTarget, setDeleteTarget] = useState<RecurringTodoSummary | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [boardSize, setBoardSize] = useState<BoardSize | null>(null);
  const [calendarPreferences, setCalendarPreferences] = useState(() => getFeaturePreferences().calendar);
  const [expandedNotes, setExpandedNotes] = useState(() => getCalendarStickyNoteExpansion(
    getFeaturePreferences().calendar.stickyNotesDefaultExpanded,
  ));

  useLayoutEffect(() => {
    const board = boardRef.current;
    if (!board) return;
    const measure = () => setBoardSize({ width: board.clientWidth, height: board.clientHeight });
    const observer = new ResizeObserver(measure);
    observer.observe(board);
    measure();
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    let active = true;
    fetchRecurringTodos(focusDate)
      .then((items) => { if (active) setTodos(items); })
      .catch((error: unknown) => {
        if (active) showMessage('error', error instanceof Error ? error.message : '读取周期待办失败');
      });
    return () => { active = false; };
  }, [focusDate, revision, showMessage]);

  useEffect(() => subscribeFeaturePreferences((preferences) => setCalendarPreferences(preferences.calendar)), []);
  useEffect(() => subscribeCalendarStickyNoteExpansion(setExpandedNotes), []);

  const weekly = useMemo(() => todos.filter((todo) => todo.recurrence === 'weekly'), [todos]);
  const monthly = useMemo(() => todos.filter((todo) => todo.recurrence === 'monthly'), [todos]);

  const toggle = async (item: StickyTodoItem) => {
    const todo = todos.find((candidate) => candidate.id === item.id);
    if (!todo || pendingRef.current.has(todo.id) || deleteTarget?.id === todo.id) return;
    pendingRef.current.add(todo.id);
    setPendingIds((current) => new Set(current).add(todo.id));
    try {
      const occurrenceDate = !todo.completed
        ? new Date()
        : new Date(`${todo.occurrenceDate}T00:00:00`);
      const updated = await setDailyTodoCompleted(
        todo.id,
        !todo.completed,
        todo.version,
        occurrenceDate,
      );
      setTodos((current) => current.map((candidate) => candidate.id === todo.id
        ? { ...candidate, completed: !todo.completed, version: updated.version }
        : candidate));
      try {
        await onChanged();
      } catch (error: unknown) {
        showMessage('error', error instanceof Error
          ? `待办已更新，但日历刷新失败：${error.message}`
          : '待办已更新，但日历刷新失败');
      }
    } catch (error: unknown) {
      showMessage('error', error instanceof Error ? error.message : '修改周期待办失败');
    } finally {
      pendingRef.current.delete(todo.id);
      setPendingIds((current) => {
        const next = new Set(current);
        next.delete(todo.id);
        return next;
      });
    }
  };

  const requestDelete = (item: StickyTodoItem) => {
    const todo = todos.find((candidate) => candidate.id === item.id);
    if (todo && !pendingRef.current.has(todo.id) && !deleteTarget) setDeleteTarget(todo);
  };

  const confirmDelete = async () => {
    if (!deleteTarget || pendingRef.current.has(deleteTarget.id)) return;
    const target = deleteTarget;
    pendingRef.current.add(target.id);
    setIsDeleting(true);
    try {
      await deleteDailyEvent(target.id, target.version);
      setTodos((current) => current.filter((todo) => todo.id !== target.id));
      setDeleteTarget(null);
      showMessage('success', '已删除整个重复待办');
      try {
        await onChanged();
      } catch (error: unknown) {
        showMessage('error', error instanceof Error
          ? `待办已删除，但日历刷新失败：${error.message}`
          : '待办已删除，但日历刷新失败');
      }
    } catch (error: unknown) {
      showMessage('error', error instanceof Error ? error.message : '删除周期待办失败');
    } finally {
      pendingRef.current.delete(target.id);
      setIsDeleting(false);
    }
  };

  const handleAddWeekly = async (text: string) => {
    try {
      const weekStart = new Date(focusDate);
      const day = (weekStart.getDay() + 6) % 7;
      weekStart.setDate(weekStart.getDate() - day);
      await createDailyRecord(weekStart, {
        kind: 'todo',
        title: text,
        priority: 'medium',
        recurrence: 'weekly',
        recurrenceWeekday: undefined,
      });
      const updated = await fetchRecurringTodos(focusDate);
      setTodos(updated);
      await onChanged();
      showMessage('success', '已添加本周待办');
    } catch (error: unknown) {
      showMessage('error', error instanceof Error ? error.message : '添加本周待办失败');
      throw error;
    }
  };

  const handleAddMonthly = async (text: string) => {
    try {
      const monthStart = new Date(focusDate.getFullYear(), focusDate.getMonth(), 1);
      await createDailyRecord(monthStart, {
        kind: 'todo',
        title: text,
        priority: 'medium',
        recurrence: 'monthly',
        recurrenceMonthDay: undefined,
      });
      const updated = await fetchRecurringTodos(focusDate);
      setTodos(updated);
      await onChanged();
      showMessage('success', '已添加本月待办');
    } catch (error: unknown) {
      showMessage('error', error instanceof Error ? error.message : '添加本月待办失败');
      throw error;
    }
  };

  const setExpanded = (kind: CalendarStickyNoteKind, expanded: boolean) => {
    setExpandedNotes(setCalendarStickyNoteExpanded(
      kind,
      expanded,
      calendarPreferences.stickyNotesDefaultExpanded,
    ));
  };

  const noteCount = Number(weekly.length > 0) + Number(monthly.length > 0);
  const layout = boardSize ? (() => {
    const compactWidth = 188;
    const compactHeight = 62;
    const expandedWidth = 304;
    const expandedHeight = 382;
    const gap = 10;
    const edge = 24;
    const weeklyExpanded = weekly.length > 0 && expandedNotes.weekly;
    const monthlyExpanded = monthly.length > 0 && expandedNotes.monthly;
    const compactX = Math.max(16, boardSize.width - compactWidth - edge);
    const expandedX = Math.max(16, boardSize.width - expandedWidth - edge);
    const expandedY = Math.max(16, boardSize.height - expandedHeight - edge);

    if (weeklyExpanded && monthlyExpanded) {
      if (boardSize.width >= expandedWidth * 2 + edge * 2) {
        const overlap = 24;
        const firstX = Math.max(16, boardSize.width - (expandedWidth * 2 - overlap) - edge);
        return {
          weekly: { x: firstX, y: expandedY },
          monthly: { x: firstX + expandedWidth - overlap, y: expandedY + 10 },
        };
      }
      const overlap = 28;
      const firstY = Math.max(16, boardSize.height - (expandedHeight * 2 - overlap) - edge);
      return {
        weekly: { x: expandedX, y: firstY },
        monthly: { x: Math.max(16, expandedX - 10), y: firstY + expandedHeight - overlap },
      };
    }

    if (weeklyExpanded || monthlyExpanded) {
      const compactY = Math.max(16, expandedY - compactHeight - gap);
      return {
        weekly: weeklyExpanded ? { x: expandedX, y: expandedY } : { x: compactX, y: compactY },
        monthly: monthlyExpanded ? { x: expandedX, y: expandedY } : { x: compactX, y: compactY },
      };
    }

    const startY = Math.max(16, boardSize.height - noteCount * compactHeight - (noteCount - 1) * gap - edge);
    return {
      weekly: { x: compactX, y: startY },
      monthly: { x: compactX, y: startY + (weekly.length > 0 ? compactHeight + gap : 0) },
    };
  })() : null;

  const interactionPendingIds = new Set([...pendingIds, ...(deleteTarget ? [deleteTarget.id] : [])]);

  return (
    <>
      <section className={styles.board} ref={boardRef} aria-label="待办便签">
        {calendarPreferences.showStickyNotes && layout && weekly.length > 0 && <StickyTodoNote
          title="本周待办"
          subtitle="Week Focus"
          tone="yellow"
          items={asStickyItems(weekly, interactionPendingIds)}
          initialX={layout.weekly.x}
          initialY={layout.weekly.y}
          rotation={-2.4}
          collapsed={!expandedNotes.weekly}
          constraintsRef={boardRef}
          onCollapsedChange={(collapsed) => setExpanded('weekly', !collapsed)}
          onToggle={(item) => { void toggle(item); }}
          onAdd={handleAddWeekly}
          onDelete={requestDelete}
        />}
        {calendarPreferences.showStickyNotes && layout && monthly.length > 0 && <StickyTodoNote
          title="本月待办"
          subtitle="Month Goals"
          tone="green"
          items={asStickyItems(monthly, interactionPendingIds)}
          initialX={layout.monthly.x}
          initialY={layout.monthly.y}
          rotation={2.1}
          collapsed={!expandedNotes.monthly}
          constraintsRef={boardRef}
          onCollapsedChange={(collapsed) => setExpanded('monthly', !collapsed)}
          onToggle={(item) => { void toggle(item); }}
          onAdd={handleAddMonthly}
          onDelete={requestDelete}
        />}
      </section>
      <ConfirmDialog
        open={deleteTarget !== null}
        title="删除整个重复待办？"
        tone="danger"
        description={`“${deleteTarget?.title}”将被永久删除，以后每${deleteTarget?.recurrence === 'monthly' ? '个月' : '周'}都不会再出现，此操作无法撤销。`}
        confirmText="删除整个重复待办"
        busy={isDeleting}
        onClose={() => { if (!isDeleting) setDeleteTarget(null); }}
        onConfirm={confirmDelete}
      />
    </>
  );
}
