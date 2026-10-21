import React, { useEffect, useState } from 'react';
import type { CreateFinanceTransactionInput, FinanceAccount, FinanceCategoryOptions, FinanceTransaction } from '../../types/finance';
import { formatLocalDate } from '../../services/dailyEvents';
import { Button } from '../common/Button';
import { CategoryPicker } from '../common/CategoryPicker';
import { mergeCategoryOptions } from '../common/categoryOptions';
import { Modal } from '../common/Modal';
import { Select } from '../common/Select';
import { EXPENSE_CATEGORIES, INCOME_CATEGORIES } from './financeCategories';
import { financeCategoryColor } from './financeCategoryColors';
import { FinanceCategoryColorPicker } from './FinanceCategoryColorPicker';
import styles from './TransactionModal.module.css';

function nowTime(): string {
  const now = new Date();
  return `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
}

function money(value: number): string {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);
}

interface TransactionModalProps {
  open: boolean;
  accounts: FinanceAccount[];
  categories: FinanceCategoryOptions;
  categoriesError?: string | null;
  categoriesLoading?: boolean;
  onRetryCategories?: () => void;
  defaultDate?: string;
  transaction?: FinanceTransaction | null;
  onClose: () => void;
  onSubmit: (input: CreateFinanceTransactionInput) => Promise<void>;
}

/** 统一的收入 / 支出记账弹框，供财务页和日历页共用。 */
export const TransactionModal: React.FC<TransactionModalProps> = ({
  open, accounts, categories, categoriesError, categoriesLoading, onRetryCategories, defaultDate, transaction, onClose, onSubmit,
}) => {
  const [entryType, setEntryType] = useState<'expense' | 'income'>('expense');
  const [category, setCategory] = useState<string>(EXPENSE_CATEGORIES[0]);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draftColors, setDraftColors] = useState<Record<string, string>>({});
  const colorKey = `${entryType}:${category.trim()}`;
  const selectedColor = draftColors[colorKey] ?? financeCategoryColor(category, entryType, categories.colors?.[entryType]);
  const availableCategories = mergeCategoryOptions(
    entryType === 'expense' ? EXPENSE_CATEGORIES : INCOME_CATEGORIES,
    categories[entryType],
  );

  useEffect(() => {
    if (!open) return;
    const nextType = transaction?.transactionType === 'income' ? 'income' : 'expense';
    setEntryType(nextType);
    setCategory(transaction?.category ?? (nextType === 'expense' ? EXPENSE_CATEGORIES[0] : INCOME_CATEGORIES[0]));
    setError(null);
    setDraftColors({});
  }, [open, transaction]);

  const closeModal = () => {
    if (isSaving) return;
    setError(null);
    onClose();
  };

  const submitTransaction = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isSaving) return;
    const form = event.currentTarget;
    const data = new FormData(form);
    setIsSaving(true);
    setError(null);
    try {
      await onSubmit({
        accountId: String(data.get('accountId')),
        transactionType: entryType,
        category: category.trim(),
        categoryColor: draftColors[colorKey],
        note: String(data.get('note') ?? '').trim(),
        amount: Number(data.get('amount')),
        date: String(data.get('date')),
        time: String(data.get('time')),
      });
      form.reset();
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '流水保存失败，请检查填写内容。');
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <Modal open={open} title={transaction ? '编辑流水' : '记一笔'} onClose={closeModal} width={600} centered busy={isSaving} busyLabel="正在保存流水并同步账户…">
      <form key={`${open}-${transaction?.id ?? defaultDate ?? 'today'}`} className={styles.transactionForm} onSubmit={(event) => { void submitTransaction(event); }}>
        <div className={styles.typeSwitch} aria-label="流水类型">
          <button type="button" className={entryType === 'expense' ? styles.typeActive : ''} onClick={() => { setEntryType('expense'); setCategory(EXPENSE_CATEGORIES[0]); }}>支出</button>
          <button type="button" className={entryType === 'income' ? styles.typeActive : ''} onClick={() => { setEntryType('income'); setCategory(INCOME_CATEGORIES[0]); }}>收入</button>
        </div>
        {error && <div className={styles.modalError} role="alert">{error}</div>}
        {categoriesError && <div className={styles.categoryNotice} role="alert">
          <span>历史分类加载失败，仍可使用默认分类或输入新分类。{categoriesError}</span>
          {onRetryCategories && <Button type="button" variant="ghost" size="sm" onClick={onRetryCategories} disabled={categoriesLoading || isSaving}>重试分类</Button>}
        </div>}
        <label className={styles.amountField}><span>金额</span><div><b>¥</b><input name="amount" type="number" min="0.01" step="0.01" placeholder="0.00" defaultValue={transaction?.amount} required autoFocus /></div></label>
        <div className={styles.formGrid}>
          <Select
            name="accountId"
            label="资产账户"
            options={accounts.map((account) => ({ value: account.id, label: `${account.name} · ${money(account.balance)}` }))}
            defaultValue={transaction?.accountId}
            fieldSize="md"
            fullWidth
            required
          />
          <CategoryPicker options={availableCategories} value={category} onChange={setCategory} disabled={isSaving} />
          <div className={styles.wideField}>
            <FinanceCategoryColorPicker category={category} value={selectedColor} disabled={isSaving || !category.trim()}
              onChange={(color) => setDraftColors((current) => ({ ...current, [colorKey]: color }))} />
          </div>
          <label className={styles.wideField}><span>说明</span><input name="note" defaultValue={transaction?.note} required placeholder={entryType === 'expense' ? '例如：午餐' : '例如：九月工资'} /></label>
          <label><span>日期</span><input name="date" type="date" defaultValue={transaction?.date ?? defaultDate ?? formatLocalDate(new Date())} required /></label>
          <label><span>时间</span><input name="time" type="time" defaultValue={transaction?.time.slice(0, 5) ?? nowTime()} required /></label>
        </div>
        <div className={styles.modalActions}><Button type="button" variant="outline" onClick={closeModal} disabled={isSaving}>取消</Button><Button type="submit" variant="primary" disabled={isSaving || !category.trim()}>{isSaving ? '保存中…' : transaction ? '保存修改' : `保存${entryType === 'expense' ? '支出' : '收入'}`}</Button></div>
      </form>
    </Modal>
  );
};

export default TransactionModal;
