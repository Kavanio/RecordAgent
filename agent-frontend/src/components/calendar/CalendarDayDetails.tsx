import { useEffect, useState, type ReactNode } from 'react';
import { CaretRightIcon } from '@phosphor-icons/react';
import { fetchCalendarDetails } from '../../services/calendarDetails';
import { toCalendarDayEntry } from './dailyEventViewModel';
import type {
  FinanceTransaction,
  FinanceTransactionType,
} from '../../types/finance';
import { formatStudyDuration } from './calendarPresentation';
import { useStudyRealtime } from '../../context/studyRealtimeState';
import { Skeleton } from '../common/Skeleton';
import styles from './CalendarDayDetails.module.css';

const LABELS: Record<FinanceTransactionType, string> = {
  income: '收入',
  expense: '支出',
  yield: '收益',
  transfer_in: '转入',
  transfer_out: '转出',
  adjustment_increase: '校准增加',
  adjustment_decrease: '校准减少',
};

const decreases = new Set<FinanceTransactionType>([
  'expense',
  'transfer_out',
  'adjustment_decrease',
]);

function Section({
  title,
  meta,
  children,
  compact,
}: {
  title: string;
  meta?: string;
  children: ReactNode;
  compact: boolean;
}) {
  return compact ? (
    <details className={styles.section}>
      <summary className={styles.summaryTrigger}>
        <span className={styles.summaryTitle}>
          <CaretRightIcon size={11} className={styles.chevron} aria-hidden="true" />
          <span>{title}</span>
        </span>
        {meta && <span className={styles.summaryMeta}>{meta}</span>}
      </summary>
      <div className={styles.sectionBody}>{children}</div>
    </details>
  ) : (
    <section className={styles.section}>
      <h4>
        <span>{title}</span>
        {meta && <span className={styles.headingMeta}>{meta}</span>}
      </h4>
      {children}
    </section>
  );
}

type RowTone = 'income' | 'expense' | 'neutral';

function Row({
  title,
  meta,
  tone = 'neutral',
}: {
  title: string;
  meta?: string;
  tone?: RowTone;
}) {
  return (
    <div className={`${styles.row} ${styles[tone]}`}>
      <span>{title}</span>
      {meta && <small>{meta}</small>}
    </div>
  );
}

