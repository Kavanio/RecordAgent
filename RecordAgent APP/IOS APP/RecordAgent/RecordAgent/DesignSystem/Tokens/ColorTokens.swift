import SwiftUI

public enum AppColors {
    /// 品牌主色蓝（用于主操作按钮、重要高亮）
    public static let primaryBlue = Color(red: 0.36, green: 0.61, blue: 0.98) // #5C9CFA

    /// 按钮悬停/按压态深蓝
    public static let primaryBluePressed = Color(red: 0.30, green: 0.53, blue: 0.90)

    /// 登录模式切换胶囊选中底色
    public static let segmentSelectedBg = Color(red: 0.84, green: 0.91, blue: 0.99) // #D6E8FD

    /// 登录模式切换胶囊选中文本色
    public static let segmentSelectedText = Color(red: 0.14, green: 0.20, blue: 0.32)

    /// 登录模式切换栏背景底色
    public static let segmentBarBg = Color(red: 0.95, green: 0.96, blue: 0.98) // #F2F5FA

    /// 标题 record 手写字背后荧光笔高光色
    public static let brushHighlight = Color(red: 0.83, green: 0.90, blue: 0.99)

    /// 输入框默认边框色
    public static let inputBorder = Color(red: 0.91, green: 0.93, blue: 0.96) // #E8EDF5

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

    // MARK: - 主页与概览指标色彩

    /// 概览-日程指示与进度紫
    public static let overviewSchedule = Color(red: 0.48, green: 0.59, blue: 0.93) // #7B96EC

    /// 概览-支出金额与进度橙红
    public static let overviewExpense = Color(red: 1.0, green: 0.43, blue: 0.35) // #FF6E5A

    /// 概览-学习时长与进度青绿
    public static let overviewStudy = Color(red: 0.27, green: 0.63, blue: 0.59) // #45A096

    /// 概览-进度条默认浅底槽色
    public static let progressTrackBg = Color(red: 0.93, green: 0.95, blue: 0.98) // #EDF2FA

    /// 待办项勾选框未选中描边色
    public static let checkboxBorder = Color(red: 0.73, green: 0.77, blue: 0.82) // #BAC4D1

    /// TabBar 未选中图标与文字灰
    public static let tabInactive = Color(red: 0.56, green: 0.61, blue: 0.68) // #8F9CAE

    /// 主页天空淡蓝背景过渡色
    public static let skyLightBlue = Color(red: 0.91, green: 0.95, blue: 0.99) // #E8F2FC
}

