import SwiftUI

/// 收支语义色与稳定分类色统一用于摘要、流水、图表及详情。
enum FinanceVisualStyle {
    static let expense = Color(red: 0.83, green: 0.32, blue: 0.24)
    static let income = Color(red: 0.16, green: 0.53, blue: 0.44)
    static let palette: [Color] = [expense, .indigo, income, .orange, .purple, .teal, .pink, .blue]
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
