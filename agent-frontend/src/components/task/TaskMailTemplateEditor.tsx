import type { MailTemplate } from '../../types/automation';
import { Select } from '../common/Select';
import { FormField } from '../common/FormField';
import { TextInput } from '../common/TextInput';
import styles from './TaskMailTemplateEditor.module.css';

/** 每个日报保存独立模板；纯文本占位符由后端安全渲染。 */
export function TaskMailTemplateEditor({ value, disabled, onChange }: {
  value: MailTemplate; disabled: boolean; onChange: (value: MailTemplate) => void;
}) {
  function update<K extends keyof MailTemplate>(key: K, next: MailTemplate[K]) {
    onChange({ ...value, [key]: next });
  }
  return <fieldset disabled={disabled} className={styles.group}>
    <legend>日报邮件模板</legend>
    <Select label="邮件样式" value={value.style} options={[
      { value: 'CARDS', label: '清爽卡片 · 分栏汇总' },
      { value: 'MINIMAL', label: '极简白纸 · 简洁阅读' },
      { value: 'LETTER', label: '暖色信笺 · 温暖记录' },
      { value: 'CUSTOM', label: '自定义 · 配色与正文' },
    ]} onChange={e => update('style', e.target.value as MailTemplate['style'])} />
    {value.style === 'CUSTOM' && <>
      <FormField label="主题色" htmlFor="mail-accent"><TextInput id="mail-accent" type="color" value={value.accent} onChange={e => update('accent', e.target.value)} /></FormField>
      <FormField label="邮件内标题" htmlFor="mail-heading"><TextInput id="mail-heading" maxLength={100} value={value.heading} onChange={e => update('heading', e.target.value)} /></FormField>
      <FormField label="开场语" htmlFor="mail-intro"><TextInput id="mail-intro" maxLength={500} value={value.introduction} onChange={e => update('introduction', e.target.value)} /></FormField>
      <FormField label="正文模板" htmlFor="mail-body" hint="支持纯文本和下方占位符，可自由调整栏目顺序。">
        <textarea id="mail-body" rows={8} maxLength={8000} value={value.body} onChange={e => update('body', e.target.value)} />
      </FormField>
      <p className={styles.hint}>{'{{expense}} 支出 · {{todo}} 待办 · {{study}} 学习 · {{date}} 日期 · {{generatedAt}} 生成时间 · {{title}} 任务标题 · {{content}} 完整报告。未勾选的栏目留空。'}</p>
      <FormField label="页脚" htmlFor="mail-footer"><TextInput id="mail-footer" maxLength={200} value={value.footer} onChange={e => update('footer', e.target.value)} /></FormField>
    </>}
    <p className={styles.hint}>点击下方“预览”查看真实邮件排版，不会发送邮件。模板与本任务一起保存，复制任务时一并复制。</p>
  </fieldset>;
}
