import { useState } from 'react';
import { lifeReviewApi } from '../../services/lifeReviewApi';
import type { LifeCompanionView, SaveLifeCognitionInput } from '../../types/lifeReview';
import { Button } from '../common/Button';
import { Select } from '../common/Select';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { ReviewFormModal } from './ReviewFormModal';
import styles from './LifeReviewSettings.module.css';

interface LifeCompanionSectionProps {
  companion: LifeCompanionView;
  timezone: string;
  busy: boolean;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
}

const HOURS = Array.from({ length: 24 }, (_, idx) => ({
  value: String(idx),
  label: `${String(idx).padStart(2, '0')}:00`,
}));

/** 系统性用户认知（四维卡片直接展示与修改）与不定时动态主动问候面板。 */
export function LifeCompanionSection({
  companion,
  timezone,
  busy,
  act,
}: LifeCompanionSectionProps) {
  const { cognition, rhythm } = companion;
  const [editOpen, setEditOpen] = useState(false);
  const [draft, setDraft] = useState<SaveLifeCognitionInput>({
    backgroundAndPreferences: cognition.backgroundAndPreferences,
    routineAndBusyHours: cognition.routineAndBusyHours,
    activeFocusAndBlockers: cognition.activeFocusAndBlockers,
    pendingHooksAndCuriosities: cognition.pendingHooksAndCuriosities,
    busyStartHour: cognition.busyStartHour,
    busyEndHour: cognition.busyEndHour,
  });

  function openEditModal() {
    setDraft({
      backgroundAndPreferences: cognition.backgroundAndPreferences,
      routineAndBusyHours: cognition.routineAndBusyHours,
      activeFocusAndBlockers: cognition.activeFocusAndBlockers,
      pendingHooksAndCuriosities: cognition.pendingHooksAndCuriosities,
      busyStartHour: cognition.busyStartHour,
      busyEndHour: cognition.busyEndHour,
    });
    setEditOpen(true);
  }

  const rhythmStatusText = rhythm.busyDeferred
    ? `忙时免打扰顺延中（当前在忙碌时段 ${String(cognition.busyStartHour).padStart(2, '0')}:00—${String(cognition.busyEndHour).padStart(2, '0')}:00 内且上次未回复，已顺延至忙完后再跟进）`
    : rhythm.unansweredCount > 0
      ? `上次主动问候暂未回复（连续 ${rhythm.unansweredCount} 次未回，已自动拉长间隔不催促）`
      : rhythm.nextReachAt
        ? `下次动态问候预计：${new Date(rhythm.nextReachAt).toLocaleString('zh-CN', { timeZone: timezone })}`
        : '等待根据你的作息与近期动态自动安排时机';

  return (
    <>
      <SettingsGroup
        title="不定时主动问候与陪伴"
        description="自动从日常对话、手记、待办、学习与账单中持续认识你；忙时不打扰，闲时像朋友一样在唯一主对话里主动关心与跟进。"
      >
        <SettingsRow
          label={companion.companionEnabled ? '主动陪伴与认知学习已开启' : '主动陪伴与认知学习未开启'}
          description={`绑定唯一主对话：「${companion.primarySessionTitle || '日常主对话'}」 · 模型：${companion.activeModelLabel} · 渠道：${companion.feishuLabel} · ${rhythmStatusText}`}
          control={
            <div className={styles.choices}>
              <Button
                size="sm"
                variant={companion.companionEnabled ? 'outline' : 'primary'}
                disabled={busy}
                onClick={() => void act(
                  () => lifeReviewApi.enableCompanion({
                    enabled: !companion.companionEnabled,
                    feishu: companion.feishuConnected,
                    timezone,
                  }),
                  companion.companionEnabled ? '已暂停不定时主动问候' : '已开启系统性认知学习与不定时主动问候',
                )}
              >
                {companion.companionEnabled ? '暂停主动陪伴' : '一键开启主动陪伴'}
              </Button>
              <Button
                size="sm"
                disabled={busy}
                onClick={() => void act(() => lifeReviewApi.learnCompanion(), '已从近期全量记录更新对你的系统性认知')}
              >
                立即学习更新
              </Button>
              <Button
                size="sm"
                disabled={busy}
                onClick={() => void act(
                  () => lifeReviewApi.reachOutCompanion(),
                  '已生成主动问候并同步至唯一主对话',
                )}
              >
                立即主动问候一次
              </Button>
            </div>
          }
        />
      </SettingsGroup>

      <SettingsGroup
        title="Agent 对你的系统性认知（自动学习 · 可直接修改）"
        description="无需逐条审批提案，后台自动提炼并同步至个人画像；发现不准确之处可随时点击右侧按钮直接修改。"
      >
        <SettingsRow
          label="1. 基本背景与性格喜好"
          description={cognition.backgroundAndPreferences || '等待从日常对话与记录中自动学习…'}
          control={<Button size="sm" disabled={busy} onClick={openEditModal}>修改认知</Button>}
        />
        <SettingsRow
          label="2. 作息规律与平时忙闲时段"
          description={`${cognition.routineAndBusyHours || '工作日白天通常在忙，晚间较常互动'}（忙时免打扰窗口：${String(cognition.busyStartHour).padStart(2, '0')}:00—${String(cognition.busyEndHour).padStart(2, '0')}:00）`}
          control={null}
        />
        <SettingsRow
          label="3. 近期主线大事与卡点"
          description={cognition.activeFocusAndBlockers || '等待从待办、学习与手记中自动提炼近期主线…'}
          control={null}
        />
        <SettingsRow
          label="4. 上次未完话头与好奇点"
          description={cognition.pendingHooksAndCuriosities || '等待从最近聊天中捕捉待跟进话头与好奇提问…'}
          control={null}
        />
      </SettingsGroup>

      <ReviewFormModal
        open={editOpen}
        title="修改 Agent 对你的系统性认知"
        busy={busy}
        onClose={() => setEditOpen(false)}
        footer={
          <>
            <Button size="sm" onClick={() => setEditOpen(false)}>取消</Button>
            <Button
              size="sm"
              variant="primary"
              disabled={busy}
              onClick={() => void act(async () => {
                await lifeReviewApi.saveCognition(draft);
                setEditOpen(false);
              }, '系统性认知已保存并同步至个人画像')}
            >
              保存系统性认知
            </Button>
          </>
        }
      >
        <SettingsRow
          label="基本背景与性格喜好"
          layout="stacked"
          control={
            <textarea
              className={styles.textarea}
              aria-label="基本背景与性格喜好"
              rows={2}
              value={draft.backgroundAndPreferences}
              disabled={busy}
              onChange={e => setDraft(d => ({ ...d, backgroundAndPreferences: e.target.value }))}
            />
          }
        />
        <SettingsRow
          label="作息规律与平时忙闲描述"
          layout="stacked"
          control={
            <textarea
              className={styles.textarea}
              aria-label="作息规律与平时忙闲描述"
              rows={2}
              value={draft.routineAndBusyHours}
              disabled={busy}
              onChange={e => setDraft(d => ({ ...d, routineAndBusyHours: e.target.value }))}
            />
          }
        />
        <SettingsRow
          label="常规忙碌时段（忙时未回复将自动顺延至忙完后跟进）"
          control={
            <div className={styles.choices}>
              <Select
                aria-label="忙碌开始小时"
                value={String(draft.busyStartHour)}
                disabled={busy}
                options={HOURS}
                onChange={e => setDraft(d => ({ ...d, busyStartHour: Number(e.target.value) }))}
              />
              <span>至</span>
              <Select
                aria-label="忙碌结束小时"
                value={String(draft.busyEndHour)}
                disabled={busy}
                options={HOURS}
                onChange={e => setDraft(d => ({ ...d, busyEndHour: Number(e.target.value) }))}
              />
            </div>
          }
        />
        <SettingsRow
          label="近期主线大事与卡点"
          layout="stacked"
          control={
            <textarea
              className={styles.textarea}
              aria-label="近期主线大事与卡点"
              rows={2}
              value={draft.activeFocusAndBlockers}
              disabled={busy}
              onChange={e => setDraft(d => ({ ...d, activeFocusAndBlockers: e.target.value }))}
            />
          }
        />
        <SettingsRow
          label="上次未完话头与好奇点"
          layout="stacked"
          control={
            <textarea
              className={styles.textarea}
              aria-label="上次未完话头与好奇点"
              rows={2}
              value={draft.pendingHooksAndCuriosities}
              disabled={busy}
              onChange={e => setDraft(d => ({ ...d, pendingHooksAndCuriosities: e.target.value }))}
            />
          }
        />
      </ReviewFormModal>
    </>
  );
}
