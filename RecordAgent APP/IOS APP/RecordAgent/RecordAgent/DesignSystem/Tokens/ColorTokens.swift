import SwiftUI

public enum AppColors {
    /// 品牌主色蓝（用于主操作按钮、重要高亮）
    public static let primaryBlue = Color(red: 0.38, green: 0.62, blue: 0.96) // #619EF5

    /// 按钮悬停/按压态深蓝
    public static let primaryBluePressed = Color(red: 0.31, green: 0.54, blue: 0.89)

    /// 登录模式切换胶囊选中底色
    public static let segmentSelectedBg = Color(red: 0.82, green: 0.89, blue: 0.98) // #D1E3FA

    /// 登录模式切换胶囊选中文本色
    public static let segmentSelectedText = Color(red: 0.15, green: 0.22, blue: 0.35)

    /// 登录模式切换栏背景底色
    public static let segmentBarBg = Color(red: 0.95, green: 0.96, blue: 0.98) // #F2F5FA

    /// 输入框默认边框色
    public static let inputBorder = Color(red: 0.90, green: 0.92, blue: 0.95) // #E6EBF2

    /// 输入框聚焦边框色
    public static let inputBorderFocused = Color(red: 0.55, green: 0.74, blue: 0.98)

    /// 表单输入框内部背景
    public static let inputBackground = Color.white

    /// 表单前置图标与占位符浅灰
    public static let iconTint = Color(red: 0.25, green: 0.28, blue: 0.35)
    public static let placeholderText = Color(red: 0.62, green: 0.66, blue: 0.72)

    /// 主标题深色文本
    public static let textPrimary = Color(red: 0.12, green: 0.15, blue: 0.20)

    /// 副标题与辅助文本
    public static let textSecondary = Color(red: 0.48, green: 0.53, blue: 0.60)

    /// 底部协议与轻量说明文案
    public static let textTertiary = Color(red: 0.62, green: 0.66, blue: 0.72)

    /// 整个页面的背景纯净底色
    public static let pageBackground = Color(red: 0.99, green: 0.995, blue: 1.0)
}
