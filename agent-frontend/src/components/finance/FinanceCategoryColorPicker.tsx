import { useId } from 'react';
import { FINANCE_COLOR_PRESETS } from './financeCategoryColors';
import styles from './FinanceCategoryColorPicker.module.css';

interface FinanceCategoryColorPickerProps {
  category: string;
  value: string;
  onChange: (color: string) => void;
  disabled?: boolean;
}

/** 分类选色：预设色与原生自定义色共享受控草稿，随流水保存后生效。 */
export function FinanceCategoryColorPicker({ category, value, onChange, disabled }: FinanceCategoryColorPickerProps) {
  const helpId = useId();
  return <fieldset className={styles.field} disabled={disabled} aria-describedby={helpId}>
    <legend>分类颜色</legend>
    <div className={styles.palette}>
      {FINANCE_COLOR_PRESETS.map((preset) => <button type="button" key={preset.color}
        className={styles.swatch} aria-label={`分类颜色：${preset.name}`} aria-pressed={value.toUpperCase() === preset.color}
        title={preset.name} onClick={() => onChange(preset.color)}>
        <svg width="22" height="22" viewBox="0 0 22 22" aria-hidden="true"><circle cx="11" cy="11" r="9" fill={preset.color} /></svg>
      </button>)}
      <label className={styles.custom}>
        <input type="color" value={value} onChange={(event) => onChange(event.target.value.toUpperCase())} aria-label="自定义分类颜色" />
        <span>自定义</span>
      </label>
    </div>
    <p id={helpId}>{category.trim() ? `「${category.trim()}」` : '当前分类'}的颜色会随本次保存生效，同类历史记录同步更新。</p>
  </fieldset>;
}
