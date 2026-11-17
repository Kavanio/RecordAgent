export type MaterialChannel = 'DESKTOP' | 'FEISHU' | (string & {});
export type MaterialSessionKind = 'GENERAL' | 'PROJECT' | (string & {});
export type MaterialCategory = 'IMAGE' | 'DOCUMENT' | 'AUDIO' | 'OTHER' | (string & {});

export interface ChatMaterialItem {
  id: string;
  fileId: string;
  channel: MaterialChannel;
  sessionKind: MaterialSessionKind;
  sessionId: string;
  projectId?: string | null;
  sessionTitle: string;
  turnId: string;
  category: MaterialCategory;
  subType: string;
  originalName: string;
  mediaType: string;
  sizeBytes: number;
  sha256?: string | null;
  archivePath: string;
  createdDate: string;
  createdAt: string;
}

export interface ChatMaterialPage {
  items: ChatMaterialItem[];
  total: number;
}

export interface ChatMaterialSessionBucket {
  sessionId: string;
  sessionTitle: string;
  channel: MaterialChannel;
  sessionKind: MaterialSessionKind;
  count: number;
  latestCreatedAt: string;
}

export interface ChatMaterialSummary {
  totalCount: number;
  totalBytes: number;
  byCategory: Record<string, number>;
  byChannel: Record<string, number>;
  bySessionKind: Record<string, number>;
  bySubType: Record<string, number>;
  sessions: ChatMaterialSessionBucket[];
}

export interface ChatMaterialFilter {
  category?: string;
  subType?: string;
  channel?: string;
  sessionKind?: string;
  sessionId?: string;
  from?: string;
  to?: string;
  query?: string;
  limit?: number;
  offset?: number;
}
