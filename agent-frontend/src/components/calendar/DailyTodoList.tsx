import { motion } from 'framer-motion';
import { CheckIcon } from '@phosphor-icons/react';
import type { CalendarTodo } from '../../types/calendar';
import { DailyRecordDeleteButton } from './DailyRecordDeleteButton';
import styles from './DailyTodoList.module.css';

interface DailyTodoListProps {
  todos: CalendarTodo[];
  onToggle: (todoId: string) => void;
  onDelete?: (todo: CalendarTodo) => void;
  /** 概览卡片使用紧凑密度，日历页保持完整行高。 */
  compact?: boolean;
  /** 保存中的条目禁止重复切换。 */
  pendingIds?: ReadonlySet<string>;
}

const priorityLabels = {
  high: '重要',
  medium: '计划',
  low: '生活',
} as const;

const weekdayLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'] as const;

const recurrenceLabel = (todo: CalendarTodo): string => {
  if (todo.recurrence === 'daily') return '每天';
  if (todo.recurrence === 'weekly') {
    return todo.recurrenceWeekday ? `每${weekdayLabels[todo.recurrenceWeekday - 1]}` : '每周';
  }
  if (todo.recurrence === 'monthly') {
    if (todo.recurrenceMonthDay) {
      return todo.recurrenceMonthDay === 31 ? '每月最后一天' : `每月${todo.recurrenceMonthDay}日`;
    }
    return '每月';
  }
  return '今天';
};

/** 带手绘划线反馈的当日待办清单。 */
export const DailyTodoList: React.FC<DailyTodoListProps> = ({ todos, onToggle, onDelete, compact = false, pendingIds }) => {
  if (todos.length === 0) {
    return <p className={styles.empty}>这一天没有待办，留给自己一点空白。</p>;
  }

  return (
    <div className={`${styles.list} ${compact ? styles.compact : ''}`}>
      {todos.map((todo) => (
        <div key={todo.id} className={styles.item}>
          <label className={styles.toggle}>
            <input
              className={styles.checkboxInput}
              type="checkbox"
              checked={todo.completed}
              disabled={pendingIds?.has(todo.id)}
              onChange={() => onToggle(todo.id)}
              aria-label={`${todo.completed ? '取消完成' : '标记完成'}：${todo.title}`}
            />
            <span className={styles.checkbox} aria-hidden="true">
              {todo.completed && <CheckIcon size={13} weight="bold" />}
            </span>
          </label>
          <span className={styles.todoContent}>
            <div className={styles.todoHeaderRow}>
              <span className={styles.todoLine}>
                <span className={todo.completed ? styles.todoTitleDone : styles.todoTitle}>{todo.title}</span>
                <motion.svg
                  viewBox="0 0 340 32"
                  preserveAspectRatio="none"
                  className={styles.strike}
                  aria-hidden="true"
                >
                  <motion.path
                    d="M 8 16.5 C 49 7, 89 12, 128 16 C 169 21, 215 8, 258 14 C 288 18, 314 11, 334 15"
                    fill="none"
                    stroke="currentColor"
                    strokeLinecap="round"
                    strokeWidth="2"
                    initial={false}
                    animate={{ pathLength: todo.completed ? 1 : 0, opacity: todo.completed ? 1 : 0 }}
                    transition={{ pathLength: { duration: 0.52, ease: 'easeInOut' }, opacity: { duration: 0.01, delay: todo.completed ? 0 : 0.52 } }}
                  />
                </motion.svg>
              </span>
              {compact && (
                todo.completed ? (
                  <span className={`${styles.badgePill} ${styles.badgeDone}`}>已完成</span>
                ) : todo.priority === 'high' ? (
                  <span className={`${styles.badgePill} ${styles.badgePriorityHigh}`}>优先</span>
                ) : (
                  <span className={`${styles.badgePill} ${styles.badgePriorityNormal}`}>待完成</span>
                )
              )}
            </div>
            <div className={styles.meta}>
              {compact ? (
                todo.completed ? (
                  <span>{todo.time ? `${todo.time} 已完成` : '今日已完成'}</span>
                ) : (
                  <span>计划 · {recurrenceLabel(todo)}</span>
                )
              ) : (
                <>
                  <span className={`${styles.priority} ${styles[`priority${todo.priority[0].toUpperCase()}${todo.priority.slice(1)}`]}`}>{priorityLabels[todo.priority]}</span>
                  {todo.recurrence && todo.recurrence !== 'none' && (
                    <span className={styles.recurrence}>{recurrenceLabel(todo)}</span>
                  )}
                  {todo.time && <span>{todo.time}</span>}
                </>
              )}
            </div>
          </span>
          {onDelete && <DailyRecordDeleteButton label={`待办“${todo.title}”`} onDelete={() => onDelete(todo)} />}
        </div>
      ))}
    </div>
  );
};
