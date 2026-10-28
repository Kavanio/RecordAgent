/** 恢复出厂只操作当前实际连接的后端，远端记忆另列状态。 */
export interface AgentResetPreview {
  confirmationToken: string;
  expiresAt: number;
  scopeVersion: string;
  generation: number;
  deletes: string[];
  preserves: string[];
  limitation: string;
}
export interface AgentResetStatus {
  resetId: string | null;
  revision: number;
  generation: number;
  status: 'IDLE' | 'QUIESCING' | 'CLEANING' | 'WAITING_WRITERS' | 'WAITING_RESTART' | 'FAILED' | 'LOCAL_COMPLETE';
  completedSteps: string[];
  message: string;
  retryable: boolean;
  remoteStatus: 'NOT_INTEGRATED' | 'NOT_REQUIRED' | 'PENDING' | 'PROCESSING' | 'FAILED' | 'UNKNOWN' | 'COMPLETE';
}
