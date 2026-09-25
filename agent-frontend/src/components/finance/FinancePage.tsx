import { TopBarAction } from '../common/TopBarAction';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowClockwiseIcon, ArrowsLeftRightIcon, CaretRightIcon, PencilSimpleIcon, PlusIcon, SparkleIcon, WalletIcon } from '@phosphor-icons/react';
import { createFinanceAccount, createFinanceBalanceAdjustment, createFinanceTransaction, createFinanceTransfer, fetchFinanceCategories, fetchFinanceExpenseChart, fetchFinanceOverview, fetchFinanceTransactions, updateFinanceTransaction } from '../../services/financeApi';
import type { CreateFinanceBalanceAdjustmentInput, CreateFinanceTransactionInput, CreateFinanceTransferInput, FinanceAccount, FinanceAccountType, FinanceCategoryOptions, FinanceChartRange, FinanceExpenseChart, FinanceOverview, FinanceTransaction } from '../../types/finance';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import { Modal } from '../common/Modal';
import { TopBar } from '../common/TopBar';
import styles from './FinancePage.module.css';
import { AccountTypeIcon } from './AccountTypeIcon';
import { AssetAccountDeck } from './AssetAccountDeck';
import { AssetAdjustmentModal } from './AssetAdjustmentModal';
import { SpendingTrendChart } from './SpendingTrendChart';
import { TransactionTypeIcon } from './TransactionTypeIcon';
import { TransactionDrawer } from './TransactionDrawer';
import { TransactionModal } from './TransactionModal';
import { TransferModal } from './TransferModal';
import { AssetDetailModal } from './AssetDetailModal';
import { FinanceSummary } from './FinanceSummary';

const ACCOUNT_TYPES: Array<{ value: FinanceAccountType; label: string; interest: boolean }> = [
  { value: 'wechat_balance', label: '微信余额', interest: false },
  { value: 'wechat_yield', label: '微信零钱通', interest: true },
  { value: 'alipay_balance', label: '支付宝余额', interest: false },
  { value: 'alipay_yuebao', label: '支付宝余额宝', interest: true },
  { value: 'bank', label: '银行卡', interest: false },
  { value: 'other', label: '其他', interest: false },
];
function money(value: number): string {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);
}

