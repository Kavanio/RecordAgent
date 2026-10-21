import { TopBarAction } from '../common/TopBarAction';
import React, { useState } from 'react';
import {
  ArrowLeftIcon,
  CalendarDotsIcon,
  CheckSquareIcon,
  NotePencilIcon,
  PlusIcon,
  WalletIcon,
} from '@phosphor-icons/react';
import type { CalendarRecordDraft, TodoPriority, TodoRecurrence } from '../../types/calendar';
import { Modal } from '../common/Modal';
import { Select } from '../common/Select';
import { Button } from '../common/Button';
import { TextInput } from '../common/TextInput';
import { TimeWheelPicker } from './TimeWheelPicker';
import styles from './CalendarQuickCreate.module.css';

type RecordKind = CalendarRecordDraft['kind'];
type ModalRecordKind = Exclude<RecordKind, 'journal' | 'expense'>;

interface CalendarQuickCreateProps {
  selectedDate: Date;
  onCreate: (draft: CalendarRecordDraft) => void | Promise<void>;
  onWriteJournal: () => void;
  onCreateFinance: () => void;
}

const RECORD_OPTIONS = [
  { kind: 'todo', label: '新建待办', description: '记录要完成的事项', icon: CheckSquareIcon },
  { kind: 'schedule', label: '添加日程', description: '安排时间与地点', icon: CalendarDotsIcon },
  { kind: 'finance', label: '记一笔', description: '在当前页面记录收支', icon: WalletIcon },
  { kind: 'journal', label: '写手记', description: '留下当天的想法', icon: NotePencilIcon },
] satisfies Array<{ kind: RecordKind | 'finance'; label: string; description: string; icon: typeof CheckSquareIcon }>;

const FORM_TITLES: Record<ModalRecordKind, string> = {
  todo: '新建待办',
  schedule: '添加日程',
};

const PRIORITY_OPTIONS = [
  { value: 'high', label: '重要' },
  { value: 'medium', label: '计划' },
  { value: 'low', label: '生活' },
] as const;

const RECURRENCE_OPTIONS = [
  { value: 'none', label: '不重复' },
  { value: 'daily', label: '每天' },
  { value: 'weekly', label: '每周' },
  { value: 'monthly', label: '每月' },
] as const;

const WEEKDAY_OPTIONS = [
  { value: '', label: '不固定星期（仅在便签显示）' },
  ...['周一', '周二', '周三', '周四', '周五', '周六', '周日'].map((label, index) => ({
    value: String(index + 1),
    label,
  })),
];

const MONTH_DAY_OPTIONS = [
  { value: '', label: '不固定日期（仅在便签显示）' },
  { value: '31', label: '每月最后一天（月末）' },
  ...Array.from({ length: 30 }, (_, index) => ({
    value: String(index + 1),
    label: `每月 ${index + 1} 日`,
  })),
];

const valueOf = (formData: FormData, key: string): string => String(formData.get(key) ?? '').trim();
const optionalNumberOf = (formData: FormData, key: string): number | undefined => {
  const value = valueOf(formData, key);
  return value ? Number(value) : undefined;
};

