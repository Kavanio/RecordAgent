import {
  Activity, CalendarClock, CalendarDays, CircleHelp,
  GraduationCap, Search, WalletCards, X,
} from 'lucide-react';
import type { CSSProperties } from 'react';
import { LoadingTree } from '../common/LoadingTree';
import { RECORD_REFERENCE_PRESENTATIONS } from '../../features/record/recordReferencePresentation';
import type { SlashCommandDefinition } from '../../features/slash-command/slashCommands';
import type { DailyInsight } from '../../types/dailyInsight';
import type { DailyDay, ScheduleDailyEvent, TodoDailyEvent } from '../../types/dailyEvent';
import type { FinanceExpenseChart } from '../../types/finance';
import type { RecordReferenceOption } from '../../types/record';
import type { StudyStatistics } from '../../types/study';
import type { SlashQueryPeriod } from '../../features/slash-command/slashCommandArguments';
import type { SlashStatusData } from '../../features/slash-command/slashCommandStatus';
import { RecordReferenceIcon } from './RecordReferenceIcon';
import { formatTokenCount } from './tokenUsageFormat';
import styles from './SlashCommandResult.module.css';

export type SlashCommandResultData =
  | { kind: 'help'; commands: readonly SlashCommandDefinition[]; command?: SlashCommandDefinition }
  | { kind: 'status' }
  | { kind: 'today'; data: DailyInsight }
  | { kind: 'agenda'; data: DailyDay }
  | { kind: 'spending'; data: FinanceExpenseChart }
  | { kind: 'study-report'; data: StudyStatistics; period: SlashQueryPeriod }
  | { kind: 'find-record'; data: RecordReferenceOption[]; query: string; hasMore: boolean }
  | { kind: 'loading'; message: string }
  | { kind: 'error'; message: string };

interface SlashCommandResultProps {
  result: SlashCommandResultData;
  statusData: SlashStatusData;
  onClose: () => void;
}

export function SlashCommandResult({ result, statusData, onClose }: SlashCommandResultProps) {
  return (
    <section className={styles.panel} aria-live="polite">
      <header className={styles.header}>
        <div className={styles.heading}>
          <ResultIcon kind={result.kind} />
          <span>{result.kind === 'status' ? '状态'
            : result.kind === 'help' ? '可用命令'
              : result.kind === 'today' ? '今日活动'
                : result.kind === 'agenda' ? '待办与日程'
                  : result.kind === 'spending' ? '收支统计'
                    : result.kind === 'study-report' ? '学习统计'
                      : result.kind === 'find-record' ? '资料检索'
                : result.kind === 'loading' ? '正在执行' : '命令提示'}</span>
        </div>
        <button type="button" className={styles.close} onClick={onClose} aria-label="关闭命令结果">
          <X size={14} strokeWidth={1.8} aria-hidden="true" />
        </button>
      </header>

      {result.kind === 'help' ? <HelpContent result={result} />
        : result.kind === 'status' ? <StatusContent data={statusData} />
          : result.kind === 'today' ? <TodayContent data={result.data} />
            : result.kind === 'agenda' ? <AgendaContent data={result.data} />
              : result.kind === 'spending' ? <SpendingContent data={result.data} />
                : result.kind === 'study-report' ? <StudyReportContent data={result.data} period={result.period} />
                  : result.kind === 'find-record' ? <RecordSearchContent data={result.data} query={result.query} hasMore={result.hasMore} />
                    : result.kind === 'loading' ? <div className={styles.loading}><LoadingTree size="small" label={result.message} /></div>
                      : <p className={styles.error}>{result.message}</p>}
    </section>
  );
}

function ResultIcon({ kind }: { kind: SlashCommandResultData['kind'] }) {
  if (kind === 'status' || kind === 'loading') return <Activity size={15} strokeWidth={1.6} aria-hidden="true" />;
  if (kind === 'today') return <CalendarDays size={15} strokeWidth={1.6} aria-hidden="true" />;
  if (kind === 'agenda') return <CalendarClock size={15} strokeWidth={1.6} aria-hidden="true" />;
  if (kind === 'spending') return <WalletCards size={15} strokeWidth={1.6} aria-hidden="true" />;
  if (kind === 'study-report') return <GraduationCap size={15} strokeWidth={1.6} aria-hidden="true" />;
  if (kind === 'find-record') return <Search size={15} strokeWidth={1.6} aria-hidden="true" />;
  return <CircleHelp size={15} strokeWidth={1.6} aria-hidden="true" />;
}

