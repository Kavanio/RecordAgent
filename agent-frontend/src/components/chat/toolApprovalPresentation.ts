/** 常见工具的中文友好展示名称映射 */
export const TOOL_NAME_MAP: Record<string, string> = {
  // 学习记录
  study_create_manual: '创建学习记录',
  study_start: '开始学习时段',
  study_finish: '结束学习时段',
  study_update: '更新学习记录',
  study_delete: '删除学习记录',
  study_query: '查询学习记录',

  // 待办事项
  todo_create: '创建待办事项',
  todo_update: '更新待办事项',
  todo_delete: '删除待办事项',
  todo_query: '查询待办事项',

  // 财务记账
  finance_record_transaction: '记录财务收支',
  finance_delete_transaction: '删除财务收支',

  // 文件与终端
  write_file: '写入本地文件',
  edit_file: '编辑文件内容',
  read_file: '读取本地文件',
  execute: '执行系统命令',
  execute_command: '执行系统命令',
  custom_bash: '运行终端脚本',
  capture_screen: '截取电脑主屏幕',
  terminal: '终端会话操作',

  // 网络与数据
  http_request: '发送网络请求',
  web_search: '执行网络搜索',
  query_database: '执行 SQL 查询',

  // 任务计划
  plan_exit: '提交任务计划书',
  task_cancel: '取消后台任务',
  session_search: '搜索历史会话',
};

/** 解析工具的风险操作徽章 */
export function getRiskBadge(toolName: string, riskDescription?: string): string {
  if (riskDescription) {
    if (riskDescription.includes('写入') || riskDescription.includes('修改数据')) return '写入操作';
    if (riskDescription.includes('Shell') || riskDescription.includes('命令')) return '系统命令';
    if (riskDescription.includes('网络') || riskDescription.includes('请求')) return '网络请求';
    if (riskDescription.includes('文件')) return '文件修改';
  }
  if (toolName.includes('create') || toolName.includes('record')) return '写入操作';
  if (toolName.includes('delete')) return '删除操作';
  if (toolName.includes('update') || toolName.includes('edit') || toolName.includes('write')) return '修改操作';
  if (toolName.includes('execute') || toolName.includes('bash') || toolName.includes('terminal')) return '系统命令';
  if (toolName.includes('http') || toolName.includes('search')) return '网络请求';
  return '高风险操作';
}

/** 格式化参数值输出 */
export function formatParamValue(value: unknown): string {
  if (value === null) return 'null';
  if (value === undefined) return 'undefined';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}
