import { Bell, Sparkles } from 'lucide-react';
import {
  BooksIcon,
  CalendarDotsIcon,
  FolderOpenIcon,
  StudentIcon,
  WalletIcon,
} from '@phosphor-icons/react';

export type SidebarFeatureId =
  | 'chat'
  | 'calendar'
  | 'finance'
  | 'record'
  | 'materials'
  | 'study'
  | 'task'
  | 'status';

export const SIDEBAR_FEATURE_ITEMS = [
  { id: 'calendar', label: '日历', Icon: CalendarDotsIcon },
  { id: 'finance', label: '财务', Icon: WalletIcon },
  { id: 'record', label: '资料', Icon: BooksIcon },
  { id: 'materials', label: '档案', Icon: FolderOpenIcon },
  { id: 'study', label: '记录', Icon: StudentIcon },
  { id: 'task', label: '任务', Icon: Bell },
  { id: 'status', label: '状态', Icon: Sparkles },
] as const;