function HelpContent({ result }: { result: Extract<SlashCommandResultData, { kind: 'help' }> }) {
  if (result.command) {
    return (
      <div className={styles.commandDetail}>
        <code>{result.command.usage}</code>
        <p>{result.command.description}</p>
        {result.command.aliases.length > 0 && <p>别名：/{result.command.aliases.join('、/')}</p>}
      </div>
    );
  }
  return (
    <div className={styles.commandList}>
      {result.commands.map((command) => (
        <div key={command.name} className={styles.commandRow}>
          <code>/{command.name}</code>
          <span>{command.description}</span>
        </div>
      ))}
    </div>
  );
}

function StatusContent({ data }: { data: SlashStatusData }) {
  const contextRatio = data.contextTokens != null && data.contextWindow
    ? Math.min(100, Math.round((data.contextTokens / data.contextWindow) * 100))
    : null;
  return (
    <dl className={styles.statusGrid}>
      <div><dt>模型</dt><dd>{data.providerName} · {data.modelName}</dd></div>
      <div><dt>会话</dt><dd title={data.sessionId}>{data.sessionTitle} · {data.sessionId.slice(0, 8)}</dd></div>
      <div><dt>权限模式</dt><dd>{data.permissionMode}</dd></div>
      <div><dt>运行状态</dt><dd>{data.runtimeState}</dd></div>
      <div><dt>会话 Token</dt><dd>{formatTokenCount(data.totalTokens)}</dd></div>
      <div><dt>上下文压缩</dt><dd>{data.compactionState}</dd></div>
      <div className={styles.contextRow}>
        <dt>上下文窗口</dt>
        <dd>
          {data.contextTokens == null
            ? '暂无可用调用数据'
            : data.contextWindow
              ? `${formatTokenCount(data.contextTokens)} / ${formatTokenCount(data.contextWindow)}（${contextRatio}%）`
              : `${formatTokenCount(data.contextTokens)} / 上限未知`}
        </dd>
        {contextRatio != null && <progress max="100" value={contextRatio} aria-label={`上下文窗口已使用 ${contextRatio}%`} />}
      </div>
    </dl>
  );
}

const MONEY_FORMATTER = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' });

function TodayContent({ data }: { data: DailyInsight }) {
  const categories = Object.entries(data.finance.expenseCategories)
    .sort((left, right) => right[1] - left[1])
    .map(([name, amount]) => `${name} ${MONEY_FORMATTER.format(amount)}`)
    .join(' · ');
  return (
    <div>
      <p className={styles.insightDate}>{data.date}</p>
      <dl className={styles.insightGrid}>
        <div><dt>待办</dt><dd>{data.todos.completed} / {data.todos.total}</dd><small>{data.todos.pending} 项待完成</small></div>
        <div><dt>日程</dt><dd>{data.scheduleCount} 项</dd><small>今日安排</small></div>
        <div><dt>资料</dt><dd>{data.records.createdCount} 篇</dd><small>当日资料</small></div>
        <div><dt>花销</dt><dd>{MONEY_FORMATTER.format(data.finance.expenseTotal)}</dd><small>{data.finance.expenseCount} 笔{categories ? ` · ${categories}` : ''}</small></div>
        <div><dt>学习</dt><dd>{formatStudyDuration(data.study.durationSeconds)}</dd><small>{data.study.sessionCount} 次记录</small></div>
      </dl>
    </div>
  );
}

function formatStudyDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  if (hours > 0) return remainingMinutes > 0 ? `${hours} 小时 ${remainingMinutes} 分钟` : `${hours} 小时`;
  return `${minutes} 分钟`;
}