/** 日历顶栏快捷记录入口：在当前选中日期内创建四类日记录。 */
export const CalendarQuickCreate: React.FC<CalendarQuickCreateProps> = ({ selectedDate, onCreate, onWriteJournal, onCreateFinance }) => {
  const [isOpen, setIsOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeKind, setActiveKind] = useState<ModalRecordKind | null>(null);
  const [todoRecurrence, setTodoRecurrence] = useState<TodoRecurrence>('none');
  const selectedDateLabel = `${selectedDate.getMonth() + 1}月${selectedDate.getDate()}日`;

  const closeModal = () => {
    if (saving) return;
    setIsOpen(false);
    setActiveKind(null);
    setTodoRecurrence('none');
    setError(null);
  };

  const submitRecord = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!activeKind || saving) return;

    const formData = new FormData(event.currentTarget);
    let draft: CalendarRecordDraft;
    if (activeKind === 'todo') {
      draft = {
        kind: 'todo',
        title: valueOf(formData, 'title'),
        time: valueOf(formData, 'time') || undefined,
        priority: (valueOf(formData, 'priority') || 'medium') as TodoPriority,
        recurrence: (valueOf(formData, 'recurrence') || 'none') as TodoRecurrence,
        recurrenceWeekday: optionalNumberOf(formData, 'recurrenceWeekday'),
        recurrenceMonthDay: todoRecurrence === 'monthly' ? optionalNumberOf(formData, 'recurrenceMonthDay') : undefined,
      };
    } else if (activeKind === 'schedule') {
      draft = {
        kind: 'schedule',
        title: valueOf(formData, 'title'),
        startTime: valueOf(formData, 'startTime'),
        endTime: valueOf(formData, 'endTime'),
        location: valueOf(formData, 'location') || undefined,
      };
    } else return;

    setSaving(true); setError(null);
    try {
      await onCreate(draft);
      setIsOpen(false); setActiveKind(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '保存失败，请重试。');
    } finally { setSaving(false); }
  };

  return (
    <div className={styles.container}>
      <TopBarAction
        variant="primary"
        aria-expanded={isOpen}
        aria-haspopup="dialog"
        onClick={() => setIsOpen(true)}
      >
        <PlusIcon size={14} weight="bold" />
        新建记录
      </TopBarAction>

      <Modal
        open={isOpen}
        busy={saving}
        busyLabel="正在保存日历记录…"
        title={activeKind ? FORM_TITLES[activeKind] : '新建记录'}
        onClose={closeModal}
        width={640}
        centered
        className={styles.modal}
      >
        {activeKind ? (
          <form className={styles.form} onSubmit={(event) => void submitRecord(event)}>
            <fieldset disabled={saving} className={styles.formFields}>
            {error && <p role="alert">{error}</p>}
            <div className={styles.formHeading}>
              <button type="button" className={styles.backButton} aria-label="返回记录类型" onClick={() => setActiveKind(null)}>
                <ArrowLeftIcon size={15} />
              </button>
              <span>记录到 {selectedDateLabel}</span>
            </div>

            {activeKind === 'todo' && <>
              <label className={styles.field}>
                <span>待办内容</span>
                <TextInput name="title" required autoFocus placeholder="例如：整理项目笔记" />
              </label>
              <div className={styles.fieldRowThree}>
                <div className={styles.field}><span>时间（选填）</span><TimeWheelPicker name="time" ariaLabel="待办时间" /></div>
                <Select name="priority" label="优先级" options={PRIORITY_OPTIONS} defaultValue="medium" fieldSize="md" fullWidth />
                <Select
                  name="recurrence"
                  label="重复"
                  options={RECURRENCE_OPTIONS}
                  value={todoRecurrence}
                  onChange={(event) => setTodoRecurrence(event.target.value as TodoRecurrence)}
                  fieldSize="md"
                  fullWidth
                />
              </div>
              {todoRecurrence === 'weekly' && (
                <Select
                  name="recurrenceWeekday"
                  label="每周星期"
                  options={WEEKDAY_OPTIONS}
                  defaultValue=""
                  description="未指定星期时仅在每周便签显示，勾选后记录到当天日历"
                  fieldSize="md"
                  fullWidth
                  containerClassName={styles.recurrenceRule}
                />
              )}
              {todoRecurrence === 'monthly' && (
                <Select
                  name="recurrenceMonthDay"
                  label="每月日期"
                  options={MONTH_DAY_OPTIONS}
                  defaultValue=""
                  description="未指定日期时仅在每月便签显示，勾选后记录到当天日历"
                  fieldSize="md"
                  fullWidth
                  containerClassName={styles.recurrenceRule}
                />
              )}
            </>}

            {activeKind === 'schedule' && <>
              <label className={styles.field}>
                <span>日程名称</span>
                <TextInput name="title" required autoFocus placeholder="例如：产品评审会" />
              </label>
              <div className={styles.fieldRow}>
                <div className={styles.field}><span>开始（选填）</span><TimeWheelPicker name="startTime" ariaLabel="日程开始时间" /></div>
                <div className={styles.field}><span>结束（选填）</span><TimeWheelPicker name="endTime" ariaLabel="日程结束时间" /></div>
              </div>
              <label className={styles.field}>
                <span>地点</span>
                <TextInput name="location" placeholder="选填" />
              </label>
            </>}

            <div className={styles.formActions}>
              <Button type="button" variant="outline" onClick={closeModal} disabled={saving}>取消</Button>
              <Button type="submit" variant="primary" disabled={saving}>{saving ? '保存中…' : `保存到 ${selectedDateLabel}`}</Button>
            </div>
            </fieldset>
          </form>
        ) : (
          <div className={styles.menu} aria-label="选择记录类型">
            <p className={styles.menuIntro}>选择记录类型，内容将添加到 <strong>{selectedDateLabel}</strong></p>
            <div className={styles.optionGrid}>
              {RECORD_OPTIONS.map(({ kind, label, description, icon: Icon }) => (
                <button
                  type="button"
                  key={kind}
                  onClick={() => {
                    if (kind === 'journal') {
                      closeModal();
                      onWriteJournal();
                      return;
                    }
                    if (kind === 'finance') {
                      closeModal();
                      onCreateFinance();
                      return;
                    }
                    setTodoRecurrence('none');
                    setActiveKind(kind);
                  }}
                >
                  <span className={styles.optionIcon}><Icon size={17} /></span>
                  <span><strong>{label}</strong><small>{description}</small></span>
                </button>
              ))}
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
};

export default CalendarQuickCreate;
