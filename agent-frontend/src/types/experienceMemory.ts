export type MemoryChannel = 'DESKTOP' | 'FEISHU';
/** 长期模式只表示不按统一天数排除来源，不代表永不失效或远端已删除。 */
export type MemoryRecallMode = 'ALL_AUTHORIZED' | 'RECENT_DAYS';

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

/** 运行保护与用途授权分开：调整节流不会更换 revision 或使已积累来源失效。 */
export interface MemoryRuntimePolicy {
  version: number;
  batchSize: number;
  dailyWriteLimit: number;
  dailyStatusLimit: number;
  dailyRecallLimit: number;
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
  /** V1 授权才使用这三个字段；V2 的日额度来自 runtimePolicy。 */
  retentionDays: number;
  dailyWriteLimit: number;
  dailyQueryLimit: number;
  policyVersion: number;
  recallMode: MemoryRecallMode;
  runtimePolicy: MemoryRuntimePolicy;
}

/** V2 授权不提交每日条数上限；retentionDays 只在“最近N天”模式必填。 */
export interface MemoryAuthorization {
  expectedRevision: number;
  recipientFingerprint: string;
  allowSave: boolean;
  allowRecall: boolean;
  channels: MemoryChannel[];
  retentionDays: number | null;
  policyVersion: number;
  recallMode: MemoryRecallMode;
  continuousProcessing: boolean;
  acknowledgeSourceImpact: boolean;
  confirmed: boolean;
  requestId: string;
}

export interface MemoryUsageToday {
  day: string;
  writeCalls: number;
  legacyCombinedQueries: number;
  statusCalls: number;
  recallCalls: number;
  writeLimit: number;
  statusLimit: number;
  recallLimit: number;
}

/** 只读本地权威状态：不包含正文，也不等于“已形成具体事实”或“远端已完整删除”。 */
export interface MemoryStatus {
  sourceTotal: number;
  sourceActive: number;
  operations: Record<string, number>;
  oldestPendingAt: string | null;
  pauseReasons: string[];
  usage: MemoryUsageToday;
  skippedToday: Record<string, number>;
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