function AgendaContent({ data }: { data: DailyDay }) {
  const todos = data.events.filter((event): event is TodoDailyEvent => event.eventType === 'todo');
  const schedules = data.events.filter((event): event is ScheduleDailyEvent => event.eventType === 'schedule');
  return (
    <div className={styles.queryContent}>
      <p className={styles.insightDate}>{data.date} · {todos.length} 项待办 · {schedules.length} 项日程</p>
      {todos.length === 0 && schedules.length === 0 ? <p className={styles.empty}>这一天没有待办或日程。</p> : (
        <div className={styles.agendaColumns}>
          <QueryList title="待办">
            {todos.map((todo) => <li key={todo.id}>
              <span className={todo.details.completed ? styles.completed : ''}>{todo.title}</span>
              <small>{todo.details.completed ? '已完成' : todo.details.time || '未完成'}</small>
            </li>)}
          </QueryList>
          <QueryList title="日程">
            {schedules.map((schedule) => <li key={schedule.id}>
              <span>{schedule.title}</span>
              <small>{schedule.details.startTime || '时间待定'}{schedule.details.location ? ` · ${schedule.details.location}` : ''}</small>
            </li>)}
          </QueryList>
        </div>
      )}
    </div>
  );
}

function QueryList({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className={styles.queryList}><h4>{title}</h4><ul>{children}</ul></section>;
}

function SpendingContent({ data }: { data: FinanceExpenseChart }) {
  const categories = new Map<string, number>();
  data.days.forEach((day) => day.categories.forEach((item) =>
    categories.set(item.category, (categories.get(item.category) ?? 0) + item.amount)));
  const categoryText = [...categories.entries()]
    .sort((left, right) => right[1] - left[1])
    .slice(0, 4)
    .map(([name, amount]) => `${name} ${MONEY_FORMATTER.format(amount)}`)
    .join(' · ');
  return (
    <div className={styles.queryContent}>
      <p className={styles.insightDate}>{data.from} 至 {data.to}</p>
      <dl className={styles.summaryMetrics}>
        <div><dt>支出</dt><dd>{MONEY_FORMATTER.format(data.totalExpense)}</dd></div>
        <div><dt>收入</dt><dd>{MONEY_FORMATTER.format(data.totalIncome)}</dd></div>
        <div><dt>结余</dt><dd>{MONEY_FORMATTER.format(data.totalIncome - data.totalExpense)}</dd></div>
      </dl>
      <p className={styles.queryNote}>{categoryText || '当前范围内没有支出分类。'}</p>
    </div>
  );
}

function StudyReportContent({ data, period }: { data: StudyStatistics; period: SlashQueryPeriod }) {
  const label = period === 'today' ? '今日' : period === 'week' ? '本周' : '本月';
  return (
    <div className={styles.queryContent}>
      <p className={styles.insightDate}>{label} · {data.from} 至 {data.to}</p>
      <dl className={styles.summaryMetrics}>
        <div><dt>学习时长</dt><dd>{formatStudyDuration(data.totalDurationSeconds)}</dd></div>
        <div><dt>记录次数</dt><dd>{data.sessionCount} 次</dd></div>
        <div><dt>学习天数</dt><dd>{data.studyDays} 天</dd></div>
        <div><dt>日均投入</dt><dd>{formatStudyDuration(data.averageDailySeconds)}</dd></div>
      </dl>
    </div>
  );
}

function RecordSearchContent({ data, query, hasMore }: {
  data: RecordReferenceOption[];
  query: string;
  hasMore: boolean;
}) {
  return (
    <div className={styles.queryContent}>
      <p className={styles.insightDate}>“{query}” · {data.length} 条结果</p>
      {data.length === 0 ? <p className={styles.empty}>没有找到匹配的资料。</p> : (
        <ul className={styles.recordResults}>
          {data.map((record) => {
            const presentation = RECORD_REFERENCE_PRESENTATIONS[record.type];
            return <li key={record.id} style={{ '--reference-color': presentation.color } as CSSProperties}>
              <RecordReferenceIcon icon={presentation.icon} size={15} />
              <span><b>{record.title || '无标题资料'}</b><small>{record.summary || '暂无正文摘要'}</small></span>
              <em>{presentation.label} · {record.recordDate}</em>
            </li>;
          })}
        </ul>
      )}
      {hasMore && <p className={styles.queryNote}>结果较多，当前显示前 {data.length} 条；请补充关键词缩小范围。</p>}
    </div>
  );
}
