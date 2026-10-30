import type { MemoryCleanupProgress } from '../../types/experienceMemory';

/** 只判断已记录的已知清理步骤，不作为完整擦除、来源复活或解除保护的依据。 */
export function knownCleanupRecorded(progress?: MemoryCleanupProgress | null): boolean {
  if (!progress) return false;
  return progress.documentDeletionRecorded && progress.factsClearanceRecorded
    && progress.parentDeletionRecorded && Number.isInteger(progress.knownChildTasks)
    && progress.knownChildTasks > 0 && progress.knownChildTasks <= 32
    && progress.childDeletionsRecorded === progress.knownChildTasks;
}

/** 列表、详情与 Message 共用同一口径；旧后端无进度字段时保持保守提示。 */
export function memoryDeletionLabel(state: string, progress?: MemoryCleanupProgress | null): string {
  if (state === 'UNKNOWN' && knownCleanupRecorded(progress)) return '已完成已知内容清理';
  if (state === 'UNKNOWN' && progress && (progress.documentDeletionRecorded || progress.factsClearanceRecorded
    || progress.childDeletionsRecorded > 0 || progress.parentDeletionRecorded)) return '部分清理步骤已完成，仍待核实';
  switch (state) {
    case 'PENDING': return '等待核对远端删除';
    case 'WAITING_REMOTE': return '等待在途保存结束';
    case 'SUCCEEDED': return '记忆来源处理已完成';
    case 'VERIFIED': return '远端删除已核对';
    case 'FAILED': return '远端删除核对失败';
    case 'UNKNOWN': return '远端删除结果未知';
    default: return '';
  }
}

/** 文案明确是历史核对记录，不把刷新进度冒充重新访问远端。 */
export function cleanupProgressDescription(progress: MemoryCleanupProgress): string {
  return `上次核对记录：原文${progress.documentDeletionRecorded ? '已删除' : '尚未核实'}；`
    + `直接事实${progress.factsClearanceRecorded ? '已清空' : '尚未核实'}；`
    + `已知子任务 ${progress.childDeletionsRecorded}/${progress.knownChildTasks} 已删除；`
    + `父任务${progress.parentDeletionRecorded ? '已删除' : '尚未核实'}。`;
}
