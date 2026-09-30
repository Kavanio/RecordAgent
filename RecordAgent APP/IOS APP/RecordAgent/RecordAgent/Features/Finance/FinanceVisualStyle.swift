import SwiftUI

/// 收支语义色与稳定分类色统一用于摘要、流水、图表及详情。
enum FinanceVisualStyle {
    static let expense = Color(red: 0.32, green: 0.47, blue: 0.57)
    static let income = Color(red: 0.34, green: 0.49, blue: 0.41)
    static let palette: [Color] = [
        expense, income, Color(red: 0.67, green: 0.52, blue: 0.35),
        Color(red: 0.55, green: 0.48, blue: 0.65), Color(red: 0.66, green: 0.43, blue: 0.40),
        Color(red: 0.38, green: 0.55, blue: 0.56), Color(red: 0.59, green: 0.52, blue: 0.43),
        Color(red: 0.45, green: 0.51, blue: 0.66),
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
            Label(title, systemImage: symbol).font(.headline)
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
