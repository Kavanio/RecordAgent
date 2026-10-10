import SwiftUI

/// 轻量悬浮框：仅展示选中日期与支出/收入对比，尺寸小巧精致，不遮挡图表主体。
struct FinanceTrendTooltip: View {
    let bucket: FinanceTrendBucket
    let isYear: Bool
    var onClose: (() -> Void)? = nil
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(bucket.date, format: isYear ? .dateTime.year().month() : .dateTime.month().day())
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.secondary)

            HStack(spacing: 12) {
                metricRow("finance.expense", amount: bucket.expense, color: FinanceVisualStyle.expense)
                metricRow("finance.income", amount: bucket.income, color: FinanceVisualStyle.income)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(.ultraThinMaterial)
                .shadow(color: Color.black.opacity(0.08), radius: 8, x: 0, y: 2)
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.8)
                )
        }
        .contentShape(Rectangle())
        .onTapGesture { onClose?() }
        .accessibilityElement(children: .combine)
    }

    private func metricRow(_ titleKey: LocalizedStringKey, amount: Int64, color: Color) -> some View {
        HStack(spacing: 4) {
            Circle()
                .fill(color)
                .frame(width: 5, height: 5)
            Text(titleKey)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(.secondary)
            Text(WorkspaceFormat.money(amount, locale: locale))
                .font(.system(size: 11, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(color)
        }
    }
}

