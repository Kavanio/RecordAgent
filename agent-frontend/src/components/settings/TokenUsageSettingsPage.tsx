import { useCallback, useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { Activity, ArrowDownLeft, ArrowUpRight, Blocks, Box, CalendarDays, ChartPie, ChevronDown, Cpu, Database, Layers, MessageSquare, RefreshCw, ShieldCheck, Wrench } from 'lucide-react';
import { fetchTokenUsageOverview } from '../../services/tokenUsageService';
import type { TokenUsageOverview } from '../../types/tokenUsage';
import { formatTokenCount } from '../chat/tokenUsageFormat';
import { Button } from '../common/Button';
import { Select } from '../common/Select';
import { SettingsPageLayout } from './SettingsPageLayout';
import { UsageRanking } from './UsageRanking';
import { UsageTrend } from './UsageTrend';
import { createDateRange, groupVendors, percentage, type UsageRange } from './tokenUsageAnalytics';
import styles from './TokenUsageSettingsPage.module.css';

const RANGES: { value: UsageRange; label: string }[] = [{ value: '7d', label: '近 7 天' }, { value: '30d', label: '近 30 天' }, { value: '90d', label: '近 90 天' }, { value: 'all', label: '全部时间' }];
const PURPOSE_LABELS: Record<string, string> = { CHAT: '聊天对话', SESSION_TITLE: '会话标题', MODEL_CONNECTION_TEST: '模型连接测试', PROFILE_MAINTENANCE: '画像维护', CONTEXT_COMPACTION: '上下文压缩', BACKGROUND_AGENT: '后台 Agent' };

/** 本地用量分析入口；范围切换与刷新均只采纳最后一次请求的结果。 */
export function TokenUsageSettingsPage() {
  const [range, setRange] = useState<UsageRange>('30d');
  const [overview, setOverview] = useState<TokenUsageOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestId = useRef(0);
  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const result = await fetchTokenUsageOverview(createDateRange(range));
      if (id === requestId.current) setOverview(result);
    } catch (cause) {
      if (id === requestId.current) setError(cause instanceof Error ? cause.message : '读取 Token 用量失败');
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, [range]);
  useEffect(() => {
    setOverview(null);
    void load();
    return () => { requestId.current += 1; };
  }, [load]);

  return <SettingsPageLayout title="Token 用量" loading={loading} loadingLabel="正在汇总所选范围的 Token 用量…" description="了解每一次调用的消耗，让模型、上下文与工具的用量更清晰。" actions={<Button variant="ghost" icon={<RefreshCw size={14} />} onClick={() => void load()} disabled={loading}>{loading ? '正在刷新' : '刷新统计'}</Button>}>
    <div className={styles.toolbar}><div className={styles.rangeTabs} role="group" aria-label="统计时间范围">{RANGES.map(item => <Button key={item.value} size="sm" variant="ghost" aria-pressed={range === item.value} onClick={() => setRange(item.value)}>{item.label}</Button>)}</div><span className={styles.localNote}><ShieldCheck size={14} aria-hidden="true" />仅统计本地记录</span></div>
    {error && !overview && <div className={styles.state} role="alert"><p>{error}</p><Button variant="outline" onClick={() => void load()}>重新加载</Button></div>}
    {overview && <div aria-busy={loading}>{error && <p className={styles.notice} role="alert">刷新失败：{error}。当前显示上次成功加载的数据。</p>}<UsageContent overview={overview} /></div>}
  </SettingsPageLayout>;
}

