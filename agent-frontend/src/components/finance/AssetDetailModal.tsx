import React from 'react';
import { PlusIcon, SlidersHorizontalIcon, TrashIcon } from '@phosphor-icons/react';
import type { FinanceAccount, FinanceAccountType } from '../../types/finance';
import { Button } from '../common/Button';
import { Modal } from '../common/Modal';
import { AccountTypeIcon } from './AccountTypeIcon';
import { formatFinanceMoney, isZeroBalanceAccount } from './financeDeleteDescriptions';
import styles from './AssetDetailModal.module.css';

const ACCOUNT_LABELS: Record<FinanceAccountType, string> = {
  wechat_balance: '微信余额',
  wechat_yield: '微信零钱通',
  alipay_balance: '支付宝余额',
  alipay_yuebao: '支付宝余额宝',
  bank: '银行卡',
  other: '其他',
  wechat: '微信',
  alipay: '支付宝',
  cash: '现金',
};

interface AssetDetailModalProps {
  open: boolean;
  accounts: FinanceAccount[];
  totalAssets: number;
  onClose: () => void;
  onAddAccount?: () => void;
  onAdjustAccount?: (account: FinanceAccount) => void;
  onDeleteAccount?: (account: FinanceAccount) => void;
}

function accountToneClass(type: FinanceAccountType): string {
  if (type.startsWith('wechat')) return styles.wechatTone;
  if (type.startsWith('alipay')) return styles.alipayTone;
  if (type === 'bank') return styles.bankTone;
  return styles.otherTone;
}

/** 全部资产概览：极简无边框、无卡片块分区、纯净排版呈现。 */
export const AssetDetailModal: React.FC<AssetDetailModalProps> = ({
  open,
  accounts,
  totalAssets,
  onClose,
  onAddAccount,
  onAdjustAccount,
  onDeleteAccount,
}) => {
  const yieldCount = accounts.filter((item) => item.interestEnabled).length;

  return (
    <Modal open={open} title="全部资产概览" onClose={onClose} width={460} centered>
      <div className={styles.container}>
        {/* 顶部净资产大字与副信息，无背景块与边框 */}
        <div className={styles.headline}>
          <div className={styles.headlineAmountWrap}>
            <span className={styles.currencySign}>¥</span>
            <strong className={styles.headlineAmount}>
              {new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(totalAssets)}
            </strong>
          </div>
          <span className={styles.headlineMeta}>
            共 {accounts.length} 个账户
            {yieldCount > 0 ? ` · ${yieldCount} 个生息账户` : ''}
          </span>
        </div>

        {/* 账户明细列表：平铺流式排版，无边框、无卡片、无进度条 */}
        <div className={styles.list}>
          {accounts.length === 0 ? (
            <p className={styles.emptyList}>暂无资产账户，点击左下角“添加账户”新建。</p>
          ) : accounts.map((account) => {
            const ratio = totalAssets > 0 ? (account.balance / totalAssets) * 100 : 0;
            const isZeroBalance = isZeroBalanceAccount(account);
            return (
              <div key={account.id} className={styles.row}>
                <div className={styles.left}>
                  <div className={`${styles.iconWrap} ${accountToneClass(account.accountType)}`}>
                    <AccountTypeIcon type={account.accountType} size={16} />
                  </div>
                  <div className={styles.nameBlock}>
                    <span className={styles.accountName} title={account.name}>{account.name}</span>
                    <div className={styles.descLine}>
                      <span className={styles.accountDesc}>{ACCOUNT_LABELS[account.accountType] ?? '账户'}</span>
                      {account.interestEnabled && (
                        <span className={styles.yieldBadge}>
                          年化 {account.annualRatePercent}%
                        </span>
                      )}
                    </div>
                  </div>
                </div>

                <div className={styles.right}>
                  <strong className={styles.accountAmount}>{formatFinanceMoney(account.balance, account.currency)}</strong>
                  <div className={styles.accountMeta}>
                    <span className={styles.accountRatio}>{ratio.toFixed(1)}%</span>
                    {onAdjustAccount && <button
                      type="button"
                      className={styles.adjustBtn}
                      onClick={() => onAdjustAccount(account)}
                      aria-label={`调整${account.name}余额`}
                    ><SlidersHorizontalIcon size={11} />调整</button>}
                    {onDeleteAccount && <button
                      type="button"
                      className={`${styles.adjustBtn} ${styles.deleteBtn}`}
                      onClick={() => onDeleteAccount(account)}
                      aria-label={`删除${account.name}`}
                      title={isZeroBalance ? `删除账户：${account.name}` : '余额不为 0，请先清零后再删除'}
                    ><TrashIcon size={11} />删除</button>}
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {/* 底部操作区：无分割线 */}
        <div className={styles.footer}>
          {onAddAccount ? (
            <button
              type="button"
              className={styles.addBtn}
              onClick={() => {
                onClose();
                onAddAccount();
              }}
            >
              <PlusIcon size={12} weight="bold" />
              添加账户
            </button>
          ) : <span />}
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            关闭
          </Button>
        </div>
      </div>
    </Modal>
  );
};
