import { TopBarAction } from '../common/TopBarAction';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowClockwiseIcon, ArrowsLeftRightIcon, CaretRightIcon, PencilSimpleIcon, PlusIcon, SparkleIcon, WalletIcon } from '@phosphor-icons/react';
import { createFinanceAccount, createFinanceBalanceAdjustment, createFinanceTransaction, createFinanceTransfer, fetchFinanceCategories, fetchFinanceExpenseChart, fetchFinanceExpenseInsights, fetchFinanceOverview, fetchFinanceTransactions, updateFinanceTransaction } from '../../services/financeApi';
import type { CreateFinanceAccountInput, CreateFinanceBalanceAdjustmentInput, CreateFinanceTransactionInput, CreateFinanceTransferInput, FinanceAccount, FinanceCategoryOptions, FinanceChartRange, FinanceExpenseChart, FinanceExpenseInsights, FinanceOverview, FinanceTransaction } from '../../types/finance';
import { Button } from '../common/Button';
import { Skeleton } from '../common/Skeleton';
import { useMessage } from '../common/Message';
import { TopBar } from '../common/TopBar';
import styles from './FinancePage.module.css';
import { AssetAccountDeck } from './AssetAccountDeck';
import { AssetAccountModal } from './AssetAccountModal';
import { AssetAdjustmentModal } from './AssetAdjustmentModal';
import { SpendingTrendChart } from './SpendingTrendChart';
import { TransactionTypeIcon } from './TransactionTypeIcon';
import { TransactionDrawer } from './TransactionDrawer';
import { TransactionModal } from './TransactionModal';
import { TransferModal } from './TransferModal';
import { AssetDetailModal } from './AssetDetailModal';
import { FinanceSummary } from './FinanceSummary';
import { ExpenseInsightsPanel } from './ExpenseInsightsPanel';

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
  const [insights, setInsights] = useState<FinanceExpenseInsights | null>(null);
  const [insightsRange, setInsightsRange] = useState<FinanceChartRange>('month');
  const [isInsightsLoading, setIsInsightsLoading] = useState(true);
  const [insightsError, setInsightsError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [overviewError, setOverviewError] = useState<string | null>(null);
  const [categoriesError, setCategoriesError] = useState<string | null>(null);
  const [isCategoriesLoading, setIsCategoriesLoading] = useState(true);
  const [isChartLoading, setIsChartLoading] = useState(true);
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
  const chartRequestId = useRef(0);
  const insightsRequestId = useRef(0);
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

  const loadInsights = useCallback(async (range: FinanceChartRange) => {
    const requestId = ++insightsRequestId.current;
    setIsInsightsLoading(true);
    setInsightsError(null);
    try {
      const nextInsights = await fetchFinanceExpenseInsights(range);
      if (requestId === insightsRequestId.current) setInsights(nextInsights);
    } catch (cause) {
      if (requestId === insightsRequestId.current) setInsightsError(cause instanceof Error ? cause.message : '支出分析加载失败，请稍后重试。');
    } finally {
      if (requestId === insightsRequestId.current) setIsInsightsLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { void loadCategories(); }, [loadCategories]);
  useEffect(() => { void loadChart(chartRange); }, [chartRange, loadChart]);
  useEffect(() => { void loadInsights(insightsRange); }, [insightsRange, loadInsights]);
  useEffect(() => () => {
    overviewRequestId.current += 1;
    categoriesRequestId.current += 1;
    chartRequestId.current += 1;
    insightsRequestId.current += 1;
  }, []);
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
    setIsAccountModalOpen(true);
  };

  const closeAccountModal = () => {
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

  const submitAccount = async (input: CreateFinanceAccountInput) => {
    await createFinanceAccount(input);
    await Promise.all([load(), loadInsights(insightsRange)]);
    setIsAccountModalOpen(false);
    showMessage('success', '资产账户已添加');
  };

  const submitTransaction = async (input: CreateFinanceTransactionInput) => {
    if (editingTransaction) {
      await updateFinanceTransaction(editingTransaction.id, input);
    } else {
      await createFinanceTransaction(input);
    }
    void loadCategories();
    await Promise.all([load(), loadChart(chartRange), loadInsights(insightsRange), ...(isTransactionDrawerOpen ? [loadAllTransactions()] : [])]);
    showMessage('success', editingTransaction ? '流水已更新，账户余额已同步调整' : '流水已保存');
    closeTransactionModal();
  };

  const submitTransfer = async (input: CreateFinanceTransferInput) => {
    await createFinanceTransfer(input);
    await Promise.all([load(), loadChart(chartRange), loadInsights(insightsRange), ...(isTransactionDrawerOpen ? [loadAllTransactions()] : [])]);
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
        onClick={() => void Promise.all([load(), loadCategories(), loadChart(chartRange), loadInsights(insightsRange)])} disabled={isLoading || isChartLoading || isInsightsLoading}>
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
          onRangeChange={setChartRange} onRetry={() => void loadChart(chartRange)} categoryColors={categoryOptions.colors?.expense} />

        <ExpenseInsightsPanel insights={insights} range={insightsRange} loading={isInsightsLoading} error={insightsError}
          onRangeChange={setInsightsRange} onRetry={() => void loadInsights(insightsRange)} categoryColors={categoryOptions.colors?.expense} />

        {!overview && isLoading && <div className={styles.financeLayout}>
          <section className={styles.historyPanel} aria-label="最近流水"><h2>最近流水</h2><Skeleton label="正在读取最近流水…" /></section>
          <section className={styles.accountPanel} aria-label="我的账户"><h2>我的账户</h2><Skeleton label="正在读取资产账户…" /></section>
        </div>}
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

    <AssetAccountModal
      open={isAccountModalOpen}
      onClose={closeAccountModal}
      onSubmit={submitAccount}
    />

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
