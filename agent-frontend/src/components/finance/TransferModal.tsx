import React, { useEffect, useMemo, useState } from 'react';
import { ArrowsLeftRightIcon } from '@phosphor-icons/react';
import type { CreateFinanceTransferInput, FinanceAccount } from '../../types/finance';
import { formatLocalDate } from '../../services/dailyEvents';
import { Button } from '../common/Button';
import { Modal } from '../common/Modal';
import { Select } from '../common/Select';
import styles from './TransferModal.module.css';

function nowTime(): string {
  const now = new Date();
  return `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
}

function money(value: number): string {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);
}

interface TransferModalProps {
  open: boolean;
  accounts: FinanceAccount[];
  defaultDate?: string;
  onClose: () => void;
  onSubmit: (input: CreateFinanceTransferInput) => Promise<void>;
}

/** 资产划账弹窗：支持在两个资产账户之间划转资金，并实时预览变动后余额。 */
export const TransferModal: React.FC<TransferModalProps> = ({
  open,
  accounts,
  defaultDate,
  onClose,
  onSubmit,
}) => {
  const [fromAccountId, setFromAccountId] = useState('');
  const [toAccountId, setToAccountId] = useState('');
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const [date, setDate] = useState(defaultDate ?? formatLocalDate(new Date()));
  const [time, setTime] = useState(nowTime());
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 初始化选择账户：优先挑选有可用余额的账户作为转出账户
  useEffect(() => {
    if (!open || accounts.length < 2) return;
    const defaultFrom = accounts.find((acc) => acc.balance > 0) ?? accounts[0];
    const defaultTo = accounts.find((acc) => acc.id !== defaultFrom.id) ?? accounts[1];
    setFromAccountId(defaultFrom.id);
    setToAccountId(defaultTo.id);
    setAmount('');
    setNote('');
    setDate(defaultDate ?? formatLocalDate(new Date()));
    setTime(nowTime());
    setError(null);
  }, [open, accounts, defaultDate]);

  const fromAccount = useMemo(
    () => accounts.find((acc) => acc.id === fromAccountId),
    [accounts, fromAccountId],
  );

  const toAccount = useMemo(
    () => accounts.find((acc) => acc.id === toAccountId),
    [accounts, toAccountId],
  );

  const numericAmount = Number(amount) || 0;
  const fromBalanceAfter = fromAccount ? fromAccount.balance - numericAmount : 0;
  const toBalanceAfter = toAccount ? toAccount.balance + numericAmount : 0;
  const isInsufficient = Boolean(fromAccount && numericAmount > 0 && fromBalanceAfter < 0);
  const isSameAccount = Boolean(fromAccountId && toAccountId && fromAccountId === toAccountId);

  const handleSwap = () => {
    if (fromAccountId && toAccountId) {
      setFromAccountId(toAccountId);
      setToAccountId(fromAccountId);
    }
  };

  const closeModal = () => {
    if (isSaving) return;
    setError(null);
    onClose();
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isSaving) return;
    setError(null);

    if (isSameAccount) {
      setError('转出账户与转入账户不能相同');
      return;
    }
    if (numericAmount <= 0) {
      setError('划账金额必须大于 0');
      return;
    }
    if (isInsufficient) {
      setError('转出账户余额不足，无法划账');
      return;
    }

    setIsSaving(true);
    try {
      await onSubmit({
        fromAccountId,
        toAccountId,
        amount: numericAmount,
        note: note.trim(),
        date,
        time,
      });
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '划账保存失败，请检查填写内容。');
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <Modal open={open} title="资产划账" onClose={closeModal} width={560} centered busy={isSaving} busyLabel="正在划账并同步余额…">
      <form className={styles.transferForm} onSubmit={(e) => { void handleSubmit(e); }}>
        {error && <div className={styles.modalError} role="alert">{error}</div>}

        <label className={styles.amountField}>
          <span>划账金额</span>
          <div>
            <b>¥</b>
            <input
              name="amount"
              type="number"
              min="0.01"
              step="0.01"
              placeholder="0.00"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              required
              autoFocus
            />
          </div>
        </label>

        {/* 账户选择联动 */}
        <div className={styles.accountStage}>
          <Select
            name="fromAccountId"
            label="转出账户"
            value={fromAccountId}
            onChange={(e) => setFromAccountId(e.target.value)}
            options={accounts.map((acc) => ({
              value: acc.id,
              label: `${acc.name} (${money(acc.balance)})`,
            }))}
            fieldSize="md"
            fullWidth
            required
          />

          <button
            type="button"
            className={styles.swapButton}
            title="对调转出与转入账户"
            aria-label="对调转出与转入账户"
            onClick={handleSwap}
            disabled={isSaving}
          >
            <ArrowsLeftRightIcon size={14} weight="bold" />
          </button>

          <Select
            name="toAccountId"
            label="转入账户"
            value={toAccountId}
            onChange={(e) => setToAccountId(e.target.value)}
            options={accounts.map((acc) => ({
              value: acc.id,
              label: `${acc.name} (${money(acc.balance)})`,
            }))}
            fieldSize="md"
            fullWidth
            required
          />
        </div>

        {/* 划转后余额变动预览卡片 */}
        {fromAccount && toAccount && (
          <div className={styles.previewCard} aria-live="polite">
            <div className={styles.previewItem}>
              <span>{fromAccount.name} 预计余额</span>
              <strong className={isInsufficient ? styles.overdrawWarning : ''}>
                {money(fromBalanceAfter)}
                {isInsufficient ? ' (余额不足)' : ''}
              </strong>
            </div>
            <span className={styles.previewArrow}>→</span>
            <div className={styles.previewItem}>
              <span>{toAccount.name} 预计余额</span>
              <strong>{money(toBalanceAfter)}</strong>
            </div>
          </div>
        )}

        <div className={styles.formGrid}>
          <label className={styles.wideField}>
            <span>说明 (可选)</span>
            <input
              name="note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder={fromAccount && toAccount ? `例如：划账至 ${toAccount.name}` : '例如：日常理财充值'}
            />
          </label>
          <label>
            <span>日期</span>
            <input
              name="date"
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              required
            />
          </label>
          <label>
            <span>时间</span>
            <input
              name="time"
              type="time"
              value={time}
              onChange={(e) => setTime(e.target.value)}
              required
            />
          </label>
        </div>

        <div className={styles.modalActions}>
          <Button type="button" variant="outline" onClick={closeModal} disabled={isSaving}>
            取消
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={isSaving || isInsufficient || isSameAccount || numericAmount <= 0}
          >
            {isSaving ? '划账中…' : '确认划账'}
          </Button>
        </div>
      </form>
    </Modal>
  );
};

export default TransferModal;
