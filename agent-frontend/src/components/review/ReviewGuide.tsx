import styles from './ReviewGuide.module.css';

/** 用当前状态说明下一步，不执行授权或生成。 */
export function ReviewGuide({ enabled, authorized, statistics = false }: { enabled: boolean; authorized: boolean; statistics?: boolean }) {
  const next = !enabled ? '先打开配置，选择资料范围和模型，再启用并保存。'
    : !authorized ? '设置已保存。请查看授权范围并确认，之后才能生成。'
      : statistics ? '可以先生成昨天的预览；定期报告需在「任务」中另行设置邮件任务。'
        : '可以准备今天的复盘。生成后在下方阅读，有遗漏时再补充经历。';
  return <section className={styles.guide} aria-label="复盘使用指引"><strong>下一步：{next}</strong>
    <p>① 选择资料并授权　→　② {statistics ? '预览或定期报告' : '生成并阅读'}　→　③ {statistics ? '反馈建议的实际效果' : '按需补充经历'}</p>
    <p>{statistics ? '统计复盘参考学习、待办和财务数值；预览不发送邮件，也不创建长期建议。'
      : '生活复盘整理获准的近期经历；主动联系和飞书发送是可选项。当前尚不等于完整的长期记忆。'}</p></section>;
}
