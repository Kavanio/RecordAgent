import React, { useEffect, useState } from 'react';
import type { CreateFinanceBalanceAdjustmentInput, FinanceAccount } from '../../types/finance';
import { Button } from '../common/Button';
import { Modal } from '../common/Modal';
import styles from './AssetAdjustmentModal.module.css';

interface AssetAdjustmentModalProps {
  open: boolean;
  account: FinanceAccount | null;
  onClose: () => void;
  onSubmit: (input: CreateFinanceBalanceAdjustmentInput) => Promise<void>;
}

const money = (value: number, currency: string) => new Intl.NumberFormat('zh-CN', {
  style: 'currency', currency,
}).format(value);

/** 单账户余额校准表单；该操作生成审计流水，但不代表真实收入或支出。 */
export const AssetAdjustmentModal: React.FC<AssetAdjustmentModalProps> = ({
  open, account, onClose, onSubmit,
}) => {
  const [direction, setDirection] = useState<'increase' | 'decrease'>('increase');
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setDirection('increase');
    setError(null);
  }, [open, account]);

  const closeModal = () => {
    if (isSaving) return;
    onClose();
  };

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isSaving) return;
    const form = event.currentTarget;
    const data = new FormData(form);
    setIsSaving(true);
    setError(null);
    try {
      await onSubmit({
        direction,
        amount: Number(data.get('amount')),
        note: String(data.get('note') ?? '').trim(),
      });
      form.reset();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '资产余额调整失败，请检查填写内容。');
    } finally {
      setIsSaving(false);
    }
  };

  return <Modal open={open} title="调整资产余额" onClose={closeModal} width={500} centered busy={isSaving} busyLabel="正在校准资产余额…">
    <form key={account?.id ?? 'none'} className={styles.form} onSubmit={(event) => { void submit(event); }}>
      <div className={styles.warning}>
        <strong>余额校准不代表真实收支</strong>
        <p>系统会生成特别标注的校准流水用于追溯，但不会计入收入、支出和趋势统计。</p>
      </div>
      {error && <div className={styles.error} role="alert">{error}</div>}
      <div className={styles.accountSummary}>
        <span>{account?.name ?? '资产账户'}</span>
        <strong>{account ? money(account.balance, account.currency) : '—'}</strong>
      </div>
      <div className={styles.directionSwitch} aria-label="余额调整方向">
        <button type="button" className={direction === 'increase' ? styles.active : ''} onClick={() => setDirection('increase')}>手动增加</button>
        <button type="button" className={direction === 'decrease' ? styles.active : ''} onClick={() => setDirection('decrease')}>手动减少</button>
      </div>
      <label className={styles.amountField}>
        <span>调整金额</span>
        <div><b>¥</b><input name="amount" type="number" min="0.01" step="0.01" placeholder="0.00" required autoFocus /></div>
      </label>
      <label>
        <span>备注信息</span>
        <textarea name="note" maxLength={100} placeholder="请说明调整原因，例如：账户对账补差" required />
      </label>
      <div className={styles.actions}>
        <Button type="button" variant="outline" onClick={closeModal} disabled={isSaving}>取消</Button>
        <Button type="submit" variant="primary" disabled={isSaving || !account}>{isSaving ? '保存中…' : '确认调整'}</Button>
      </div>
    </form>
  </Modal>;
};
