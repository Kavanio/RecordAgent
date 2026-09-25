import { useEffect, useState } from 'react';
import type { AutomationPreview, AutomationSpec } from '../../types/automation';
import { previewAutomation } from '../../services/automationApi';
import { Modal } from '../common/Modal';
import { Button } from '../common/Button';
import styles from './TaskPreviewModal.module.css';

/** 预览当前草稿，不保存或发送；关闭后丢弃未完成的响应。 */
export function TaskPreviewModal({ spec, onClose }: { spec: AutomationSpec; onClose: () => void }) {
  const [preview, setPreview] = useState<AutomationPreview | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let stopped = false;
    setLoading(true);
    setError('');
    setPreview(null);
    void previewAutomation(spec)
      .then(value => { if (!stopped) setPreview(value); })
      .catch(e => { if (!stopped) setError(e instanceof Error ? e.message : '预览失败'); })
      .finally(() => { if (!stopped) setLoading(false); });
    return () => { stopped = true; };
  }, [spec, attempt]);

  return <Modal open title={spec.kind.endsWith('_REPORT') ? '报告邮件预览' : '任务预览'} width={800} className={styles.modal} centered onClose={onClose}>
    <div className={styles.body}>
      <p className={styles.hint}>根据当前草稿生成预览，不会保存任务或发送邮件。</p>
      {loading && <p role="status" className={styles.state}>正在生成预览…</p>}
      {error && <div className={styles.state} role="alert"><p>{error}</p><Button variant="outline" onClick={() => setAttempt(value => value + 1)}>重新预览</Button></div>}
      {preview && <>
        <div className={styles.summary}><strong>{preview.title}</strong><p>下次执行：{preview.nextAt ? new Date(preview.nextAt).toLocaleString('zh-CN') : spec.trigger === 'ACTIVITY' ? '等待使用状态触发' : '没有符合条件的未来执行时间'}</p></div>
        {spec.kind.endsWith('_REPORT')
          ? <iframe title="报告邮件内容" sandbox="" srcDoc={preview.html} className={styles.mail} />
          : <pre className={styles.content}>{preview.content}</pre>}
      </>}
      <div className={styles.actions}><Button variant="outline" onClick={onClose}>返回编辑</Button></div>
    </div>
  </Modal>;
}
