import { useEffect, useState, type ReactNode } from 'react';
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
  children,
  compact,
}: {
  title: string;
  children: ReactNode;
  compact: boolean;
}) {
  return compact ? (
    <details className={styles.section}>
      <summary>{title}</summary>
      {children}
    </details>
  ) : (
    <section className={styles.section}>
      <h4>{title}</h4>
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
      <small>{meta}</small>
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
          {compact && entry && (
            <>
              <div className={styles.summary}>
                <span>
                  支出{' '}
                  <b>
                    ¥
                    {entry.expenses
                      .reduce((n, item) => n + item.amount, 0)
                      .toFixed(2)}
                  </b>
                </span>
                <span>
                  收入{' '}
                  <b>
                    ¥
                    {entry.incomes
                      .reduce((n, item) => n + item.amount, 0)
                      .toFixed(2)}
                  </b>
                </span>
              </div>
              {entry.todos.length > 0 && (
                <Section
                  compact={compact}
                  title={`待办 · ${entry.todos.filter((item) => item.completed).length}/${entry.todos.length} 已完成`}
                >
                  {entry.todos.length ? (
                    entry.todos.map((item) => (
                      <Row
                        key={item.id}
                        title={`${item.completed ? '✓' : '○'} ${item.title}`}
                        meta={item.time}
                      />
                    ))
                  ) : (
                    <p className={styles.empty}>暂无待办</p>
                  )}
                </Section>
              )}
              {entry.schedules.length > 0 && (
                <Section compact={compact} title="日程">
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
                  title={`收支明细 · ${entry.expenses.length + entry.incomes.length} 笔`}
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
                      title={`${item.category} · ${item.note || '无备注'}`}
                      meta={`${item.sign}¥${item.amount.toFixed(2)} · ${item.time}`}
                      tone={item.tone}
                    />
                  ))}
                  {!entry.expenses.length && !entry.incomes.length && (
                    <p className={styles.empty}>暂无收支</p>
                  )}
                </Section>
              )}
            </>
          )}
          {data.assets.status === 'fulfilled' && groups.size > 0 && (
            <Section
              compact={compact}
              title={`资产变动 · ${groups.size} 个账户`}
            >
              {groups.size ? (
                [...groups].map(([accountId, items]) => (
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
                ))
              ) : (
                <p className={styles.empty}>当天没有资产变动</p>
              )}
            </Section>
          )}
          <Section
            compact={compact}
            title={`学习时间${data.study.status === 'fulfilled' ? ` · ${formatStudyDuration(data.study.value.totalDurationSeconds)}` : ' · 暂不可用'}`}
          >
            {data.sessions.status === 'fulfilled' &&
              (data.sessions.value.length ? (
                data.sessions.value.map((item) => (
                  <Row
                    key={item.id}
                    title={item.content}
                    meta={`${new Date(item.startedAt).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })} – ${item.endedAt ? new Date(item.endedAt).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '进行中'}`}
                  />
                ))
              ) : (
                <p className={styles.empty}>暂无学习记录</p>
              ))}
          </Section>
          {data.records.status === 'fulfilled' &&
            data.records.value.length > 0 && (
              <Section
                compact={compact}
                title={`当天资料 · ${data.records.value.length} 篇`}
              >
                {data.records.value.length ? (
                  data.records.value.map((item) => (
                    <div className={styles.document} key={item.id}>
                      <strong>{item.title || '无标题资料'}</strong>
                      <p>
                        {item.contentText.slice(0, 140) || '暂无正文'}
                        {item.contentText.length > 140 ? '…' : ''}
                      </p>
                    </div>
                  ))
                ) : (
                  <p className={styles.empty}>暂无资料</p>
                )}
              </Section>
            )}
          <p className={styles.empty}>
            {[
              compact && entry && !entry.todos.length ? '无待办' : null,
              compact &&
              entry &&
              !entry.expenses.length &&
              !entry.incomes.length
                ? '无收支'
                : null,
              data.assets.status === 'fulfilled' && !groups.size
                ? '无资产变动'
                : null,
              data.records.status === 'fulfilled' && !data.records.value.length
                ? '无资料'
                : null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </p>
          {compact &&
            entry?.journals?.some((item) => item.source !== 'record') && (
              <Section compact={compact} title="手记">
                {entry.journals
                  .filter((item) => item.source !== 'record')
                  .map((item) => (
                    <Row
                      key={item.id}
                      title={item.title || item.excerpt.slice(0, 100)}
                      meta={item.mood}
                    />
                  ))}
              </Section>
            )}
        </>
      )}
    </div>
  );
}
