import { motion } from 'framer-motion';
import { DailyRecordDeleteButton } from './DailyRecordDeleteButton';
import type { StickyTodoItem } from './StickyTodoNote';
import styles from './StickyTodoRow.module.css';

interface StickyTodoRowProps {
  item: StickyTodoItem;
  index: number;
  onToggle: (item: StickyTodoItem) => void;
  onDelete?: (item: StickyTodoItem) => void;
}

/** 便签待办行：完成与删除为独立按钮，共享请求中的禁用状态。 */
export function StickyTodoRow({ item, index, onToggle, onDelete }: StickyTodoRowProps) {
  return (
    <motion.div
      className={styles.row}
      initial={{ opacity: 0, y: -6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.24, delay: index * 0.035 }}
    >
      <motion.button
        className={`${styles.todo} ${item.done ? styles.done : ''}`}
        type="button"
        onClick={() => onToggle(item)}
        disabled={item.pending}
        aria-label={`${item.done ? '取消完成' : '标记完成'}：${item.text}`}
      >
        <motion.span
          className={styles.check}
          animate={item.done ? { scale: [0.88, 1.12, 1] } : { scale: 1 }}
          transition={{ duration: 0.22 }}
        >
          {item.done && <motion.svg viewBox="0 0 20 20" fill="none">
            <motion.path
              d="M5 10.2 8.2 13.25 15 6.7"
              stroke="currentColor"
              strokeWidth="2.2"
              strokeLinecap="round"
              strokeLinejoin="round"
              initial={{ pathLength: 0, opacity: 0 }}
              animate={{ pathLength: 1, opacity: 1 }}
              transition={{ duration: 0.18 }}
            />
          </motion.svg>}
        </motion.span>
        <span className={styles.label}>{item.text}</span>
      </motion.button>
      {onDelete && <DailyRecordDeleteButton
        label={`待办“${item.text}”`}
        onDelete={() => onDelete(item)}
        disabled={item.pending}
      />}
    </motion.div>
  );
}
