import React, { useMemo, useState } from 'react';
import { CalendarDotsIcon, MagnifyingGlassIcon, PencilSimpleIcon, TrashIcon, WalletIcon } from '@phosphor-icons/react';
import type { FinanceAccount, FinanceTransaction } from '../../types/finance';
import { Drawer } from '../common/Drawer';
import { Select } from '../common/Select';
import { Button } from '../common/Button';
import { Skeleton } from '../common/Skeleton';
import { TransactionTypeIcon } from './TransactionTypeIcon';
import styles from './TransactionDrawer.module.css';

interface TransactionDrawerProps {
  open: boolean;
  transactions: FinanceTransaction[];
  accounts?: FinanceAccount[];
  loading: boolean;
  error: string | null;
  onClose: () => void;
  onRetry: () => void;
  onEdit: (transaction: FinanceTransaction) => void;
  /** 请求删除；仅在可安全回滚余额的流水上展示入口，自动收益不提供删除。 */
  onDelete: (transaction: FinanceTransaction) => void;
}

/** 自动收益由系统按日计提且无法重算，与不可回滚来源一起不提供删除入口。 */
const isDeletable = (transaction: FinanceTransaction) => transaction.transactionType !== 'yield'
  && transaction.source !== 'automatic';

const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);

/** 全部流水抽屉：支持按支付分类、不同资产支付、交易类型、日期和关键词多维筛选。 */
export const TransactionDrawer: React.FC<TransactionDrawerProps> = ({
  open, transactions, accounts, loading, error, onClose, onRetry, onEdit, onDelete,
}) => {
  const [selectedCategory, setSelectedCategory] = useState('');
  const [selectedAccountId, setSelectedAccountId] = useState('');
  const [selectedType, setSelectedType] = useState<string>('all');
  const [date, setDate] = useState('');
  const [keyword, setKeyword] = useState('');

  // 提取所有可用分类选项
  const availableCategories = useMemo(() => {
    const set = new Set<string>();
    transactions.forEach((t) => {
      if (t.category) set.add(t.category);
    });
    return Array.from(set).sort();
  }, [transactions]);

  // 提取所有可用资产支付账户选项
  const availableAccounts = useMemo(() => {
    const map = new Map<string, string>();
    if (accounts) {
      accounts.forEach((acc) => map.set(acc.id, acc.name));
    }
    transactions.forEach((t) => {
      if (t.accountId && t.accountName) map.set(t.accountId, t.accountName);
    });
    return Array.from(map.entries()).map(([id, name]) => ({ id, name }));
  }, [accounts, transactions]);

  const hasActiveFilter = Boolean(date || keyword || selectedCategory || selectedAccountId || (selectedType && selectedType !== 'all'));

  const resetFilters = () => {
    setSelectedCategory('');
    setSelectedAccountId('');
    setSelectedType('all');
    setDate('');
    setKeyword('');
  };

  const filtered = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLocaleLowerCase('zh-CN');
    return transactions.filter((transaction) => {
      if (date && transaction.date !== date) return false;
      if (selectedCategory && transaction.category !== selectedCategory) return false;
      if (selectedAccountId && transaction.accountId !== selectedAccountId && transaction.accountName !== selectedAccountId) return false;
      if (selectedType && selectedType !== 'all') {
        if (selectedType === 'transfer') {
          if (transaction.transactionType !== 'transfer_out' && transaction.transactionType !== 'transfer_in') return false;
        } else if (selectedType === 'adjustment') {
          if (transaction.transactionType !== 'adjustment_increase' && transaction.transactionType !== 'adjustment_decrease') return false;
        } else if (transaction.transactionType !== selectedType) {
          return false;
        }
      }
      if (normalizedKeyword && !transaction.note.toLocaleLowerCase('zh-CN').includes(normalizedKeyword)) return false;
      return true;
    });
  }, [date, keyword, selectedCategory, selectedAccountId, selectedType, transactions]);

  return <Drawer
    open={open}
    title="全部流水"
    description={loading ? '正在读取流水…' : `共 ${transactions.length} 笔${hasActiveFilter ? `，筛选出 ${filtered.length} 笔` : ''}`}
    onClose={onClose}
  >
    <div className={styles.filters} role="search" aria-label="筛选流水">
      <div className={styles.filterRow}>
        <Select
          label="支付分类"
          value={selectedCategory}
          onChange={(e) => setSelectedCategory(e.target.value)}
          aria-label="按支付分类筛选"
          fieldSize="sm"
          fullWidth
          options={[
            { value: '', label: '全部分类' },
            ...availableCategories.map((cat) => ({ value: cat, label: cat })),
          ]}
        />
        <Select
          label="资产支付"
          value={selectedAccountId}
          onChange={(e) => setSelectedAccountId(e.target.value)}
          aria-label="按资产支付账户筛选"
          fieldSize="sm"
          fullWidth
          options={[
            { value: '', label: '全部资产账户' },
            ...availableAccounts.map((acc) => ({ value: acc.id, label: acc.name })),
          ]}
        />
        <Select
          label="类型"
          value={selectedType}
          onChange={(e) => setSelectedType(e.target.value)}
          aria-label="按收支类型筛选"
          fieldSize="sm"
          fullWidth
          options={[
            { value: 'all', label: '全部' },
            { value: 'expense', label: '支出' },
            { value: 'income', label: '收入' },
            { value: 'yield', label: '收益' },
            { value: 'transfer', label: '划账' },
            { value: 'adjustment', label: '余额校准' },
          ]}
        />
      </div>

      <div className={styles.filterRowBottom}>
        <label><span>日期</span><input type="date" value={date} onChange={(event) => setDate(event.target.value)} /></label>
        <label className={styles.searchField}><span>说明搜索</span><div><MagnifyingGlassIcon size={14} aria-hidden="true" /><input type="search" value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索流水说明" /></div></label>
        {hasActiveFilter && <Button variant="ghost" size="sm" onClick={resetFilters}>清除筛选</Button>}
      </div>
    </div>

    <div className={styles.list} aria-live="polite">
      {loading ? <Skeleton label="正在读取全部流水…" />
        : error ? <div className={styles.state}><strong>流水加载失败</strong><p>{error}</p><Button variant="outline" size="sm" onClick={onRetry}>重新加载</Button></div>
          : filtered.length ? filtered.map((transaction) => {
            const isExpense = transaction.transactionType === 'expense';
            const isTransferOut = transaction.transactionType === 'transfer_out';
            const isTransferIn = transaction.transactionType === 'transfer_in';
            const isAdjustment = transaction.transactionType === 'adjustment_increase'
              || transaction.transactionType === 'adjustment_decrease';
            const isOutflow = isExpense || isTransferOut || transaction.transactionType === 'adjustment_decrease';
            const amountClass = isAdjustment
              ? styles.adjustmentAmount
              : isExpense
              ? styles.expenseAmount
              : isTransferOut
              ? styles.transferOutAmount
              : isTransferIn
              ? styles.transferInAmount
              : styles.incomeAmount;
            return <article className={styles.row} key={transaction.id}>
              <TransactionTypeIcon type={transaction.transactionType} category={transaction.category} />
              <div className={styles.content}>
                <div className={styles.primary}>
                  <strong>{transaction.note}</strong>
                  <div className={styles.primaryActions}>
                    <b className={amountClass}>{isOutflow ? '-' : '+'}{money(transaction.amount)}</b>
                    {transaction.source === 'manual' && (isExpense || transaction.transactionType === 'income') && <button
                      type="button"
                      className={styles.editButton}
                      onClick={() => onEdit(transaction)}
                      aria-label={`编辑流水：${transaction.note}`}
                      title="编辑流水"
                    ><PencilSimpleIcon size={13} /></button>}
                    {isDeletable(transaction) && <button
                      type="button"
                      className={`${styles.editButton} ${styles.deleteButton}`}
                      onClick={() => onDelete(transaction)}
                      aria-label={`删除流水：${transaction.note}`}
                      title="删除流水"
                    ><TrashIcon size={13} /></button>}
                  </div>
                </div>
                <div className={styles.meta}>
                  <span><CalendarDotsIcon size={12} />{transaction.date} {transaction.time.slice(0, 5)}</span>
                  <span><WalletIcon size={12} />{transaction.accountName}</span>
                  <span>{transaction.category}</span>
                  <span>{transaction.source === 'calendar'
                    ? '日历记录'
                    : transaction.source === 'automatic'
                    ? '自动收益'
                    : transaction.source === 'adjustment'
                    ? '余额校准（非收支）'
                    : '手工记录'}</span>
                </div>
              </div>
            </article>;
          }) : <div className={styles.state}><strong>没有匹配的流水</strong><p>请调整分类、支付资产或关键词筛选条件。</p></div>}
    </div>
  </Drawer>;
};
