export type RecordType = 'quick' | 'learning' | 'weekly_review' | 'reading' | 'journal';

export interface RecordEntry {
  id: string;
  recordDate: string;
  type: RecordType;
  title: string | null;
  contentHtml: string;
  contentText: string;
  tags: string[];
  tabId: string | null;
  source: string;
  sourceReference: string | null;
  pinned: boolean;
  favorite: boolean;
  archived: boolean;
  trashedAt: string | null;
  weekYear: number | null;
  weekNumber: number | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface RecordDaySummary {
  date: string;
  count: number;
  weeklyReviewCompleted: boolean;
  charCount?: number;
}

export interface SaveRecordInput {
  recordDate: string;
  type: RecordType;
  title?: string;
  contentHtml: string;
  contentText: string;
  tags: string[];
  tabId?: string;
}

export interface RecordTab {
  id: string;
  name: string;
  systemKey: string | null;
  sortOrder: number;
}

export interface RecordAttachment {
  id: string;
  recordId: string;
  originalName: string;
  mediaType: string;
  sizeBytes: number;
  createdAt: string;
}

/** Slash Command 引用选择器使用的轻量资料信息。 */
export interface RecordReferenceOption {
  id: string;
  recordDate: string;
  type: RecordType;
  title: string | null;
  summary: string;
  tags: string[];
  updatedAt: string;
}

export interface RecordReferencePage {
  items: RecordReferenceOption[];
  hasMore: boolean;
  nextOffset: number;
}
