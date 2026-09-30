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
    var title: LocalizedStringKey { LocalizedStringKey("finance.range.\(rawValue)") }
}

/// 图表采用单层柔和底色，不叠加边框或阴影。
struct FinanceChartSection<Content: View>: View {
    let title: LocalizedStringKey
    let symbol: String
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Label(title, systemImage: symbol).font(.headline)
            content()
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24))
    }
}
