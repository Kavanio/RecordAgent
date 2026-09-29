import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Camera, LoaderCircle, RefreshCw } from 'lucide-react';
import { getAccountAvatarUrl, uploadAccountAvatar, type AccountStatus } from '../../services/api';
import { fetchTokenUsageOverview } from '../../services/tokenUsageService';
import type { DailyTokenUsage, TokenUsageOverview } from '../../types/tokenUsage';
import { formatTokenCount } from '../chat/tokenUsageFormat';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import { SettingsPageLayout } from './SettingsPageLayout';
import styles from './ProfileSettingsPage.module.css';

interface ProfileSettingsPageProps {
  accountStatus: AccountStatus | null;
  onAccountStatusChange?: (status: AccountStatus) => void;
}

interface ActivityDay {
  date: string;
  tokens: number;
  level: number;
  inRange: boolean;
}

interface ActivityWeek {
  key: string;
  monthLabel: string | null;
  days: ActivityDay[];
}

/** 将本地账户资料与真实 Token 统计汇总到同一资料页。 */
export function ProfileSettingsPage({ accountStatus, onAccountStatusChange }: ProfileSettingsPageProps) {
  const { showMessage } = useMessage();
  const avatarInputRef = useRef<HTMLInputElement>(null);
  const [overview, setOverview] = useState<TokenUsageOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [avatarVersion, setAvatarVersion] = useState(accountStatus?.avatarVersion ?? null);
  const [avatarUploading, setAvatarUploading] = useState(false);
  const [avatarFailed, setAvatarFailed] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setOverview(await fetchTokenUsageOverview());
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '读取 Token 用量失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    setAvatarVersion(accountStatus?.avatarVersion ?? null);
    setAvatarFailed(false);
  }, [accountStatus?.avatarVersion]);

  const handleAvatarChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!['image/png', 'image/jpeg'].includes(file.type)) {
      showMessage('error', '请选择 PNG 或 JPEG 格式的头像图片');
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      showMessage('error', '头像图片不能超过 5 MB');
      return;
    }
    setAvatarUploading(true);
    try {
      const status = await uploadAccountAvatar(file);
      setAvatarVersion(status.avatarVersion);
      setAvatarFailed(false);
      onAccountStatusChange?.(status);
      showMessage('success', '头像已更新');
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '头像上传失败，请重试');
    } finally {
      setAvatarUploading(false);
    }
  };

  const activity = useMemo(() => buildActivity(overview?.daily ?? []), [overview]);
  const displayName = accountStatus?.bound ? '本地用户' : '未绑定用户';
  const email = accountStatus?.maskedEmail || '尚未绑定邮箱';
  const avatarText = accountStatus?.maskedEmail?.replace(/[^a-zA-Z0-9\u4e00-\u9fa5]/g, '').slice(0, 1).toUpperCase() || 'B';

  return (
    <SettingsPageLayout
      title="个人资料"
      description="查看本机用户状态与本地 Token 消耗总览。"
      actions={
        <Button variant="ghost" icon={<RefreshCw size={14} />} onClick={() => void load()} disabled={loading}>
          {loading ? '正在刷新' : '刷新统计'}
        </Button>
      }
    >

      <div className={styles.identity}>
        <button
          type="button"
          className={styles.avatarButton}
          onClick={() => avatarInputRef.current?.click()}
          disabled={avatarUploading}
          aria-label={avatarUploading ? '正在上传头像' : avatarVersion ? '更换头像' : '上传头像'}
          title="点击更换头像"
        >
          {avatarVersion && !avatarFailed ? (
            <img
              className={styles.avatarImage}
              src={getAccountAvatarUrl(avatarVersion)}
              alt="当前头像"
              onError={() => setAvatarFailed(true)}
            />
          ) : (
            <span className={styles.avatarFallback} aria-hidden="true">{avatarText}</span>
          )}
          <span className={styles.avatarAction} aria-hidden="true">
            {avatarUploading ? <LoaderCircle size={17} className={styles.avatarSpinner} /> : <Camera size={17} />}
          </span>
        </button>
        <input
          ref={avatarInputRef}
          className={styles.avatarInput}
          type="file"
          accept="image/png,image/jpeg"
          onChange={(event) => void handleAvatarChange(event)}
          tabIndex={-1}
          aria-hidden="true"
        />
        <h2>{displayName}</h2>
        <div className={styles.accountLine}>
          <span>{email}</span>
          <span className={accountStatus?.bound ? styles.verified : styles.unbound}>
            {accountStatus?.bound ? '已验证' : '未绑定'}
          </span>
        </div>
      </div>

      {loading && !overview && <ProfileState message="正在汇总本地 Token 用量…" />}
      {error && !overview && (
        <div className={styles.state} role="alert">
          <p>{error}</p>
          <Button variant="outline" onClick={() => void load()}>重新加载</Button>
        </div>
      )}

      {overview && (
        <div className={styles.usageContent} aria-live="polite">
          {error && <p className={styles.staleNotice}>刷新失败，当前显示上次成功加载的数据。</p>}
          <dl className={styles.metrics}>
            <Metric value={formatTokenCount(overview.totals.totalTokens)} label="累计 Token 数" />
            <Metric value={formatTokenCount(activity.peakTokens)} label="单日峰值 Token 数" />
            <Metric value={`${activity.activeDays} 天`} label="活跃天数" />
            <Metric value={`${activity.currentStreak} 天`} label="当前连续天数" />
            <Metric value={`${activity.longestStreak} 天`} label="最长连续天数" />
          </dl>

          {overview.totals.status !== 'COMPLETE' && overview.totals.modelCallCount > 0 && (
            <p className={styles.completenessNote}>部分模型调用未返回用量，当前合计可能偏低。</p>
          )}

          <section className={styles.activitySection} aria-labelledby="token-activity-title">
            <div className={styles.sectionHeading}>
              <div>
                <h2 id="token-activity-title">Token 活动</h2>
                <p>每个方格代表一天，颜色越深表示当日 Token 用量越高。</p>
              </div>
              <span>近一年</span>
            </div>
            <div className={styles.heatmapContainer}>
              <div className={styles.heatmap} role="img" aria-label="近一年每日 Token 用量热力图">
                {activity.weeks.map((week) => (
                  <div className={styles.week} key={week.key}>
                    {week.days.map((day) => (
                      <span
                        key={day.date}
                        className={`${styles.day} ${styles[`level${day.level}`]} ${day.inRange ? '' : styles.outside}`}
                        title={day.inRange ? `${day.date}：${day.tokens.toLocaleString()} tokens` : undefined}
                      />
                    ))}
                    <span className={styles.month}>{week.monthLabel}</span>
                  </div>
                ))}
              </div>
            </div>
            <div className={styles.legend} aria-hidden="true">
              <span>少</span>
              {[0, 1, 2, 3, 4].map((level) => <i key={level} className={`${styles.day} ${styles[`level${level}`]}`} />)}
              <span>多</span>
            </div>
          </section>

          <section className={styles.details} aria-labelledby="usage-details-title">
            <div>
              <h2 id="usage-details-title">用量构成</h2>
              <p>统计来自本机聊天记录，不会上传个人资料。</p>
            </div>
            <dl>
              <div><dt>输入 Token</dt><dd>{formatTokenCount(overview.totals.inputTokens)}</dd></div>
              <div><dt>输出 Token</dt><dd>{formatTokenCount(overview.totals.outputTokens)}</dd></div>
              <div><dt>模型调用</dt><dd>{overview.totals.modelCallCount.toLocaleString()} 次</dd></div>
              <div><dt>聊天轮次</dt><dd>{overview.totals.turnCount.toLocaleString()} 轮</dd></div>
            </dl>
          </section>
        </div>
      )}
    </SettingsPageLayout>
  );
}

