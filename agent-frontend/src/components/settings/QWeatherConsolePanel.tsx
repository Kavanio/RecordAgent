import { RefreshCw } from 'lucide-react';
import type { QWeatherConsoleSummary } from '../../services/dailyContextApi';
import { Button } from '../common/Button';
import { LoadingTree } from '../common/LoadingTree';
import styles from './DailyContextSettingsPage.module.css';

interface QWeatherConsolePanelProps {
  summary: QWeatherConsoleSummary | null;
  loading: boolean;
  onRefresh: () => void;
}

/** 和风天气控制台费用与接口用量面板。 */
export function QWeatherConsolePanel({ summary, loading, onRefresh }: QWeatherConsolePanelProps) {
  const finance = summary?.finance;
  const usage = summary?.usage;
  const hasData = Boolean(finance || usage);

  return (
    <section
      className={styles.consolePanel}
      aria-labelledby="qweather-console-title"
      aria-busy={loading}
      aria-live="polite"
    >
      <div className={styles.consoleHeading}>
        <div>
          <h3 id="qweather-console-title">用量与费用</h3>
          <p>来自和风控制台 API，数据通常延迟一小时以上。</p>
        </div>
        <Button
          variant="ghost"
          size="sm"
          icon={<RefreshCw className={loading ? styles.refreshing : ''} size={13} />}
          disabled={loading}
          onClick={onRefresh}
        >
          {loading ? '刷新中' : '刷新'}
        </Button>
      </div>

      {loading && !hasData && (
        <div className={styles.consoleLoading}>
          <LoadingTree size="small" label="正在读取用量与费用…" />
        </div>
      )}

      {hasData && (
        <>
          <dl className={styles.consoleMetrics}>
            <Metric label="账户余额" value={finance ? formatMoney(finance.balance, finance.currency) : '—'} />
            <Metric label="本月费用" value={finance ? formatMoney(finance.thisMonthCharges, finance.currency) : '—'} />
            <Metric label="昨日费用" value={finance ? formatMoney(finance.previousDayCharges, finance.currency) : '—'} />
            <Metric
              label={`待支付账单${finance?.pendingBillCount ? ` · ${finance.pendingBillCount} 笔` : ''}`}
              value={finance ? formatMoney(finance.pendingAmountDue, finance.currency) : '—'}
              warning={Boolean(finance?.pendingBillCount)}
            />
            <Metric label="24 小时成功请求" value={usage ? formatCount(usage.successRequests) : '—'} />
            <Metric label="24 小时失败请求" value={usage ? formatCount(usage.errorRequests) : '—'} warning={Boolean(usage?.errorRequests)} />
          </dl>

          {usage && usage.apis.length > 0 && (
            <div className={styles.usageBreakdown}>
              <div className={styles.usageHeader}><span>API</span><span>成功</span><span>失败</span></div>
              {usage.apis.map((item) => (
                <div className={styles.usageRow} key={item.api}>
                  <span>{formatApiName(item.api)}</span>
                  <span>{formatCount(item.successRequests)}</span>
                  <span className={item.errorRequests ? styles.errorCount : undefined}>{formatCount(item.errorRequests)}</span>
                </div>
              ))}
            </div>
          )}

          <p className={styles.consoleTimestamp}>
            数据截止：{formatAsOf(usage?.asOf ?? finance?.asOf)}
          </p>
        </>
      )}
    </section>
  );
}

function Metric({ label, value, warning = false }: { label: string; value: string; warning?: boolean }) {
  return <div><dt>{label}</dt><dd className={warning ? styles.metricWarning : undefined}>{value}</dd></div>;
}

function formatMoney(value: number, currency: string): string {
  try {
    return new Intl.NumberFormat('zh-CN', {
      style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2,
    }).format(value);
  } catch {
    return `${value.toFixed(2)} ${currency}`;
  }
}

function formatCount(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(value);
}

function formatAsOf(value?: string): string {
  if (!value) return '未知';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

function formatApiName(api: string): string {
  const names: Record<string, string> = {
    Weather: '天气', Geo: '地理位置', WeatherAlert: '天气预警',
    WeatherIndices: '天气指数', AirQuality: '空气质量', Console: '控制台',
  };
  return names[api] ?? api;
}
