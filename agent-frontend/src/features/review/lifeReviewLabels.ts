/** 事实数值的中文展示；保留原始单位，避免把最小货币单位误当元。 */
const METRICS: Record<string, string> = { duration: '学习时长', sessions: '学习次数', occurrences: '待办次数',
  completed: '完成待办', income: '收入', expense: '支出', net_cashflow: '净现金流', asset_balance_book_reconstruction: '账面资产余额' };
const UNITS: Record<string, string> = { SECONDS: '秒', COUNT: '次', MONEY_MINOR: '最小货币单位' };
export function lifeFactLabel(fact: { metric: string; value: string; unit: string; currency: string }) {
  const unit = fact.unit === 'MONEY_MINOR' && fact.currency === 'CNY' ? '分' : UNITS[fact.unit] || fact.unit;
  return `${METRICS[fact.metric] || fact.metric}：${fact.value} ${unit}${fact.currency ? ` ${fact.currency}` : ''}`;
}