function Metric({ value, label }: { value: string; label: string }) {
  return <div><dt>{label}</dt><dd>{value}</dd></div>;
}

function ProfileState({ message }: { message: string }) {
  return <div className={styles.state}><p>{message}</p></div>;
}

function buildActivity(daily: DailyTokenUsage[]) {
  const values = new Map(daily.map((item) => [item.date, item.totalTokens]));
  const activeDates = new Set(daily.filter((item) => item.totalTokens > 0).map((item) => item.date));
  const peakTokens = Math.max(0, ...daily.map((item) => item.totalTokens));
  const today = startOfLocalDay(new Date());
  const rangeStart = addDays(today, -364);
  const heatPeak = Math.max(0, ...daily
    .filter((item) => item.date >= formatLocalDate(rangeStart) && item.date <= formatLocalDate(today))
    .map((item) => item.totalTokens));
  const gridStart = addDays(rangeStart, -(mondayIndex(rangeStart)));
  const gridEnd = addDays(today, 6 - mondayIndex(today));
  const weeks: ActivityWeek[] = [];
  let previousMonth = -1;

  for (let cursor = gridStart; cursor <= gridEnd; cursor = addDays(cursor, 7)) {
    const days = Array.from({ length: 7 }, (_, offset) => {
      const date = addDays(cursor, offset);
      const dateKey = formatLocalDate(date);
      const tokens = values.get(dateKey) ?? 0;
      return {
        date: dateKey,
        tokens,
        level: heatLevel(tokens, heatPeak),
        inRange: date >= rangeStart && date <= today,
      };
    });
    const labelDate = days.find((day) => new Date(`${day.date}T00:00:00`).getDate() <= 7);
    const labelMonth = labelDate ? new Date(`${labelDate.date}T00:00:00`).getMonth() : -1;
    const monthLabel = labelDate && labelMonth !== previousMonth ? `${labelMonth + 1}月` : null;
    if (monthLabel) previousMonth = labelMonth;
    weeks.push({ key: formatLocalDate(cursor), monthLabel, days });
  }

  const streaks = calculateStreaks(activeDates, today);
  return { weeks, peakTokens, activeDays: activeDates.size, ...streaks };
}

function calculateStreaks(activeDates: Set<string>, today: Date) {
  const sorted = [...activeDates].sort();
  let longestStreak = 0;
  let running = 0;
  let previous: Date | null = null;
  for (const dateKey of sorted) {
    const date = new Date(`${dateKey}T00:00:00`);
    running = previous && differenceInDays(date, previous) === 1 ? running + 1 : 1;
    longestStreak = Math.max(longestStreak, running);
    previous = date;
  }

  let currentStreak = 0;
  let cursor = today;
  if (!activeDates.has(formatLocalDate(cursor))) cursor = addDays(cursor, -1);
  while (activeDates.has(formatLocalDate(cursor))) {
    currentStreak += 1;
    cursor = addDays(cursor, -1);
  }
  return { currentStreak, longestStreak };
}

function heatLevel(tokens: number, peak: number): number {
  if (tokens <= 0 || peak <= 0) return 0;
  return Math.min(4, Math.max(1, Math.ceil((tokens / peak) * 4)));
}

function mondayIndex(date: Date): number {
  return (date.getDay() + 6) % 7;
}

function startOfLocalDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function addDays(date: Date, days: number): Date {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

function differenceInDays(later: Date, earlier: Date): number {
  const laterDay = Date.UTC(later.getFullYear(), later.getMonth(), later.getDate());
  const earlierDay = Date.UTC(earlier.getFullYear(), earlier.getMonth(), earlier.getDate());
  return Math.round((laterDay - earlierDay) / 86_400_000);
}

function formatLocalDate(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
