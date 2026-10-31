import { lifeReviewApi } from '../../services/lifeReviewApi';
import type {
  LifeCandidate,
  LifeHabit,
  LifeHabitSuggestion,
  LifeNudge,
  LifeSettings,
} from '../../types/lifeReview';
import { Button } from '../common/Button';
import { Select } from '../common/Select';
import { TextInput } from '../common/TextInput';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { ReviewDisclosure } from './ReviewDisclosure';
import { ReviewEmptyState } from './ReviewEmptyState';
import { ReviewFormModal } from './ReviewFormModal';
import styles from './LifeReviewSettings.module.css';

const CANDIDATE_STATES: Record<string, string> = {
  PENDING: '等待合适时机',
  PRESENTED: '已呈现，无需立即回复',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
};

const NUDGE_STATES: Record<string, string> = {
  PENDING: '等待发送',
  SENDING: '正在发送',
  SENT: '已发送飞书问候',
  UNKNOWN: '发送结果未知（不自动重发）',
  FAILED: '发送未完成',
  CANCELLED: '已取消',
};

const NUDGE_FEEDBACK_LABELS: Record<string, string> = {
  HELPFUL: '已反馈：有帮助',
  NOT_NEEDED: '已反馈：暂不需要',
};

interface LifeOutreachSectionProps {
  settings: LifeSettings;
  habit: LifeHabit | null;
  habitSuggestion: LifeHabitSuggestion | null;
  candidates: LifeCandidate[];
  nudges: LifeNudge[];
  busy: boolean;
  disabled: boolean;
  habitModalOpen: boolean;
  hour: number;
  habitSource: string;
  onHourChange: (hour: number) => void;
  onHabitSourceChange: (source: string) => void;
  onOpenHabitModal: (initialHour: number, initialSource: string) => void;
  onCloseHabitModal: () => void;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
}

