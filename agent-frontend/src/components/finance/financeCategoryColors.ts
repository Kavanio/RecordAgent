/** 清亮的分类候选色；顺序仅影响选色器，不参与已有分类的颜色分配。 */
export const FINANCE_COLOR_PRESETS = [
  { name: '天蓝', color: '#3B82F6' },
  { name: '橙色', color: '#F97316' },
  { name: '琥珀', color: '#F59E0B' },
  { name: '青绿', color: '#10B981' },
  { name: '紫色', color: '#8B5CF6' },
  { name: '粉色', color: '#EC4899' },
  { name: '青蓝', color: '#06B6D4' },
  { name: '草绿', color: '#84CC16' },
  { name: '靛蓝', color: '#6366F1' },
  { name: '珊瑚', color: '#F43F5E' },
] as const;

const DEFAULT_COLORS: Record<'expense' | 'income', Record<string, string>> = {
  expense: {
    餐饮: '#F59E0B', 交通: '#3B82F6', 购物: '#8B5CF6', 居住: '#06B6D4',
    娱乐: '#EC4899', 学习: '#6366F1', 医疗: '#10B981', 其他: '#F97316',
  },
  income: {
    工资: '#3B82F6', 奖金: '#F59E0B', 报销: '#06B6D4', 转入: '#10B981',
    兼职: '#8B5CF6', 其他: '#F97316',
  },
};

/** 按名称确定颜色，完全独立于图表区间、排序和其他分类；有效用户配置优先。 */
export function financeCategoryColor(category: string, type: 'expense' | 'income' = 'expense', colors?: Record<string, string>): string {
  const name = category.trim();
  const saved = colors && Object.hasOwn(colors, name) ? colors[name] : undefined;
  if (saved && /^#[0-9a-f]{6}$/i.test(saved)) return saved.toUpperCase();
  if (Object.hasOwn(DEFAULT_COLORS[type], name)) return DEFAULT_COLORS[type][name];
  // 未配置的历史自定义分类采用确定性名称哈希，新增分类不会挤占其他分类的颜色。
  let hash = 0;
  for (const char of `${type}:${name}`) hash = (Math.imul(hash, 31) + char.codePointAt(0)!) >>> 0;
  return FINANCE_COLOR_PRESETS[hash % FINANCE_COLOR_PRESETS.length].color;
}
