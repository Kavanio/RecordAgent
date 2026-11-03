/** 防偷窥只改变编辑器显示，配置属于本机长期偏好。 */
export interface WritingPrivacyPreferences {
  enabled: boolean;
  /** 空字符串关闭快捷键，否则为 Ctrl/⌘ + Shift + 字母。 */
  shortcut: string;
}