/** 浮层按分类折叠，右侧补充展示资产、学习和资料；共用读取与降级逻辑。 */
export function CalendarDayDetails({
  date,
  compact = false,
  revision = 0,
}: {
  date: Date;
  compact?: boolean;
  revision?: number;
}) {
  const { syncGeneration } = useStudyRealtime();
  const [data, setData] = useState<Awaited<
    ReturnType<typeof fetchCalendarDetails>
  > | null>(null);
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    let active = true;
    setData(null);
    void fetchCalendarDetails(date).then((result) => {
      if (active) setData(result);
    });
    return () => {
      active = false;
    };
  }, [date, retry, syncGeneration, revision]);

  const entry =
    data?.daily.status === 'fulfilled'
      ? toCalendarDayEntry(data.daily.value)
      : null;
  const failed =
    data && Object.values(data).some((result) => result.status === 'rejected');
  const assets = data?.assets.status === 'fulfilled' ? data.assets.value : [];
  const groups = new Map<string, FinanceTransaction[]>();
  for (const item of assets)
    groups.set(item.accountId, [...(groups.get(item.accountId) ?? []), item]);

  const expenseSum = entry?.expenses.reduce((n, item) => n + item.amount, 0) ?? 0;
  const incomeSum = entry?.incomes.reduce((n, item) => n + item.amount, 0) ?? 0;
  const studySeconds = data?.study.status === 'fulfilled' ? data.study.value.totalDurationSeconds : 0;
  const studySessions = data?.sessions.status === 'fulfilled' ? data.sessions.value : [];
  const records = data?.records.status === 'fulfilled' ? data.records.value : [];
  const manualJournals = entry?.journals?.filter((item) => item.source !== 'record') ?? [];

  const showStudySection = !compact || studySeconds > 0 || studySessions.length > 0 || data?.study.status === 'rejected';
  const hasAnyCompactSection = Boolean(
    (entry && (entry.todos.length > 0 || entry.schedules.length > 0 || entry.expenses.length > 0 || entry.incomes.length > 0 || manualJournals.length > 0))
    || groups.size > 0
    || studySeconds > 0
    || studySessions.length > 0
    || records.length > 0,
  );

  return (
    <div className={compact ? styles.compact : styles.full}>
      {!data ? (
        <Skeleton label="正在读取当天记录…" />
      ) : (
        <>
          {failed && (
            <p className={styles.error}>
              部分数据读取失败{' '}
              <button
                type="button"
                onClick={() => setRetry((value) => value + 1)}
              >
                重试
              </button>
            </p>
          )}
          {compact && entry && (expenseSum > 0 || incomeSum > 0) && (
            <div className={styles.summary}>
              <div className={styles.summaryItem}>
                <span>支出</span>
                <b className={expenseSum > 0 ? styles.expenseVal : styles.zeroVal}>
                  ¥{expenseSum.toFixed(2)}
                </b>
              </div>
              <div className={styles.summaryItem}>
                <span>收入</span>
                <b className={incomeSum > 0 ? styles.incomeVal : styles.zeroVal}>
                  ¥{incomeSum.toFixed(2)}
                </b>
              </div>
            </div>
          )}
          {compact && entry && (
            <>
              {entry.todos.length > 0 && (
                <Section
                  compact={compact}
                  title="待办"
                  meta={`${entry.todos.filter((item) => item.completed).length}/${entry.todos.length}`}
                >
                  {entry.todos.map((item) => (
                    <Row
                      key={item.id}
                      title={`${item.completed ? '✓' : '○'} ${item.title}`}
                      meta={item.time ?? undefined}
                    />
                  ))}
                </Section>
              )}
              {entry.schedules.length > 0 && (
                <Section compact={compact} title="日程" meta={`${entry.schedules.length} 项`}>
                  {entry.schedules.map((item) => (
                    <Row
                      key={item.id}
                      title={item.title}
                      meta={[item.startTime, item.endTime]
                        .filter(Boolean)
                        .join(' – ')}
                    />
                  ))}
                </Section>
              )}
              {entry.expenses.length + entry.incomes.length > 0 && (
                <Section
                  compact={compact}
                  title="收支明细"
                  meta={`${entry.expenses.length + entry.incomes.length} 笔`}
                >
                  {[
                    ...entry.expenses.map((item) => ({
                      ...item,
                      sign: '−',
                      tone: 'expense' as const,
                    })),
                    ...entry.incomes.map((item) => ({
                      ...item,
                      sign: '+',
                      tone: 'income' as const,
                    })),
                  ].map((item) => (
                    <Row
                      key={item.id}
                      title={`${item.category}${item.note ? ` · ${item.note}` : ''}`}
                      meta={`${item.sign}¥${item.amount.toFixed(2)}`}
                      tone={item.tone}
                    />
                  ))}
                </Section>
              )}
            </>
          )}
          {data.assets.status === 'fulfilled' && groups.size > 0 && (
            <Section
              compact={compact}
              title="资产变动"
              meta={`${groups.size} 个账户`}
            >
              {[...groups].map(([accountId, items]) => (
                <div key={accountId} className={styles.asset}>
                  <strong>{items[0].accountName}</strong>
                  {items.map((item) => (
                    <Row
                      key={item.id}
                      title={`${LABELS[item.transactionType]} · ${item.note || item.category}`}
                      meta={`${decreases.has(item.transactionType) ? '−' : '+'}${item.amount.toFixed(2)} ${item.currency}`}
                      tone={
                        decreases.has(item.transactionType)
                          ? 'expense'
                          : 'income'
                      }
                    />
                  ))}
                </div>
              ))}
            </Section>
          )}
          {showStudySection && (
            <Section
              compact={compact}
              title="学习时间"
              meta={data.study.status === 'fulfilled' ? formatStudyDuration(studySeconds) : '暂不可用'}
            >
              {studySessions.length ? (
                studySessions.map((item) => (
                  <Row
                    key={item.id}
                    title={item.content}
                    meta={`${new Date(item.startedAt).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })} – ${item.endedAt ? new Date(item.endedAt).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '进行中'}`}
                  />
                ))
              ) : (
                <p className={styles.empty}>暂无学习记录</p>
              )}
            </Section>
          )}
          {records.length > 0 && (
            <Section
              compact={compact}
              title="当天资料"
              meta={`${records.length} 篇`}
            >
              {records.map((item) => (
                <div className={styles.document} key={item.id}>
                  <strong>{item.title || '无标题资料'}</strong>
                  <p>
                    {item.contentText.slice(0, 140) || '暂无正文'}
                    {item.contentText.length > 140 ? '…' : ''}
                  </p>
                </div>
              ))}
            </Section>
          )}
          {compact && manualJournals.length > 0 && (
            <Section compact={compact} title="手记" meta={`${manualJournals.length} 篇`}>
              {manualJournals.map((item) => (
                <Row
                  key={item.id}
                  title={item.title || item.excerpt.slice(0, 100)}
                  meta={item.mood}
                />
              ))}
            </Section>
          )}
          {compact && !hasAnyCompactSection && !failed && (
            <p className={styles.compactEmpty}>当天暂无记录</p>
          )}
          {!compact && (
            <p className={styles.empty}>
              {[
                data.assets.status === 'fulfilled' && !groups.size ? '无资产变动' : null,
                data.records.status === 'fulfilled' && !records.length ? '无资料' : null,
              ]
                .filter(Boolean)
                .join(' · ')}
            </p>
          )}
        </>
      )}
    </div>
  );
}
