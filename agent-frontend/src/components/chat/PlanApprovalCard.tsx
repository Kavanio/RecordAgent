import { useEffect, useState } from 'react';
import { ClipboardList, Check, X } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { fetchPlan } from '../../services/api';
import { LoadingTree } from '../common/LoadingTree';
import styles from './PermissionRequestCard.module.css';

interface PlanApprovalCardProps {
  sessionId: string;
  isSubmitting: boolean;
  onDecision: (approved: boolean, rememberForSession: boolean) => void;
}

/** 计划审批卡片：拉取任务计划书，由用户批准或拒绝后再执行。 */
export function PlanApprovalCard({
  sessionId,
  isSubmitting,
  onDecision,
}: PlanApprovalCardProps) {
  // null 表示加载中；空字符串表示没有计划书
  const [plan, setPlan] = useState<string | null>(null);

  // 挂载时读取计划书；组件卸载后忽略结果，避免内存泄漏告警
  useEffect(() => {
    let alive = true;
    fetchPlan(sessionId).then((content) => {
      if (alive) setPlan(content ?? '');
    });
    return () => {
      alive = false;
    };
  }, [sessionId]);

  return (
    <section className={styles.card} aria-label="任务计划书审批">
      <div className={styles.heading}>
        <ClipboardList size={18} aria-hidden="true" />
        <div>
          <p className={styles.title}>任务计划书待审批</p>
          <p className={styles.progress}>批准后 Agent 将按计划逐步执行</p>
        </div>
      </div>

      {plan === null ? (
        <div className={styles.loadingState}><LoadingTree size="small" label="正在读取计划书…" /></div>
      ) : plan === '' ? (
        <p className={styles.description}>暂无计划书内容</p>
      ) : (
        <div className={styles.details}>
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{plan}</ReactMarkdown>
        </div>
      )}

      <div className={styles.actions}>
        <button
          type="button"
          className={styles.deny}
          disabled={isSubmitting}
          onClick={() => onDecision(false, false)}
        >
          <X size={15} aria-hidden="true" />
          拒绝
        </button>
        <button
          type="button"
          className={styles.allow}
          disabled={isSubmitting}
          onClick={() => onDecision(true, false)}
        >
          <Check size={15} aria-hidden="true" />
          {isSubmitting ? '正在提交…' : '批准执行'}
        </button>
      </div>
    </section>
  );
}
