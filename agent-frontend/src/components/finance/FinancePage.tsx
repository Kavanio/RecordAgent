import { TopBarAction } from '../common/TopBarAction';
import React, { useState } from 'react';
import { ArrowClockwiseIcon, ArrowsLeftRightIcon, CaretRightIcon, PencilSimpleIcon, PlusIcon, SparkleIcon, WalletIcon } from '@phosphor-icons/react';
import { createFinanceAccount, createFinanceBalanceAdjustment, createFinanceTransaction, createFinanceTransfer, deleteFinanceAccount, deleteFinanceTransaction, updateFinanceTransaction } from '../../services/financeApi';
import type { CreateFinanceAccountInput, CreateFinanceBalanceAdjustmentInput, CreateFinanceTransactionInput, CreateFinanceTransferInput, FinanceAccount, FinanceTransaction } from '../../types/finance';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
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
import { buildBlockedDeleteAccountDescription, buildDeleteAccountDescription, buildDeleteTransactionDescription, formatFinanceMoney, isZeroBalanceAccount } from './financeDeleteDescriptions';
import { useFinanceData } from './useFinanceData';

/** 独立财务工作台：统一完成资产建档、收支记账和自动收益查看。 */
export const FinancePage: React.FC = () => {
  const { showMessage } = useMessage();
  const {
    overview, categoryOptions, chart, chartRange, setChartRange,
    insights, insightsRange, setInsightsRange, isInsightsLoading, insightsError,
    isLoading, overviewError, categoriesError, isCategoriesLoading,
    isChartLoading, chartError, allTransactions, isTransactionsLoading, transactionsError,
    load, loadCategories, loadChart, loadInsights, loadAllTransactions,
  } = useFinanceData();
  const [isAccountModalOpen, setIsAccountModalOpen] = useState(false);
  const [isTransactionModalOpen, setIsTransactionModalOpen] = useState(false);
  const [editingTransaction, setEditingTransaction] = useState<FinanceTransaction | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<FinanceTransaction | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [isTransferModalOpen, setIsTransferModalOpen] = useState(false);
  const [isTransactionDrawerOpen, setIsTransactionDrawerOpen] = useState(false);
  const [isAssetDetailModalOpen, setIsAssetDetailModalOpen] = useState(false);
  const [adjustmentAccount, setAdjustmentAccount] = useState<FinanceAccount | null>(null);
  const [deleteAccountTarget, setDeleteAccountTarget] = useState<FinanceAccount | null>(null);
  const [blockedAccountTarget, setBlockedAccountTarget] = useState<FinanceAccount | null>(null);
  const [isDeletingAccount, setIsDeletingAccount] = useState(false);
  const recentTransactions = overview?.transactions.slice(0, 5) ?? [];

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

  const confirmDeleteTransaction = async () => {
    if (!deleteTarget || isDeleting) return;
    setIsDeleting(true);
    try {
      await deleteFinanceTransaction(deleteTarget.id);
      await Promise.all([load(), loadChart(chartRange), loadInsights(insightsRange), loadAllTransactions()]);
      showMessage('success', '流水已删除，账户余额已同步回滚');
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '流水删除失败，请稍后重试。');
    } finally {
      setIsDeleting(false);
      setDeleteTarget(null);
    }
  };

  const requestDeleteAccount = (account: FinanceAccount) => {
    if (!isZeroBalanceAccount(account)) {
      setBlockedAccountTarget(account);
      return;
    }
    setDeleteAccountTarget(account);
  };

  const confirmDeleteAccount = async () => {
    if (!deleteAccountTarget || isDeletingAccount) return;
    setIsDeletingAccount(true);
    try {
      await deleteFinanceAccount(deleteAccountTarget.id);
      await Promise.all([
        load(),
        loadInsights(insightsRange),
        ...(isTransactionDrawerOpen ? [loadAllTransactions()] : []),
      ]);
      showMessage('success', '资产账户已删除，历史流水与统计已保留');
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '资产账户删除失败，请稍后重试。');
    } finally {
      setIsDeletingAccount(false);
      setDeleteAccountTarget(null);
    }
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
                  <strong className={amountClass}>{isOutflow ? '-' : '+'}{formatFinanceMoney(transaction.amount)}</strong>
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
      onDelete={(transaction) => setDeleteTarget(transaction)}
    />

    <ConfirmDialog
      open={deleteTarget !== null}
      title="确认删除流水"
      tone="danger"
      confirmText="删除流水"
      confirmVariant="danger"
      busy={isDeleting}
      description={deleteTarget ? buildDeleteTransactionDescription(deleteTarget, allTransactions) : ''}
      onClose={() => { if (!isDeleting) setDeleteTarget(null); }}
      onConfirm={() => void confirmDeleteTransaction()}
    />

    <ConfirmDialog
      open={deleteAccountTarget !== null}
      title="确认删除资产账户"
      tone="danger"
      confirmText="删除账户"
      confirmVariant="danger"
      busy={isDeletingAccount}
      description={deleteAccountTarget ? buildDeleteAccountDescription(deleteAccountTarget) : ''}
      onClose={() => { if (!isDeletingAccount) setDeleteAccountTarget(null); }}
      onConfirm={() => void confirmDeleteAccount()}
    />

    <ConfirmDialog
      open={blockedAccountTarget !== null}
      title="余额不为 0，暂不可删除"
      tone="warning"
      cancelText="知道了"
      confirmText="去调整余额"
      description={blockedAccountTarget ? buildBlockedDeleteAccountDescription(blockedAccountTarget) : ''}
      onClose={() => setBlockedAccountTarget(null)}
      onConfirm={() => {
        if (!blockedAccountTarget) return;
        const target = blockedAccountTarget;
        setBlockedAccountTarget(null);
        openBalanceAdjustment(target);
      }}
    />

    <AssetDetailModal
      open={isAssetDetailModalOpen}
      accounts={overview?.accounts ?? []}
      totalAssets={overview?.totalAssets ?? 0}
      onClose={() => setIsAssetDetailModalOpen(false)}
      onAddAccount={openAccountModal}
      onAdjustAccount={openBalanceAdjustment}
      onDeleteAccount={requestDeleteAccount}
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
