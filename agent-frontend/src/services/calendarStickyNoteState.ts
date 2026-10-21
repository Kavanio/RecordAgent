import { readPreference, writePreference } from './desktopPreferences';

export type CalendarStickyNoteKind = 'weekly' | 'monthly';

export interface CalendarStickyNoteExpansion {
  weekly: boolean;
  monthly: boolean;
}

const STORAGE_KEY = 'butvan.calendarStickyNotes.expansion';
const CHANGE_EVENT = 'butvan:calendar-sticky-notes-changed';

/** 读取周、月便签的持久化展开状态；首次使用时采用日历设置中的默认值。 */
export function getCalendarStickyNoteExpansion(defaultExpanded: boolean): CalendarStickyNoteExpansion {
  try {
    const saved = JSON.parse(readPreference(STORAGE_KEY) ?? '{}') as Partial<CalendarStickyNoteExpansion>;
    return {
      weekly: typeof saved.weekly === 'boolean' ? saved.weekly : defaultExpanded,
      monthly: typeof saved.monthly === 'boolean' ? saved.monthly : defaultExpanded,
    };
  } catch {
    return { weekly: defaultExpanded, monthly: defaultExpanded };
  }
}

function saveCalendarStickyNoteExpansion(next: CalendarStickyNoteExpansion): CalendarStickyNoteExpansion {
  writePreference(STORAGE_KEY, JSON.stringify(next));
  window.dispatchEvent(new CustomEvent<CalendarStickyNoteExpansion>(CHANGE_EVENT, { detail: next }));
  return next;
}

/** 保存单张便签的展开状态。 */
export function setCalendarStickyNoteExpanded(
  kind: CalendarStickyNoteKind,
  expanded: boolean,
  defaultExpanded: boolean,
): CalendarStickyNoteExpansion {
  return saveCalendarStickyNoteExpansion({
    ...getCalendarStickyNoteExpansion(defaultExpanded),
    [kind]: expanded,
  });
}

/** 修改默认值时，让当前周、月便签立即采用新的默认展开状态。 */
export function resetCalendarStickyNoteExpansion(expanded: boolean): CalendarStickyNoteExpansion {
  return saveCalendarStickyNoteExpansion({ weekly: expanded, monthly: expanded });
}

export function subscribeCalendarStickyNoteExpansion(
  listener: (expansion: CalendarStickyNoteExpansion) => void,
): () => void {
  const onChange = (event: Event) => {
    listener((event as CustomEvent<CalendarStickyNoteExpansion>).detail);
  };
  const onStorage = (event: StorageEvent) => {
    if (event.key === STORAGE_KEY) listener(getCalendarStickyNoteExpansion(false));
  };
  window.addEventListener(CHANGE_EVENT, onChange);
  window.addEventListener('storage', onStorage);
  return () => {
    window.removeEventListener(CHANGE_EVENT, onChange);
    window.removeEventListener('storage', onStorage);
  };
}
