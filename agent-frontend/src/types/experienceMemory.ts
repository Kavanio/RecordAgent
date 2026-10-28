export type MemoryChannel = 'DESKTOP' | 'FEISHU';

export interface MemoryTrialConnection {
  version: number;
  configured: boolean;
  endpoint: string;
  processingChain: string;
  secureTransport: boolean;
  credentialConfigured: boolean;
  recipientFingerprint: string;
  message: string;
}
export interface MemoryTrialConnect {
  expectedVersion: number;
  endpoint: string;
  token: string | null;
  clearToken: boolean;
  processingChain: string;
  acceptTrialRisks: boolean;
  allowPlaintext: boolean;
  requestId: string;
}

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

/** 上次已记录的已知目标清理步骤，不是完整擦除或当前远端快照的证明。 */
export interface MemoryCleanupProgress {
  documentDeletionRecorded: boolean;
  factsClearanceRecorded: boolean;
  knownChildTasks: number;
  childDeletionsRecorded: number;
  parentDeletionRecorded: boolean;
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
  cleanupProgress?: MemoryCleanupProgress | null;
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
  cleanupProgress?: MemoryCleanupProgress | null;
}
export interface MemoryDocumentCommand { expectedVersion: number; confirmed: boolean; requestId: string }