function UsageContent({ overview }: { overview: TokenUsageOverview }) {
  const { totals, breakdown } = overview;
  const [grouping, setGrouping] = useState('model');
  const ioTotal = totals.inputTokens + totals.outputTokens;
  const activeDays = overview.daily.filter(day => day.modelCallCount > 0).length;
  const peak = Math.max(0, ...overview.daily.map(day => day.totalTokens));
  const inputRows = [
    { key: 'system', label: '系统提示', tokens: breakdown.systemPromptTokens },
    { key: 'history', label: '历史对话', tokens: breakdown.historyTokens },
    { key: 'user', label: '当前提问', tokens: breakdown.currentUserTokens },
    { key: 'schema', label: '工具定义', tokens: breakdown.toolSchemaTokens },
    { key: 'result', label: '工具返回', tokens: breakdown.toolResultTokens },
    { key: 'profile', label: '个人画像', tokens: breakdown.profileContextTokens ?? 0 },
    { key: 'memory', label: '相关记忆', tokens: breakdown.memoryRecallTokens ?? 0 },
    { key: 'rag', label: '检索上下文', tokens: breakdown.ragContextTokens },
    { key: 'other', label: '其他 / 协议', tokens: breakdown.otherTokens },
  ];
  return <div className={styles.content}>
    <section className={styles.overview} aria-labelledby="usage-summary-title">
      <div className={styles.total}><h2 id="usage-summary-title">累计消耗</h2><strong>{formatTokenCount(totals.totalTokens)}</strong><span>tokens</span><p>{overview.from && overview.to ? `${overview.from} — ${overview.to}` : '全部本地历史记录'}</p>
        <span className={styles.status}><span className={styles.statusDot} />{totals.modelCallCount === 0 ? '等待首次调用' : totals.status === 'COMPLETE' ? '用量上报完整' : totals.status === 'PARTIAL' ? '部分调用缺少用量' : '暂无供应商用量上报'}</span>
      </div>
      <dl className={styles.metrics}>
        <Metric icon={<Cpu size={15} />} label="模型调用" value={totals.modelCallCount.toLocaleString()} detail={`${totals.reportedCallCount.toLocaleString()} 次已上报用量`} />
        <Metric icon={<MessageSquare size={15} />} label="聊天轮次" value={totals.turnCount.toLocaleString()} detail={`${totals.trackedTurnCount.toLocaleString()} 轮已统计`} />
        <Metric icon={<CalendarDays size={15} />} label="活跃天数" value={`${activeDays} 天`} detail="发生模型调用的日期" />
        <Metric icon={<Activity size={15} />} label="单次平均" value={totals.reportedCallCount ? formatTokenCount(Math.round(totals.totalTokens / totals.reportedCallCount)) : '—'} detail="tokens / 已上报调用" />
      </dl>
    </section>
    {totals.status !== 'COMPLETE' && totals.modelCallCount > 0 && <p className={styles.notice}>部分调用未返回用量，当前总量和平均值仅依据已上报数据，可能偏低。</p>}
    <div className={styles.distribution}>
      <section className={styles.composition} aria-label="输入输出比例">
        <div className={`${styles.ring} ${ioTotal === 0 ? styles.ringEmpty : ''}`} style={{ '--input-share': `${ioTotal ? totals.inputTokens / ioTotal * 100 : 0}%` } as CSSProperties} role="img" aria-label={`输入 ${percentage(totals.inputTokens, ioTotal)}，输出 ${percentage(totals.outputTokens, ioTotal)}`}><ChartPie size={20} aria-hidden="true" /></div>
        <dl className={styles.io}><div><dt><ArrowDownLeft size={13} aria-hidden="true" />输入</dt><dd>{formatTokenCount(totals.inputTokens)}<span>{percentage(totals.inputTokens, ioTotal)}</span></dd></div><div><dt><ArrowUpRight size={13} aria-hidden="true" />输出</dt><dd>{formatTokenCount(totals.outputTokens)}<span>{percentage(totals.outputTokens, ioTotal)}</span></dd></div></dl>
      </section>
      <dl className={styles.secondaryMetrics}>
        <Metric icon={<Database size={14} />} label="缓存输入" value={formatTokenCount(totals.cachedInputTokens)} detail="供应商返回的缓存 tokens" />
        <Metric icon={<ChartPie size={14} />} label="上报覆盖率" value={percentage(totals.reportedCallCount, totals.modelCallCount)} detail="已上报调用 / 全部调用" />
        <Metric icon={<ArrowUpRight size={14} />} label="单日峰值" value={formatTokenCount(peak)} detail="所选范围内最高 tokens" />
      </dl>
    </div>
    <UsageTrend overview={overview} />
    <div className={styles.breakdowns}>
      <div className={styles.modelSection}><div className={styles.grouping}><Select aria-label="模型排行分组" value={grouping} appearance="ghost" onChange={event => setGrouping(event.target.value)} options={[{ value: 'model', label: '按模型' }, { value: 'vendor', label: '按供应商' }]} /></div><UsageRanking title={grouping === 'model' ? '模型用量' : '供应商用量'} description="按总 Token 排序 · 展示各项占比" icon={<Cpu size={18} />} rows={grouping === 'model' ? overview.byModel.map(item => ({ key: JSON.stringify([item.vendor, item.model]), label: item.model || '未知模型', detail: item.vendor || '未知供应商', tokens: item.totalTokens, calls: item.modelCallCount })) : groupVendors(overview.byModel).map(item => ({ key: item.vendor, label: item.vendor, detail: `${item.models} 个模型`, tokens: item.totalTokens, calls: item.modelCallCount }))} /></div>
      <UsageRanking title="调用用途" description="了解聊天与后台任务分别消耗多少" icon={<Blocks size={18} />} rows={overview.byPurpose.map(item => ({ key: item.purpose, label: PURPOSE_LABELS[item.purpose] || item.purpose, detail: `${item.reportedCallCount} 次已上报`, tokens: item.totalTokens, calls: item.modelCallCount }))} />
    </div>
    <div className={styles.estimationHeading}><Layers size={18} aria-hidden="true" /><div><h2>输入消耗分析</h2><p>以下为本地归因估算，帮助定位上下文开销；不与供应商实际用量相加。</p></div><span>本地估算</span></div>
    <div className={styles.breakdowns}>
      <UsageRanking title="上下文构成" description={`输入估算总量 ${formatTokenCount(breakdown.estimatedInputTokens)} tokens · 占比按各分项之和计算`} icon={<Box size={18} />} rows={inputRows} />
      <UsageRanking title="工具用量" description="工具定义 + 返回内容 · 按估算 Token 排序" icon={<Wrench size={18} />} searchable rows={overview.byTool.map(item => ({ key: item.toolName, label: item.toolName, tokens: item.schemaTokens + item.resultTokens, detail: `定义 ${formatTokenCount(item.schemaTokens)} · 返回 ${formatTokenCount(item.resultTokens)}` }))} />
    </div>
    <details className={styles.method}><summary><ShieldCheck size={14} aria-hidden="true" />统计口径与数据说明<ChevronDown size={14} aria-hidden="true" /></summary><div><p>时间范围包含首尾两个自然日。总消耗以供应商返回的总 Token 为准；输入输出占比以已返回的输入与输出之和为分母。缓存输入单独展示，不重复加入总消耗。</p><p>单次平均仅使用已上报调用计算；未返回用量的调用仍计入调用次数。按周、按月查看时，首尾周期仅包含所选范围内的数据。</p><p>上下文和工具数据是本地估算，可能与供应商计数不同；未出现估算记录不代表实际没有消耗。所有数据来自本机记录。</p></div></details>
  </div>;
}

function Metric({ icon, label, value, detail }: { icon: ReactNode; label: string; value: string; detail: string }) {
  return <div className={styles.metric}><dt><span aria-hidden="true">{icon}</span>{label}</dt><dd>{value}</dd><dd className={styles.metricDetail}>{detail}</dd></div>;
}
