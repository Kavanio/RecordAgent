import styles from './TaskMailTemplateEditor.module.css';

/** 报告邮件暂时固定使用蓝调摘要；邮件正文随任务报告栏目生成。 */
export function TaskMailTemplateEditor({ disabled }: { disabled: boolean }) {
  return <fieldset disabled={disabled} className={styles.group}>
    <legend>报告邮件模板</legend>
    <p className={styles.template}>蓝调摘要 <span>蓝灰背景、数据卡片与周期比较表格</span></p>
  </fieldset>;
}
