import React, { useState } from 'react';
import type { CreateFinanceAccountInput, FinanceAccountType } from '../../types/finance';
import { Button } from '../common/Button';
import { FormField } from '../common/FormField';
import { Modal } from '../common/Modal';
import { TextInput } from '../common/TextInput';
import { AccountTypeIcon } from './AccountTypeIcon';
import styles from './AssetAccountModal.module.css';

const ACCOUNT_TYPES: Array<{ value: FinanceAccountType; label: string; interest: boolean }> = [
  { value: 'wechat_balance', label: '微信余额', interest: false },
  { value: 'wechat_yield', label: '微信零钱通', interest: true },
  { value: 'alipay_balance', label: '支付宝余额', interest: false },
  { value: 'alipay_yuebao', label: '支付宝余额宝', interest: true },
  { value: 'bank', label: '银行卡', interest: false },
  { value: 'other', label: '其他', interest: false },
];

interface AssetAccountModalProps {
  open: boolean;
  onClose: () => void;
  onSubmit: (input: CreateFinanceAccountInput) => Promise<void>;
}

/** 独立的新增资产账户弹窗组件，统一使用 FormField 与 TextInput。 */
export const AssetAccountModal: React.FC<AssetAccountModalProps> = ({
  open,
  onClose,
  onSubmit,
}) => {
  const [accountType, setAccountType] = useState<FinanceAccountType>('wechat_balance');
  const [interestEnabled, setInterestEnabled] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isYieldAccountType = accountType === 'wechat_yield' || accountType === 'alipay_yuebao';

  const handleClose = () => {
    if (isSaving) return;
    setError(null);
    onClose();
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const name = String(data.get('name') ?? '').trim();
    const initialBalance = Number(data.get('initialBalance'));
    const annualRatePercent = interestEnabled ? Number(data.get('annualRatePercent')) : 0;

    setIsSaving(true);
    setError(null);
    try {
      await onSubmit({
        name,
        accountType,
        currency: 'CNY',
        initialBalance,
        interestEnabled,
        annualRatePercent,
      });
      form.reset();
      setInterestEnabled(false);
      setAccountType('wechat_balance');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '账户保存失败，请检查填写内容。');
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <Modal open={open} title="添加资产账户" onClose={handleClose} width={500} centered>
      <form className={styles.form} onSubmit={(e) => void handleSubmit(e)}>
        <p className={styles.description}>记录账户当前余额；零钱通、余额宝等可开启每日收益。</p>
        {error && <div className={styles.error} role="alert">{error}</div>}

        <FormField label="账户名称" htmlFor="account-name" required>
          <TextInput
            id="account-name"
            name="name"
            required
            autoFocus
            placeholder="例如：微信零钱通"
            disabled={isSaving}
          />
        </FormField>

        <fieldset className={styles.typeField}>
          <legend>账户类型</legend>
          <div className={styles.typeGrid}>
            {ACCOUNT_TYPES.map((type) => (
              <label
                key={type.value}
                className={accountType === type.value ? styles.typeActive : ''}
              >
                <input
                  type="radio"
                  name="accountType"
                  value={type.value}
                  checked={accountType === type.value}
                  disabled={isSaving}
                  onChange={() => {
                    setAccountType(type.value);
                    setInterestEnabled(type.interest);
                  }}
                />
                <AccountTypeIcon type={type.value} size={20} variant="tile" />
                <b>{type.label}</b>
              </label>
            ))}
          </div>
        </fieldset>

        <FormField label="当前余额" htmlFor="account-balance" required>
          <TextInput
            id="account-balance"
            name="initialBalance"
            type="number"
            min="0"
            step="0.01"
            defaultValue="0"
            required
            disabled={isSaving}
          />
        </FormField>

        <div className={styles.switchBox}>
          <span>{isYieldAccountType ? '该账户默认自动计算每日收益' : '这是生息账户，自动计算每日收益'}</span>
          <input
            type="checkbox"
            checked={interestEnabled}
            disabled={isYieldAccountType || isSaving}
            onChange={(event) => setInterestEnabled(event.target.checked)}
            aria-label="开启每日收益"
          />
        </div>

        {interestEnabled && (
          <FormField label="当前年化收益率（%）" htmlFor="annual-rate" required>
            <TextInput
              id="annual-rate"
              name="annualRatePercent"
              type="number"
              min="0.000001"
              max="100"
              step="0.000001"
              placeholder="例如：1.85"
              required
              disabled={isSaving}
            />
          </FormField>
        )}

        <div className={styles.actions}>
          <Button type="button" variant="outline" onClick={handleClose} disabled={isSaving}>
            取消
          </Button>
          <Button type="submit" variant="primary" disabled={isSaving}>
            {isSaving ? '保存中…' : '添加账户'}
          </Button>
        </div>
      </form>
    </Modal>
  );
};
