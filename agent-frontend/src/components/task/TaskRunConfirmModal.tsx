import React from 'react';
import { Play, Clock, Monitor, Mail, ShieldCheck, Volume2, Info, AlertTriangle } from 'lucide-react';
import type { AutomationTask } from '../../types/automation';
import { scheduleText } from '../../features/automation/presentation';
import { Modal } from '../common/Modal';
import { Button } from '../common/Button';
import styles from './TaskRunConfirmModal.module.css';

export interface TaskRunConfirmModalProps {
  open: boolean;
  task: AutomationTask | null;
  busy?: boolean;
  onClose: () => void;
  onConfirm: () => void;
}

export const TaskRunConfirmModal: React.FC<TaskRunConfirmModalProps> = ({
  open,
  task,
  busy = false,
  onClose,
  onConfirm,
}) => {
  if (!task) return null;

  const { spec, nextAt } = task;
  const hasChannels = spec.desktop || spec.email;

  const kindLabel =
    spec.kind === 'SEDENTARY'
      ? '久坐提醒'
      : spec.kind === 'DAILY_REPORT'
        ? '每日日报'
        : '定时提醒';

  const reportSources = [
    spec.expense && '财务流水',
    spec.todo && '待办事项',
    spec.study && '学习记录',
  ].filter(Boolean);

  return (
    <Modal
      open={open}
      title="立即执行任务"
      width={480}
      centered
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <div className={styles.container}>
        <div className={styles.taskCard}>
          <div className={styles.taskCardHeader}>
            <div className={styles.taskTitleRow}>
              <span className={styles.kindBadge}>{kindLabel}</span>
              <h4 className={styles.taskTitle} title={spec.title}>
                {spec.title}
              </h4>
            </div>
          </div>

          <div className={styles.scheduleRow}>
            <Clock size={13} className={styles.fieldIcon} aria-hidden="true" />
            <span className={styles.scheduleText}>执行规则：{scheduleText(spec)}</span>
          </div>

          <div className={styles.channelsSection}>
            <span className={styles.sectionLabel}>触发渠道：</span>
            <div className={styles.channelsList}>
              <span
                className={spec.desktop ? styles.channelActive : styles.channelInactive}
                title={spec.desktop ? '已启用桌面通知' : '未开启桌面通知'}
              >
                <Monitor size={12} aria-hidden="true" />
                桌面通知{spec.desktop ? '' : '（未开）'}
              </span>
              <span
                className={spec.email ? styles.channelActive : styles.channelInactive}
                title={spec.email ? '已启用邮件通知' : '未开启邮件通知'}
              >
                <Mail size={12} aria-hidden="true" />
                邮件通知{spec.email ? '' : '（未开）'}
              </span>
              {spec.confirm && (
                <span className={styles.channelActive} title="需要全屏/弹窗确认">
                  <ShieldCheck size={12} aria-hidden="true" />
                  需确认
                </span>
              )}
              {spec.sound && (
                <span className={styles.channelActive} title="播放提示音">
                  <Volume2 size={12} aria-hidden="true" />
                  提示音
                </span>
              )}
            </div>
          </div>

          {spec.kind === 'DAILY_REPORT' ? (
            <div className={styles.contentSnippet}>
              <span className={styles.snippetLabel}>汇总数据：</span>
              <span className={styles.snippetValue}>
                {reportSources.length > 0 ? reportSources.join('、') : '全部数据'}
              </span>
            </div>
          ) : spec.content ? (
            <div className={styles.contentSnippet}>
              <span className={styles.snippetLabel}>通知正文：</span>
              <span className={styles.snippetValue}>{spec.content}</span>
            </div>
          ) : null}
        </div>

        {!hasChannels && (
          <div className={styles.warningBox} role="alert">
            <AlertTriangle size={14} className={styles.warningIcon} aria-hidden="true" />
            <span>当前任务未开启桌面通知或邮件通知，执行后将仅记录运行历史。</span>
          </div>
        )}

        <div className={styles.hintBox}>
          <Info size={14} className={styles.hintIcon} aria-hidden="true" />
          <div className={styles.hintContent}>
            <p>将通过上述已开启的渠道发送真实通知或邮件。</p>
            <p className={styles.hintSub}>
              本操作仅触发一次即时执行，不影响下一次计划执行
              {nextAt ? `（${new Date(nextAt).toLocaleString('zh-CN')}）` : '。'}
            </p>
          </div>
        </div>

        <div className={styles.modalActions}>
          <Button type="button" variant="outline" disabled={busy} onClick={onClose}>
            取消
          </Button>
          <Button
            type="button"
            variant="primary"
            disabled={busy}
            onClick={onConfirm}
            icon={<Play size={13} fill="currentColor" />}
          >
            {busy ? '正在执行…' : '确认执行'}
          </Button>
        </div>

      </div>
    </Modal>
  );
};
