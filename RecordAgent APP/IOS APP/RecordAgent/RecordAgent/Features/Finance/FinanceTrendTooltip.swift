import SwiftUI

/// 明细浮在绘图区内，不改变页面高度；内容多时可滚动，宽度由宿主的可用空间决定。
struct FinanceTrendTooltip: View {
    let bucket: FinanceTrendBucket
    let isYear: Bool
    let onClose: () -> Void
    @Environment(\.locale) private var locale
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                Text(bucket.date, format: isYear ? .dateTime.year().month() : .dateTime.month().day())
                    .font(.subheadline.weight(.semibold))
                Spacer(minLength: 4)
                Button(action: onClose) { Image(systemName: "xmark").font(.caption2).frame(width: 44, height: 44) }
                    .buttonStyle(.plain).accessibilityLabel("common.close")
            }
            ScrollView {
                VStack(spacing: 8) {
                    amount("finance.expense", bucket.expense, FinanceVisualStyle.expense)
                    amount("finance.income", bucket.income, FinanceVisualStyle.income)
                    if !bucket.categories.isEmpty { Divider() }
                    ForEach(bucket.categories.keys.sorted(), id: \.self) { category in
                        HStack(alignment: .firstTextBaseline) {
                            categoryLabel(category)
                            Spacer(minLength: 4)
                            Text(WorkspaceFormat.money(bucket.categories[category] ?? 0, locale: locale))
                                .monospacedDigit()
                        }.foregroundStyle(FinanceVisualStyle.category(category))
                    }
                }.font(.caption)
            }.frame(maxHeight: 110)
        }
        .padding(12)
        .background(.thickMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(FinanceVisualStyle.accent.opacity(0.18)))
        .shadow(color: .black.opacity(0.16), radius: 16, y: 6)
        .accessibilityElement(children: .contain)
    }
    private func amount(_ key: LocalizedStringKey, _ cents: Int64, _ color: Color) -> some View {
        HStack {
            Text(key)
            Spacer(minLength: 4)
            Text(WorkspaceFormat.money(cents, locale: locale)).monospacedDigit()
        }.foregroundStyle(color)
    }
}
