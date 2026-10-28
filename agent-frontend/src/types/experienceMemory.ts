export type MemoryChannel = 'DESKTOP' | 'FEISHU';

export interface MemorySettings {
  version: number;
  autoSave: boolean;
  autoRecall: boolean;
  authorizationRevision: number;
  authorized: boolean;
  remoteAvailable: boolean;
  recipientFingerprint: string;
  allowSave: boolean;
  allowRecall: boolean;
  channels: MemoryChannel[];
  effectiveAt: string | null;
  retentionDays: number;
  dailyWriteLimit: number;
  dailyQueryLimit: number;
}

export interface MemoryAuthorization {
  expectedRevision: number;
  recipientFingerprint: string;
  allowSave: boolean;
  allowRecall: boolean;
  channels: MemoryChannel[];
  retentionDays: number;
  dailyWriteLimit: number;
  dailyQueryLimit: number;
  confirmed: boolean;
  requestId: string;
}

export interface MemoryDocument {
  id: string;
  version: number;
  occurredAt: string;
  channel: MemoryChannel;
  state: 'CAPTURED' | 'REVOKED';
  syncState: string;
  deletionState: string;
  corrected: boolean;
  operationId: string | null;
}

export interface MemoryDocumentPage { items: MemoryDocument[]; nextCursor: string | null }
export interface MemoryDocumentDetail {
  document: MemoryDocument;
  originalStatus: 'AVAILABLE' | 'UNAVAILABLE';
  originalText: string | null;
  correctedText: string | null;
  correctionStatus: 'NONE' | 'LOCAL_ONLY';
}
export interface MemoryOperation {
  id: string;
  sourceId: string;
  kind: 'CORRECT' | 'DELETE';
  version: number;
  state: 'PENDING' | 'WAITING_REMOTE' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN';
  updatedAt: string;
  retryAllowed: boolean;
}
export interface MemoryDocumentCommand { expectedVersion: number; confirmed: boolean; requestId: string }