/** 作息时间弹窗、基于互动时间的作息建议、久未互动问候记录与主动候选管理。 */
export function LifeOutreachSection({
  settings: s,
  habit,
  habitSuggestion,
  candidates,
  nudges,
  busy,
  disabled,
  habitModalOpen,
  hour,
  habitSource,
  onHourChange,
  onHabitSourceChange,
  onOpenHabitModal,
  onCloseHabitModal,
  act,
}: LifeOutreachSectionProps) {
  const suggestedHour = habitSuggestion?.available ? habitSuggestion.suggestedHour : null;

  return (
    <>
      <ReviewFormModal open={habitModalOpen} title="设置每日复盘与发送时间" busy={busy} onClose={onCloseHabitModal}>
        {habitSuggestion && (
          <SettingsRow
            label="近期互动活跃时段参考"
            description={habitSuggestion.note}
            control={
              suggestedHour !== null ? (
                <Button
                  size="sm"
                  disabled={disabled}
                  onClick={() => {
                    onHourChange(suggestedHour);
                    onHabitSourceChange('采用近 14 天互动活跃时段建议');
                  }}
                >
                  填入建议（{suggestedHour}:00）
                </Button>
              ) : undefined
            }
          />
        )}
        <SettingsRow
          label="每日自动生成与发送时间（12:00 — 23:00 整点）"
          description="到达设定整点后自动准备今日复盘；若开启了主动呈现与飞书投递，生成完成后会自动发送。"
          layout="stacked"
          control={
            <div className={styles.stack}>
              <Select
                aria-label="生活复盘作息小时"
                value={String(hour)}
                disabled={disabled}
                options={Array.from({ length: 12 }, (_, idx) => {
                  const h = idx + 12;
                  return { value: String(h), label: `每天 ${h}:00${h === 21 ? '（默认）' : ''}` };
                })}
                onChange={e => onHourChange(Number(e.target.value))}
              />
              <TextInput
                aria-label="生活复盘作息来源"
                value={habitSource}
                maxLength={300}
                disabled={disabled}
                placeholder="备注说明（可选，不填可直接保存）"
                onChange={e => onHabitSourceChange(e.target.value)}
              />
              <div className={styles.choices}>
                <Button
                  size="sm"
                  variant="primary"
                  disabled={disabled}
                  onClick={() => void act(async () => {
                    await lifeReviewApi.saveHabit(habit?.revision || 0, hour, habitSource, true);
                    onCloseHabitModal();
                  }, `已设为每天 ${hour}:00 自动复盘`)}
                >
                  保存时间
                </Button>
                {habit?.active && (
                  <Button
                    size="sm"
                    disabled={disabled}
                    onClick={() => void act(async () => {
                      await lifeReviewApi.saveHabit(habit.revision, 21, '恢复默认 21:00', false);
                      onCloseHabitModal();
                    }, '已恢复默认每天 21:00')}
                  >
                    恢复默认 21:00
                  </Button>
                )}
              </div>
            </div>
          }
        />
      </ReviewFormModal>

      <ReviewDisclosure title="主动联系（可选）" description="不影响阅读复盘。展开后管理联系时间、久未互动问候和发送记录。">
        <SettingsGroup title="作息候选与纠正" description="只在你明确确认后生效，可随时纠正或停用；没有互动不代表没起床或身体异常。">
          <SettingsRow
            label="当前候选"
            description={
              habit
                ? `${habit.active ? '生效中' : '已停用'} · ${habit.availableHour}点后 · ${habit.source} · ${habit.uncertainty} · 有效至${habit.expiresAt}`
                : '尚未形成作息候选，默认每天 21:00 自动准备。'
            }
            control={
              habit && (
                <Button
                  size="sm"
                  disabled={disabled || !habit.active}
                  onClick={() => void act(() => lifeReviewApi.saveHabit(habit.revision, habit.availableHour, habit.source, false), '作息候选已停用')}
                >
                  停用候选
                </Button>
              )
            }
          />
          <SettingsRow
            label="基于互动时间的作息建议"
            description={
              habitSuggestion?.note
              || '仅统计近 14 天普通会话的用户消息时间戳（不读取正文、不调用模型），满足至少 3 个活跃日与 5 次互动后给出常活跃整点建议。'
            }
            control={
              suggestedHour !== null ? (
                <Button
                  size="sm"
                  disabled={disabled || (habit?.active === true && habit.availableHour === suggestedHour)}
                  onClick={() => void act(
                    () => lifeReviewApi.saveHabit(habit?.revision || 0, suggestedHour, '采用近 14 天互动活跃时段建议', true),
                    `已采用建议时间：每天 ${suggestedHour}:00`,
                  )}
                >
                  采用建议（{suggestedHour}:00）
                </Button>
              ) : undefined
            }
          />
          <SettingsRow
            label="调整可呈现时间"
            description="可选择 12:00—23:00 的整点自动准备与发送时间，备注可选。"
            control={
              <Button
                size="sm"
                disabled={disabled}
                onClick={() => onOpenHabitModal(habit?.availableHour || 21, habit?.source || '')}
              >
                编辑作息
              </Button>
            }
          />
        </SettingsGroup>

        <SettingsGroup
          title="久未互动问候记录"
          description={
            s.idleCheckinDays
              ? `当前已开启连续 ${s.idleCheckinDays} 天无互动飞书问候（固定温和问候语，不调用模型，未回复绝不重复催促）。`
              : '当前未开启久未互动问候；可在上方「配置复盘」中选择连续 2/3/5/7 天无互动后问候一次。'
          }
        >
          {nudges.length === 0 && (
            <ReviewEmptyState title="暂无问候记录" description="触发过久未互动问候后，发送时间、状态和你的反馈会记录在这里。" />
          )}
          {nudges.map(n => (
            <SettingsRow
              key={n.id}
              label={n.kind === 'IDLE_CHECKIN' ? '久未互动轻声问候' : n.kind}
              description={`${NUDGE_STATES[n.state] || n.state} · ${new Date(n.sentAt || n.createdAt).toLocaleString('zh-CN', { timeZone: s.timezone })} · 「${n.text}」${n.feedback ? ` · ${NUDGE_FEEDBACK_LABELS[n.feedback] || n.feedback}` : ''}`}
              control={
                <div className={styles.choices}>
                  {(['HELPFUL', 'NOT_NEEDED'] as const).map(decision => (
                    <Button
                      key={decision}
                      size="sm"
                      disabled={busy || n.feedback === decision}
                      onClick={() => void act(() => lifeReviewApi.nudgeFeedback(n.id, decision), '问候反馈已记录')}
                    >
                      {decision === 'HELPFUL' ? '有帮助' : '暂不需要'}
                    </Button>
                  ))}
                </div>
              }
            />
          ))}
        </SettingsGroup>

        <SettingsGroup title="主动记录与反馈" description="等待、过期、取消与已呈现分别记录。未回复不会被判为失败，也不会触发追问。">
          {candidates.length === 0 && (
            <ReviewEmptyState title="暂无主动候选" description="有值得呈现的新信息时，候选和后续反馈会记录在这里。" />
          )}
          {candidates.map(c => (
            <SettingsRow
              key={c.id}
              label={c.topic.startsWith('review-') ? `${c.topic.slice(7)} 生活复盘` : c.topic}
              description={`${CANDIDATE_STATES[c.state] || c.state} · 最早${new Date(c.dueAt).toLocaleString('zh-CN', { timeZone: s.timezone })} · 到期${new Date(c.expiresAt).toLocaleString('zh-CN', { timeZone: s.timezone })}${c.feedback ? ` · ${c.feedback}` : ''}`}
              control={
                <div className={styles.choices}>
                  {(c.state === 'PENDING' ? ['CANCEL'] : ['HELPFUL', 'NOT_RELEVANT']).map(decision => (
                    <Button key={decision} size="sm" disabled={busy} onClick={() => void act(() => lifeReviewApi.feedback(c.id, decision), '反馈已记录')}>
                      {decision === 'CANCEL' ? '取消候选' : decision === 'HELPFUL' ? '有帮助' : '不相关'}
                    </Button>
                  ))}
                </div>
              }
            />
          ))}
        </SettingsGroup>
      </ReviewDisclosure>
    </>
  );
}