/** 独立财务工作台：统一完成资产建档、收支记账和自动收益查看。 */
export const FinancePage: React.FC = () => {
  const { showMessage } = useMessage();
  const [overview, setOverview] = useState<FinanceOverview | null>(null);
  const [categoryOptions, setCategoryOptions] = useState<FinanceCategoryOptions>({ expense: [], income: [] });
  const [chart, setChart] = useState<FinanceExpenseChart | null>(null);
  const [chartRange, setChartRange] = useState<FinanceChartRange>('month');
  const [isLoading, setIsLoading] = useState(true);
  const [overviewError, setOverviewError] = useState<string | null>(null);
  const [categoriesError, setCategoriesError] = useState<string | null>(null);
  const [isCategoriesLoading, setIsCategoriesLoading] = useState(true);
  const [isChartLoading, setIsChartLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [accountError, setAccountError] = useState<string | null>(null);
  const [chartError, setChartError] = useState<string | null>(null);
  const [isAccountModalOpen, setIsAccountModalOpen] = useState(false);
  const [isTransactionModalOpen, setIsTransactionModalOpen] = useState(false);
  const [editingTransaction, setEditingTransaction] = useState<FinanceTransaction | null>(null);
  const [isTransferModalOpen, setIsTransferModalOpen] = useState(false);
  const [isTransactionDrawerOpen, setIsTransactionDrawerOpen] = useState(false);
  const [isAssetDetailModalOpen, setIsAssetDetailModalOpen] = useState(false);
  const [adjustmentAccount, setAdjustmentAccount] = useState<FinanceAccount | null>(null);
  const [allTransactions, setAllTransactions] = useState<FinanceTransaction[]>([]);
  const [isTransactionsLoading, setIsTransactionsLoading] = useState(false);
  const [transactionsError, setTransactionsError] = useState<string | null>(null);
  const [interestEnabled, setInterestEnabled] = useState(false);
  const [accountType, setAccountType] = useState<FinanceAccountType>('wechat_balance');
  const chartRequestId = useRef(0);
  const overviewRequestId = useRef(0);
  const categoriesRequestId = useRef(0);

  const load = useCallback(async () => {
    const requestId = ++overviewRequestId.current;
    setIsLoading(true);
    setOverviewError(null);
    try {
      const nextOverview = await fetchFinanceOverview();
      if (requestId === overviewRequestId.current) setOverview(nextOverview);
    } catch (cause) {
      if (requestId === overviewRequestId.current) setOverviewError(cause instanceof Error ? cause.message : '财务数据加载失败，请稍后重试。');
    } finally {
      if (requestId === overviewRequestId.current) setIsLoading(false);
    }
  }, []);

  // 分类失败只影响表单中的历史分类，不阻塞资产、流水和图表。
  const loadCategories = useCallback(async () => {
    const requestId = ++categoriesRequestId.current;
    setIsCategoriesLoading(true);
    setCategoriesError(null);
    try {
      const nextCategories = await fetchFinanceCategories();
      if (requestId === categoriesRequestId.current) setCategoryOptions(nextCategories);
    } catch (cause) {
      if (requestId === categoriesRequestId.current) setCategoriesError(cause instanceof Error ? cause.message : '历史分类加载失败');
    } finally {
      if (requestId === categoriesRequestId.current) setIsCategoriesLoading(false);
    }
  }, []);

  const loadChart = useCallback(async (range: FinanceChartRange) => {
    const requestId = ++chartRequestId.current;
    setIsChartLoading(true);
    setChartError(null);
    try {
      const nextChart = await fetchFinanceExpenseChart(range);
      if (requestId === chartRequestId.current) setChart(nextChart);
    } catch (cause) {
      if (requestId === chartRequestId.current) {
        setChartError(cause instanceof Error ? cause.message : '支出趋势加载失败，请稍后重试。');
      }
    } finally {
      if (requestId === chartRequestId.current) setIsChartLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { void loadCategories(); }, [loadCategories]);
  useEffect(() => { void loadChart(chartRange); }, [chartRange, loadChart]);
  useEffect(() => () => {
    overviewRequestId.current += 1;
    categoriesRequestId.current += 1;
    chartRequestId.current += 1;
  }, []);
  const isYieldAccountType = accountType === 'wechat_yield' || accountType === 'alipay_yuebao';
  const recentTransactions = overview?.transactions.slice(0, 5) ?? [];

  const loadAllTransactions = useCallback(async () => {
    setIsTransactionsLoading(true);
    setTransactionsError(null);
    try { setAllTransactions(await fetchFinanceTransactions()); }
    catch (cause) { setTransactionsError(cause instanceof Error ? cause.message : '全部流水加载失败，请稍后重试。'); }
    finally { setIsTransactionsLoading(false); }
  }, []);

  const openTransactionDrawer = () => {
    setIsTransactionDrawerOpen(true);
    void loadAllTransactions();
  };

  const openAccountModal = () => {
    setAccountError(null);
    setInterestEnabled(false);
    setAccountType('wechat_balance');
    setIsAccountModalOpen(true);
  };

  const closeAccountModal = () => {
    if (isSaving) return;
    setAccountError(null);
    setIsAccountModalOpen(false);
  };

  const openTransactionModal = () => {
    if (!overview?.accounts.length) return;
    setEditingTransaction(null);
    setIsTransactionModalOpen(true);
  };

  const editTransaction = (transaction: FinanceTransaction) => {
    setIsTransactionDrawerOpen(false);
    setEditingTransaction(transaction);
    setIsTransactionModalOpen(true);
  };

  const closeTransactionModal = () => {
    setIsTransactionModalOpen(false);
    setEditingTransaction(null);
  };

  const submitAccount = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setIsSaving(true);
    setAccountError(null);
    try {
      await createFinanceAccount({
        name: String(data.get('name') ?? '').trim(),
        accountType: String(data.get('accountType')) as FinanceAccountType,
        currency: 'CNY', initialBalance: Number(data.get('initialBalance')), interestEnabled,
        annualRatePercent: interestEnabled ? Number(data.get('annualRatePercent')) : 0,
      });
      form.reset();
      setInterestEnabled(false);
      await load();
      setIsAccountModalOpen(false);
      showMessage('success', '资产账户已添加');
    } catch (cause) {
      setAccountError(cause instanceof Error ? cause.message : '账户保存失败，请检查填写内容。');
    } finally { setIsSaving(false); }
  };

  const submitTransaction = async (input: CreateFinanceTransactionInput) => {
    if (editingTransaction) {
      await updateFinanceTransaction(editingTransaction.id, input);
    } else {
      await createFinanceTransaction(input);
    }
    void loadCategories();
    await Promise.all([load(), loadChart(chartRange), ...(isTransactionDrawerOpen ? [loadAllTransactions()] : [])]);
    showMessage('success', editingTransaction ? '流水已更新，账户余额已同步调整' : '流水已保存');
    closeTransactionModal();
  };

  const submitTransfer = async (input: CreateFinanceTransferInput) => {
    await createFinanceTransfer(input);
    await Promise.all([load(), loadChart(chartRange), ...(isTransactionDrawerOpen ? [loadAllTransactions()] : [])]);
    setIsTransferModalOpen(false);
    showMessage('success', '划账已完成，双方账户余额已同步调整');
  };

  const openBalanceAdjustment = (account: FinanceAccount) => {
    setIsAssetDetailModalOpen(false);
    setAdjustmentAccount(account);
  };

  const submitBalanceAdjustment = async (input: CreateFinanceBalanceAdjustmentInput) => {
    if (!adjustmentAccount) return;
    await createFinanceBalanceAdjustment(adjustmentAccount.id, input);
    await load();
    setAdjustmentAccount(null);
    showMessage('success', '资产余额已调整，并记录为余额校准流水');
  };

  return <main className={styles.workspace}>
    <TopBar icon={<WalletIcon size={16} />} title="财务" subtitle="资产与收支" actions={<>
      <TopBarAction iconOnly title="刷新财务数据" aria-label="刷新财务数据"
        onClick={() => void Promise.all([load(), loadCategories(), loadChart(chartRange)])} disabled={isLoading || isChartLoading}>
        <ArrowClockwiseIcon size={14} className={isLoading || isChartLoading ? styles.spinning : ''} />
      </TopBarAction>
      <TopBarAction type="button" variant="outline" icon={<PlusIcon size={13} weight="bold" />} onClick={openAccountModal}>添加账户</TopBarAction>
      <TopBarAction
        type="button"
        variant="outline"
        icon={<ArrowsLeftRightIcon size={13} weight="bold" />}
        onClick={() => setIsTransferModalOpen(true)}
        disabled={(overview?.accounts.length ?? 0) < 2}
        title={(overview?.accounts.length ?? 0) < 2 ? '至少需要两个资产账户才能划账' : '在账户之间划转资金'}
      >
        划账
      </TopBarAction>
      <TopBarAction type="button" variant="primary" onClick={openTransactionModal} disabled={!overview?.accounts.length}>记一笔</TopBarAction>
    </>} />

    <div className={styles.page}><div className={styles.content}>
        <FinanceSummary overview={overview} loading={isLoading} error={overviewError}
          onRetry={() => void load()} onOpenAssets={() => setIsAssetDetailModalOpen(true)} />

        <SpendingTrendChart chart={chart} range={chartRange} loading={isChartLoading} error={chartError}
          onRangeChange={setChartRange} onRetry={() => void loadChart(chartRange)} />

        {overview && <div className={styles.financeLayout}>
          <section className={styles.historyPanel}>
            <div className={styles.sectionHeading}><div><h2>最近流水</h2><p>最近 {recentTransactions.length} 笔记录</p></div><div className={styles.sectionMeta}>{overview.transactions.length ? <Button type="button" variant="ghost" size="sm" onClick={openTransactionDrawer}>全部流水<CaretRightIcon size={12} /></Button> : null}</div></div>
            {recentTransactions.length ? <div className={styles.transactions}>{recentTransactions.map((transaction) => {
              const isExpense = transaction.transactionType === 'expense';
              const isTransferOut = transaction.transactionType === 'transfer_out';
              const isTransferIn = transaction.transactionType === 'transfer_in';
              const isAdjustment = transaction.transactionType === 'adjustment_increase'
                || transaction.transactionType === 'adjustment_decrease';
              const isOutflow = isExpense || isTransferOut || transaction.transactionType === 'adjustment_decrease';
              const amountClass = isAdjustment
                ? styles.adjustmentAmount
                : isExpense
                ? styles.outAmount
                : isTransferOut
                ? styles.transferOutAmount
                : isTransferIn
                ? styles.transferInAmount
                : styles.inAmount;
              return <div className={styles.transactionRow} key={transaction.id}>
                <TransactionTypeIcon type={transaction.transactionType} category={transaction.category} />
                <span className={styles.transactionBody}><strong>{transaction.note}</strong><small>{transaction.accountName} · {transaction.category} · {transaction.date.slice(5)} {transaction.time.slice(0, 5)}</small></span>
                <div className={styles.transactionActions}>
                  <strong className={amountClass}>{isOutflow ? '-' : '+'}{money(transaction.amount)}</strong>
                  {transaction.source === 'manual' && (isExpense || transaction.transactionType === 'income') && <button
                    type="button"
                    className={styles.transactionEditButton}
                    onClick={() => editTransaction(transaction)}
                    aria-label={`编辑流水：${transaction.note}`}
                    title="编辑流水"
                  ><PencilSimpleIcon size={12} /></button>}
                </div>
              </div>;
            })}</div> : <div className={styles.emptyState}><WalletIcon size={20} /><strong>还没有流水记录</strong><p>{overview?.accounts.length ? '点击右上角“记一笔”开始记录。' : '先添加资产账户，再记录收入或支出。'}</p>{!overview?.accounts.length && <Button type="button" variant="outline" size="sm" onClick={openAccountModal}>添加第一个账户</Button>}</div>}
          </section>

          <aside className={styles.accountPanel}>
            <section>
              <div className={styles.sectionHeading}><div><h2>我的账户</h2><p>切换查看各账户余额</p></div><span>{overview.accounts.length} 个账户</span></div>
              {overview?.accounts.length ? <AssetAccountDeck accounts={overview.accounts} /> : <p className={styles.emptyText}>暂无账户</p>}
              {overview?.accounts.some((account) => account.interestEnabled) && <p className={styles.yieldNote}><SparkleIcon size={13} />生息账户按年化率逐日计提，收益自动计入收入。</p>}
            </section>
          </aside>
        </div>}
    </div></div>

    <TransactionModal
      open={isTransactionModalOpen}
      accounts={overview?.accounts ?? []}
      categories={categoryOptions}
      categoriesError={categoriesError}
      categoriesLoading={isCategoriesLoading}
      onRetryCategories={() => void loadCategories()}
      transaction={editingTransaction}
      onClose={closeTransactionModal}
      onSubmit={submitTransaction}
    />

    <TransferModal
      open={isTransferModalOpen}
      accounts={overview?.accounts ?? []}
      onClose={() => setIsTransferModalOpen(false)}
      onSubmit={submitTransfer}
    />

    <Modal open={isAccountModalOpen} title="添加资产账户" onClose={closeAccountModal} width={500} centered>
      <form className={styles.accountForm} onSubmit={submitAccount}>
        <p className={styles.modalDescription}>记录账户当前余额；零钱通、余额宝等可开启每日收益。</p>
        {accountError && <div className={styles.modalError} role="alert">{accountError}</div>}
        <label><span>账户名称</span><input name="name" required autoFocus placeholder="例如：微信零钱通" /></label>
        <fieldset className={styles.accountTypeField}><legend>账户类型</legend><div className={styles.accountTypeGrid}>
          {ACCOUNT_TYPES.map((type) => <label key={type.value} className={accountType === type.value ? styles.accountTypeActive : ''}>
            <input type="radio" name="accountType" value={type.value} checked={accountType === type.value} onChange={() => {
              setAccountType(type.value);
              setInterestEnabled(type.interest);
            }} />
            <AccountTypeIcon type={type.value} size={20} variant="tile" /><b>{type.label}</b>
          </label>)}
        </div></fieldset>
        <label><span>当前余额</span><input name="initialBalance" type="number" min="0" step="0.01" defaultValue="0" required /></label>
        <label className={styles.checkField}><input type="checkbox" checked={interestEnabled} disabled={isYieldAccountType}
          onChange={(event) => setInterestEnabled(event.target.checked)} /><span>{isYieldAccountType ? '该账户默认自动计算每日收益' : '这是生息账户，自动计算每日收益'}</span></label>
        {interestEnabled && <label><span>当前年化收益率（%）</span><input name="annualRatePercent" type="number" min="0.000001" max="100" step="0.000001" placeholder="例如：1.85" required /></label>}
        <div className={styles.modalActions}><Button type="button" variant="outline" onClick={closeAccountModal} disabled={isSaving}>取消</Button><Button type="submit" variant="primary" disabled={isSaving}>{isSaving ? '保存中…' : '添加账户'}</Button></div>
      </form>
    </Modal>

    <TransactionDrawer
      open={isTransactionDrawerOpen}
      transactions={allTransactions}
      accounts={overview?.accounts ?? []}
      loading={isTransactionsLoading}
      error={transactionsError}
      onClose={() => setIsTransactionDrawerOpen(false)}
      onRetry={() => void loadAllTransactions()}
      onEdit={editTransaction}
    />

    <AssetDetailModal
      open={isAssetDetailModalOpen}
      accounts={overview?.accounts ?? []}
      totalAssets={overview?.totalAssets ?? 0}
      onClose={() => setIsAssetDetailModalOpen(false)}
      onAddAccount={openAccountModal}
      onAdjustAccount={openBalanceAdjustment}
    />

    <AssetAdjustmentModal
      open={adjustmentAccount !== null}
      account={adjustmentAccount}
      onClose={() => setAdjustmentAccount(null)}
      onSubmit={submitBalanceAdjustment}
    />
  </main>;
};

export default FinancePage;
