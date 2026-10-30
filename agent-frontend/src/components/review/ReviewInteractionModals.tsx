import type { ReviewDetail } from '../../types/review';
import { ReviewFormModal } from './ReviewFormModal';
import { Button } from '../common/Button';
import styles from './ReviewInteractionModals.module.css';
interface Props {
  detail: ReviewDetail | null; feedbackDecision: string | null; correctionOpen: boolean; busy: boolean;
  feedbackText: string; correctionText: string; setFeedbackText: (text: string) => void;
  setCorrectionText: (text: string) => void; closeFeedback: () => void; closeCorrection: () => void;
  closeDetail: () => void; feedback: () => void; correct: () => void;
}
/** 统计复盘详情、反馈与纠正统一使用领域弹窗，页面只负责数据编排。 */
export function ReviewInteractionModals({ detail, feedbackDecision, correctionOpen, busy, feedbackText,
  correctionText, setFeedbackText, setCorrectionText, closeFeedback, closeCorrection, closeDetail, feedback, correct }: Props) {
  return <>
      <ReviewFormModal open={feedbackDecision !== null} title="提交反馈" busy={busy} onClose={() => closeFeedback()}>
        <p className={styles.muted}>决策：{feedbackDecision || ''}</p>
        <textarea className={styles.textarea} aria-label="反馈说明" value={feedbackText} maxLength={500} rows={3} placeholder="可选：补充原因或调整后的做法，不要填写敏感信息。"
          onChange={event => setFeedbackText(event.target.value)} />
        <div className={styles.modalActions}>
          <Button variant="ghost" size="sm" onClick={() => closeFeedback()}>取消</Button>
          <Button variant="primary" size="sm" disabled={busy} onClick={feedback}>保存反馈</Button>
        </div>
      </ReviewFormModal>

      <ReviewFormModal open={correctionOpen} title="纠正或否定观察" busy={busy} onClose={() => closeCorrection()}>
        <p className={styles.muted}>填写说明即为纠正；留空提交则否定了这条观察。被纠正或否定的观察不再作为召回证据。</p>
        <textarea className={styles.textarea} aria-label="观察纠正说明" value={correctionText} maxLength={500} rows={3} placeholder="例如：那段时间在出差，不能说明习惯变化。"
          onChange={event => setCorrectionText(event.target.value)} />
        <div className={styles.modalActions}>
          <Button variant="ghost" size="sm" onClick={() => closeCorrection()}>取消</Button>
          <Button variant="outline" size="sm" disabled={busy} onClick={correct}>提交</Button>
        </div>
      </ReviewFormModal>

      <ReviewFormModal open={Boolean(detail)} title="复盘详情" onClose={() => closeDetail()}>
        {detail && (
          <div className={styles.detail}>
            <p className={styles.muted}>{detail.coverageNote || detail.message || '没有可展示的内容。'}</p>
            {[...detail.observations, ...detail.followUps, ...detail.actions].map(item => (
              <article key={`${item.kind}-${item.topicKey}-${item.text}`} className={styles.card}>
                <div className={styles.cardHead}><strong>{item.text}</strong><span className={styles.badge}>{item.kind === 'ACTION' ? '建议' : item.kind === 'FOLLOW_UP' ? '跟进' : '观察'}</span></div>
                {item.rationale && <p className={styles.muted}>理由：{item.rationale}</p>}
                {item.criterion && <p className={styles.muted}>检查：{item.criterion}</p>}
                {item.uncertainty && <p className={styles.muted}>不确定：{item.uncertainty}</p>}
                <p className={styles.muted}>依据：{item.evidenceIds.join('、') || '无'}</p>
              </article>
            ))}
          </div>
        )}
      </ReviewFormModal>
  </>;
}
