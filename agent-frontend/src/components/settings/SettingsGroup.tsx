import type { ReactNode } from 'react';
import styles from './SettingsGroup.module.css';

interface SettingsGroupProps {
  title: string;
  description?: string;
  children: ReactNode;
}

interface SettingsRowProps {
  label: ReactNode;
  description?: ReactNode;
  control: ReactNode;
  labelFor?: string;
  /** 多控件或长说明使用上下布局，避免左侧空白、右侧拥挤。 */
  layout?: 'inline' | 'stacked';
}

/** 按功能归纳设置项：组标题位于卡片外，相关设置在同一卡片内按行排列。 */
export function SettingsGroup({ title, description, children }: SettingsGroupProps) {
  return (
    <section className={styles.group} aria-label={title}>
      <header className={styles.heading}>
        <h2>{title}</h2>
        {description && <p>{description}</p>}
      </header>
      <div className={styles.card}>{children}</div>
    </section>
  );
}

/** 设置分组中的标准行，统一承载说明文案和右侧控件。 */
export function SettingsRow({ label, description, control, labelFor, layout = 'inline' }: SettingsRowProps) {
  const copy = (
    <>
      <span className={styles.label}>{label}</span>
      {description && <p>{description}</p>}
    </>
  );

  return (
    <div data-settings-row className={`${styles.row} ${layout === 'stacked' ? styles.stacked : ''}`}>
      {labelFor ? <label className={styles.copy} htmlFor={labelFor}>{copy}</label> : <div className={styles.copy}>{copy}</div>}
      <div className={styles.control}>{control}</div>
    </div>
  );
}
