import { useState } from 'react';
import { Bell, ChevronDown, ChevronRight, History } from 'lucide-react';
import { lifeReviewApi } from '../../../services/lifeReviewApi';
import type { LifeCandidate, LifeContent, LifeNudge, LifeSettings } from '../../../types/lifeReview';
import {
  buildOutreachTimeline, dayTitle, FEEDBACK_LABELS, formatDateTime, groupReviewDays,
  OUTREACH_FILTERS, REVIEW_STATE_LABELS, reviewExcerpt, type OutreachCategory, type ReviewDay,
} from '../../../features/review/statusPresentation';
import { Button } from '../../common/Button';
import { ReviewEmptyState } from '../ReviewEmptyState';
import { ReviewDayDrawer } from './ReviewDayDrawer';
import { StatusCard } from './StatusCard';
import styles from './StatusTimelineView.module.css';

const INITIAL_VISIBLE_DAYS = 5;
const INITIAL_VISIBLE_OUTREACH = 5;

interface StatusTimelineViewProps {
  settings: LifeSettings;
  history: LifeContent[];
  candidates: LifeCandidate[];
  nudges: LifeNudge[];
  archives: string[];
  busy: boolean;
  disabled: boolean;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
}

/** 「足迹」分段：默认折叠长文本与早先记录，保留最近复盘线索与按需展开的联系详情。 */
export function StatusTimelineView({
  settings: s, history, candidates, nudges, archives, busy, disabled, act,
}: StatusTimelineViewProps) {
  const [selectedDay, setSelectedDay] = useState<string | null>(null);
  const [filter, setFilter] = useState<OutreachCategory | 'all'>('all');
  const [daysCollapsed, setDaysCollapsed] = useState(false);
  const [showAllDays, setShowAllDays] = useState(false);
  const [outreachCollapsed, setOutreachCollapsed] = useState(false);
  const [showAllOutreach, setShowAllOutreach] = useState(false);
  const [expandedKeys, setExpandedKeys] = useState<Record<string, boolean>>({});

  const days = groupReviewDays(history);
  const visibleDays = showAllDays ? days : days.slice(0, INITIAL_VISIBLE_DAYS);
  const activeReviewDay: ReviewDay | null = selectedDay ? days.find(d => d.day === selectedDay) ?? null : null;
  const timeline = buildOutreachTimeline(nudges, candidates);
  const filteredOutreach = filter === 'all' ? timeline : timeline.filter(item => item.category === filter);
  const visibleOutreach = showAllOutreach ? filteredOutreach : filteredOutreach.slice(0, INITIAL_VISIBLE_OUTREACH);

  const enabledRules = [
    s.idleCheckinDays ? `连续 ${s.idleCheckinDays} 天无互动问候` : '',
    s.weatherAlertEnabled ? '天气变化提醒' : '',
    s.expenseAlertEnabled ? '支出变化提醒' : '',
    s.serverAlertEnabled ? `服务器探针提醒（${s.serverProbeName || '应用服务器'}）` : '',
    s.mailAlertEnabled ? `重要邮件提醒（${s.imapHost || 'IMAP'}）` : '',
  ].filter(Boolean);

  const toggleEntry = (key: string) => {
    setExpandedKeys(prev => ({ ...prev, [key]: !prev[key] }));
  };

  return <div className={styles.layout} role="tabpanel" id="status-panel-timeline" aria-labelledby="status-tab-timeline">
    <StatusCard
      title="复盘时间线"
      icon={<History size={15} />}
      variant="open"
      description={days.length > 0 ? `${days.length} 天 · ${history.length} 个版本` : '复盘按日留档在这里'}
      actions={days.length > 0 ? (
        <button type="button" className={styles.sectionToggle} aria-expanded={!daysCollapsed}
          aria-label={daysCollapsed ? '展开复盘时间线' : '收起复盘时间线'}
          onClick={() => setDaysCollapsed(v => !v)}>
          {daysCollapsed ? '展开' : '收起'}
        </button>
      ) : undefined}
    >
      {daysCollapsed ? null : days.length === 0 ? (
        <ReviewEmptyState title="还没有生活复盘" description="在「今天」准备首份复盘后，每天的回顾和历史版本会保留在这里。" />
      ) : (
        <>
          <ul className={styles.dayList}>
            {visibleDays.map((day, index) => <li key={day.day} className={styles.dayNode} data-state={day.latest.state}>
              <button type="button" className={styles.dayItem} onClick={() => setSelectedDay(day.day)}>
                <div className={styles.dayTop}>
                  <strong>{dayTitle(day.day)}</strong>
                  <div className={styles.dayBadges}>
                    <span className={styles.tag}>v{day.latest.version}</span>
                    {day.versions.length > 1 && <span className={styles.tag}>{day.versions.length} 个版本</span>}
                    <span className={styles.state} data-state={day.latest.state}>{REVIEW_STATE_LABELS[day.latest.state] || day.latest.state}</span>
                    <ChevronRight size={14} aria-hidden="true" />
                  </div>
                </div>
                {index === 0 && <p className={styles.excerpt}>{reviewExcerpt(day.latest)}</p>}
              </button>
            </li>)}
          </ul>
          {days.length > INITIAL_VISIBLE_DAYS && (
            <button type="button" className={styles.moreBtn} aria-expanded={showAllDays} onClick={() => setShowAllDays(v => !v)}>
              {showAllDays ? '仅看最近 5 天' : `展开更早 ${days.length - INITIAL_VISIBLE_DAYS} 天`}
            </button>
          )}
        </>
      )}
      {!daysCollapsed && archives.length > 0 && (
        <div className={styles.archives}>
          {archives.map((a, i) => <details key={i}>
            <summary>重置前的只读外发副本 {i + 1}</summary>
            <p>{a}</p>
          </details>)}
        </div>
      )}
    </StatusCard>

    <StatusCard
      title="主动联系时间线"
      icon={<Bell size={15} />}
      variant="open"
      className={styles.outreachPane}
      description={enabledRules.length > 0 ? <span title={enabledRules.join('、')}>已启用 {enabledRules.length} 项提醒 · 未回复不催促</span> : '暂未开启提醒规则'}
      actions={timeline.length > 0 ? (
        <button type="button" className={styles.sectionToggle} aria-expanded={!outreachCollapsed}
          aria-label={outreachCollapsed ? '展开主动联系时间线' : '收起主动联系时间线'}
          onClick={() => setOutreachCollapsed(v => !v)}>
          {outreachCollapsed ? '展开' : '收起'}
        </button>
      ) : undefined}
    >
      {outreachCollapsed ? null : (
        <>
          <div className={styles.filters} role="group" aria-label="筛选主动联系记录">
            {OUTREACH_FILTERS.map(item => {
              const count = item.value === 'all' ? timeline.length : timeline.filter(entry => entry.category === item.value).length;
              return <button key={item.value} type="button" aria-pressed={filter === item.value}
                onClick={() => { setFilter(item.value); setShowAllOutreach(false); }}>
                {item.label} {count > 0 ? `· ${count}` : ''}
              </button>;
            })}
          </div>

          {filteredOutreach.length === 0 ? (
            <ReviewEmptyState
              title={timeline.length === 0 ? '暂无主动联系或候选记录' : '当前筛选下没有记录'}
              description="触发过问候、提醒或复盘候选后，时间、状态与你的反馈会集中显示在这里。"
            />
          ) : (
            <>
              <ul className={styles.outreachList}>
                {visibleOutreach.map(entry => {
                  const expanded = Boolean(expandedKeys[entry.key]);
                  const summaryLabel = entry.feedback && entry.state !== 'PENDING'
                    ? (FEEDBACK_LABELS[entry.feedback] || entry.feedback)
                    : entry.stateLabel;
                  return <li key={entry.key} className={styles.outreachItem}>
                    <button type="button" className={styles.outreachToggle} aria-expanded={expanded} onClick={() => toggleEntry(entry.key)}>
                      <span className={styles.outreachSummary}>
                        <span className={styles.kindDot} data-category={entry.category} aria-hidden="true" />
                        <strong>{entry.title}</strong>
                      </span>
                      <span className={styles.outreachMeta}>
                        <span>{formatDateTime(entry.at, s.timezone)}</span>
                        <span className={styles.tag}>{summaryLabel}</span>
                        <ChevronDown size={14} className={styles.chevron} data-open={expanded ? 'true' : 'false'} aria-hidden="true" />
                      </span>
                    </button>
                    {expanded && (
                      <div className={styles.outreachDetail}>
                        <p className={styles.outreachText}>{entry.text}</p>
                        <div className={styles.outreachFoot}>
                          {entry.feedback && entry.state !== 'PENDING' ? (
                            <span className={styles.feedbackDone}>已反馈：{FEEDBACK_LABELS[entry.feedback] || entry.feedback}</span>
                          ) : (
                            <div className={styles.outreachActions}>
                              {entry.source === 'nudge' ? (
                                (['HELPFUL', 'NOT_NEEDED'] as const).map(decision => (
                                  <Button key={decision} size="sm" variant="ghost" disabled={busy}
                                    onClick={() => void act(() => lifeReviewApi.nudgeFeedback(entry.id, decision), '问候反馈已记录')}>
                                    {decision === 'HELPFUL' ? '有帮助' : '暂不需要'}
                                  </Button>
                                ))
                              ) : (
                                (entry.state === 'PENDING' ? ['CANCEL'] : ['HELPFUL', 'NOT_RELEVANT']).map(decision => (
                                  <Button key={decision} size="sm" variant="ghost" disabled={busy}
                                    onClick={() => void act(() => lifeReviewApi.feedback(entry.id, decision), '反馈已记录')}>
                                    {decision === 'CANCEL' ? '取消候选' : decision === 'HELPFUL' ? '有帮助' : '不相关'}
                                  </Button>
                                ))
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                    )}
                  </li>;
                })}
              </ul>
              {filteredOutreach.length > INITIAL_VISIBLE_OUTREACH && (
                <button type="button" className={styles.moreBtn} aria-expanded={showAllOutreach} onClick={() => setShowAllOutreach(v => !v)}>
                  {showAllOutreach ? '仅看最近 5 条' : `展开其余 ${filteredOutreach.length - INITIAL_VISIBLE_OUTREACH} 条`}
                </button>
              )}
            </>
          )}
        </>
      )}
    </StatusCard>

    <ReviewDayDrawer reviewDay={activeReviewDay} timezone={s.timezone} busy={busy} authorized={!disabled} feishu={s.feishu}
      onClose={() => setSelectedDay(null)} act={act} />
  </div>;
}
