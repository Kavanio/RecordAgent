import SwiftUI

/// 收支语义色与稳定分类色统一用于摘要、流水、图表及详情。
enum FinanceVisualStyle {
    static let expense = adaptive(light: (0.16, 0.36, 0.78), dark: (0.43, 0.64, 1.0))
    static let income = adaptive(light: (0.05, 0.49, 0.35), dark: (0.29, 0.79, 0.59))
    static let accent = adaptive(light: (0.20, 0.29, 0.65), dark: (0.58, 0.67, 1.0))

    /// 浅色保证文字对比，深色提升亮度；收支与分类映射保持稳定。
    private static func adaptive(light: (Double, Double, Double), dark: (Double, Double, Double)) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: value.0, green: value.1, blue: value.2, alpha: 1)
        })
    }
    static let palette: [Color] = [
        expense, income, adaptive(light: (0.72, 0.43, 0.12), dark: (0.96, 0.71, 0.35)),
        adaptive(light: (0.56, 0.32, 0.75), dark: (0.76, 0.58, 0.94)), adaptive(light: (0.78, 0.30, 0.35), dark: (0.98, 0.57, 0.60)),
        adaptive(light: (0.06, 0.49, 0.57), dark: (0.34, 0.78, 0.85)), adaptive(light: (0.63, 0.44, 0.27), dark: (0.83, 0.67, 0.47)),
        adaptive(light: (0.38, 0.40, 0.73), dark: (0.63, 0.65, 0.94)),
    ]
    static func category(_ name: String) -> Color {
        let hash = name.utf8.reduce(UInt64(5381)) { ($0 &* 33) &+ UInt64($1) }
        return palette[Int(hash % UInt64(palette.count))]
    }
}

extension FinanceChartRange {
    var title: LocalizedStringKey {
        switch self {
        case .week: return "finance.range.week"
        case .month: return "finance.range.month"
        case .year: return "finance.range.year"
        }
    }
}

/// 图表沿用页面白色画布，保留全部内容宽度，不再嵌套底色卡片。
struct FinanceChartSection<Content: View>: View {
    let title: LocalizedStringKey
    let symbol: String
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 10) {
                Image(systemName: symbol).foregroundStyle(FinanceVisualStyle.accent).accessibilityHidden(true)
                Text(title)
            }.font(.headline)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)

    }
}

/// 金额优先完整显示；名称和大额在窄屏无法同行时自动改为上下布局。
struct FinanceChartValueRow<Title: View>: View {
    let amount: String
    @ViewBuilder let title: () -> Title
    @Environment(\.dynamicTypeSize) private var typeSize
    var body: some View {
        if typeSize.isAccessibilitySize {
            vertical
        } else {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    title().font(.subheadline)
                    Spacer(minLength: 4)
                    Text(amount).font(.subheadline.weight(.semibold)).monospacedDigit().fixedSize()
                }
                vertical
            }
        }
    }
    private var vertical: some View {
        VStack(alignment: .leading, spacing: 6) {
            title().font(.subheadline)
            Text(amount).font(.subheadline.weight(.semibold)).monospacedDigit()
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
