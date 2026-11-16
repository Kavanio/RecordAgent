import type { FinanceAccount, FinanceTransaction } from '../../types/finance';

export function formatFinanceMoney(value: number, currency = 'CNY'): string {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency }).format(value);
}

/** 判断资产账户余额是否已清零（按分取整避免浮点精度误差）。 */
export function isZeroBalanceAccount(account: Pick<FinanceAccount, 'balance'>): boolean {
  return Math.round(account.balance * 100) === 0;
}

/** 划账的两条流水共用日期、时间与金额，删除文案据此找到对方账户名称。 */
function findTransferPartner(
  transaction: FinanceTransaction,
  allTransactions: FinanceTransaction[],
): FinanceTransaction | undefined {
  return allTransactions.find((item) =>
    item.id !== transaction.id
    && (item.transactionType === 'transfer_out' || item.transactionType === 'transfer_in')
    && item.transactionType !== transaction.transactionType
    && item.date === transaction.date
    && item.time === transaction.time
    && item.amount === transaction.amount);
}

/** 删除确认文案逐类型说明余额会怎么回滚，避免用户误删成新的错账。 */
export function buildDeleteTransactionDescription(
  transaction: FinanceTransaction,
  allTransactions: FinanceTransaction[],
): string {
  const amount = formatFinanceMoney(transaction.amount);
  const partnerName = findTransferPartner(transaction, allTransactions)?.accountName;
  switch (transaction.transactionType) {
    case 'expense':
      return transaction.source === 'calendar'
        ? '这是从日历记录同步的旧花销，删除后会同时从日历和记录中移除，不影响资产账户余额。'
        : `删除后 ${amount} 会退回「${transaction.accountName}」。`;
    case 'income':
      return `删除后 ${amount} 会从「${transaction.accountName}」扣回；余额不足时删除会被拒绝。`;
    case 'transfer_out':
      return `这会同时删除对应的转入记录，${amount} 会退回「${transaction.accountName}」${partnerName ? `，并从「${partnerName}」扣回。` : '。'}`;
    case 'transfer_in':
      return `这会同时删除对应的转出记录，${amount} 会从「${transaction.accountName}」扣回${partnerName ? `，并退回「${partnerName}」。` : '。'}`;
    case 'adjustment_increase':
      return `删除后余额校准的影响一并回滚，「${transaction.accountName}」余额将减少 ${amount}。`;
    case 'adjustment_decrease':
      return `删除后余额校准的影响一并回滚，「${transaction.accountName}」余额将增加 ${amount}。`;
    default:
      return '';
  }
}

/** 停用并删除资产账户前的确认提示，明确说明保留历史流水与统计的边界。 */
export function buildDeleteAccountDescription(account: FinanceAccount): string {
  return `停用并删除「${account.name}」后，该账户将从总资产与账户列表中移除，不再计息或用于新记账；历史流水与收支统计将完整保留，操作不可恢复。`;
}

/** 余额未清零时提示先划账或校准余额。 */
export function buildBlockedDeleteAccountDescription(account: FinanceAccount): string {
  return `「${account.name}」当前余额为 ${formatFinanceMoney(account.balance, account.currency)}，不允许直接删除。请先通过「划账」转出资金，或使用「调整」将余额清零后再删除。`;
}
