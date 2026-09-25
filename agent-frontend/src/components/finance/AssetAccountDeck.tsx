import React, { useEffect, useState } from 'react';
import { CaretLeftIcon, CaretRightIcon } from '@phosphor-icons/react';
import type { FinanceAccount, FinanceAccountType } from '../../types/finance';
import { AccountTypeIcon } from './AccountTypeIcon';
import styles from './AssetAccountDeck.module.css';

const ACCOUNT_LABELS: Record<FinanceAccountType, string> = {
  wechat_balance: '微信余额', wechat_yield: '微信零钱通', alipay_balance: '支付宝余额',
  alipay_yuebao: '支付宝余额宝', bank: '银行卡', other: '其他',
  wechat: '微信', alipay: '支付宝', cash: '现金',
};

function money(value: number): string {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);
}

function tone(type: FinanceAccountType): string {
  if (type === 'wechat_balance' || type === 'wechat_yield' || type === 'wechat') return styles.wechatTone;
  if (type === 'alipay_balance' || type === 'alipay_yuebao' || type === 'alipay') return styles.alipayTone;
  if (type === 'bank') return styles.bankTone;
  return styles.neutralTone;
}

interface AssetAccountDeckProps {
  accounts: FinanceAccount[];
}

/** 将真实资产账户组织成可切换的钱包卡片牌组。 */
export const AssetAccountDeck: React.FC<AssetAccountDeckProps> = ({ accounts }) => {
  const [activeIndex, setActiveIndex] = useState(0);

  useEffect(() => {
    if (activeIndex >= accounts.length) setActiveIndex(Math.max(0, accounts.length - 1));
  }, [accounts.length, activeIndex]);

  if (accounts.length === 0) return null;
  const account = accounts[activeIndex] ?? accounts[0];
  const selectPrevious = () => setActiveIndex((current) => (current - 1 + accounts.length) % accounts.length);
  const selectNext = () => setActiveIndex((current) => (current + 1) % accounts.length);

  return <div className={styles.deck}>
    <div className={styles.cardStage}>
      <span className={`${styles.backCard} ${styles.backCardFar}`} aria-hidden="true" />
      <span className={`${styles.backCard} ${styles.backCardNear}`} aria-hidden="true" />
      <article key={account.id} className={`${styles.accountCard} ${tone(account.accountType)}`} aria-live="polite">
        <header>
          <AccountTypeIcon type={account.accountType} size={22} />
          <span className={styles.cardIdentity}><strong>{account.name}</strong><small>{ACCOUNT_LABELS[account.accountType]}</small></span>
          <span className={styles.cardCount}>{activeIndex + 1} / {accounts.length}</span>
        </header>
        <div className={styles.cardBalance}><span>当前余额</span><strong>{money(account.balance)}</strong></div>
        <footer>
          <span>{account.currency}</span>
          <span>{account.interestEnabled ? `年化 ${account.annualRatePercent}%` : '账户余额'}</span>
        </footer>
      </article>
    </div>

    <div className={styles.deckNavigation}>
      <button type="button" className={styles.arrowButton} onClick={selectPrevious} disabled={accounts.length < 2} aria-label="上一个资产账户"><CaretLeftIcon size={14} /></button>
      <div className={styles.accountSelectors} aria-label="选择资产账户">
        {accounts.map((item, index) => <button type="button" aria-pressed={index === activeIndex}
          className={index === activeIndex ? styles.selectorActive : ''} key={item.id}
          title={`${item.name}，${money(item.balance)}`} onClick={() => setActiveIndex(index)}>
          <AccountTypeIcon type={item.accountType} size={16} /><span>{item.name}</span>
        </button>)}
      </div>
      <button type="button" className={styles.arrowButton} onClick={selectNext} disabled={accounts.length < 2} aria-label="下一个资产账户"><CaretRightIcon size={14} /></button>
    </div>
  </div>;
};
