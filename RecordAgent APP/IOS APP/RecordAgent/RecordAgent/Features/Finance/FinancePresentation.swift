import SwiftUI

extension FinanceTransactionFilter {
    var title: LocalizedStringKey {
        switch self {
        case .all: return "finance.filter.all"
        case .expense: return "finance.expense"
        case .income: return "finance.income"
        }
    }
}

extension FinanceTransaction {
    var typeTitle: LocalizedStringKey { type == "income" ? "finance.income" : "finance.expense" }
}

struct FinanceTransactionRow: View {
    let transaction: FinanceTransaction
    let accountName: String?
    @Environment(\.locale) private var locale

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Group {
            if typeSize.isAccessibilitySize {
                HStack(alignment: .top, spacing: 16) {
                    icon
                    VStack(alignment: .leading, spacing: 14) {
                        details
                        amounts
                    }
                }
            } else {
                HStack(alignment: .top, spacing: 16) {
                    icon
                    details
                    Spacer(minLength: 8)
                    amounts
                }
            }
        }
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
    private var icon: some View {
        FinanceCategoryBadge(
            category: transaction.category,
            type: transaction.type,
            size: 42,
            cornerRadius: 13
        )
        .accessibilityHidden(true)
    }
    private var details: some View {
        VStack(alignment: .leading, spacing: 6) {
            if transaction.category.isEmpty {
                Text(transaction.typeTitle).font(.body.weight(.medium))
            } else {
                Text(transaction.category).font(.body.weight(.semibold))
            }
            if typeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 4) { dateAndAccount }
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                HStack(spacing: 8) { dateAndAccount }.font(.caption).foregroundStyle(.secondary)
            }
            if !transaction.note.isEmpty {
                Text(transaction.note).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
        }
    }
    @ViewBuilder private var dateAndAccount: some View {
        if let date = WorkspaceFormat.date(transaction.date) {
            Text(date, format: .dateTime.month(.abbreviated).day())
        }
        if let accountName { Text(accountName).lineLimit(typeSize.isAccessibilitySize ? nil : 1) }
    }
    private var amounts: some View {
        VStack(alignment: typeSize.isAccessibilitySize ? .leading : .trailing, spacing: 5) {
            let isIncome = transaction.type == "income"
            let sign = isIncome ? "+" : "-"
            let formattedAmount = WorkspaceFormat.money(transaction.amountInCents, locale: locale)
            Text("\(sign)\(formattedAmount)")
                .font(.body.weight(.semibold)).monospacedDigit()
                .foregroundStyle(isIncome ? FinanceVisualStyle.income : FinanceVisualStyle.expense)
            if let balance = transaction.balanceAfterInCents {
                VStack(alignment: typeSize.isAccessibilitySize ? .leading : .trailing, spacing: 2) {
                    Text("finance.balance.after").font(.caption2)
                    Text(WorkspaceFormat.money(balance, locale: locale)).font(.caption).monospacedDigit()
                }.foregroundStyle(.secondary)
            } else {
                Text("finance.balance.unknown").font(.caption2).foregroundStyle(.secondary)
            }
        }.fixedSize(horizontal: false, vertical: true)
    }
}

struct FinanceTransactionDetailView: View {
    let transaction: FinanceTransaction
    let accountName: String?
    @Environment(\.locale) private var locale
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            WorkspaceSheetHeader(title: "finance.transaction.detail") { dismiss() }
                .padding(.horizontal, 24).padding(.top, 28).padding(.bottom, 16)
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    HStack(spacing: 16) {
                        FinanceCategoryBadge(
                            category: transaction.category,
                            type: transaction.type,
                            size: 52,
                            cornerRadius: 16
                        )
                        VStack(alignment: .leading, spacing: 4) {
                            Text(transaction.category.isEmpty ? AppLocalization.string(transaction.type == "income" ? "finance.income" : "finance.expense", locale: locale) : transaction.category)
                                .font(.title3.weight(.bold))
                            Text(transaction.typeTitle)
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        let isIncome = transaction.type == "income"
                        let sign = isIncome ? "+" : "-"
                        let formattedAmount = WorkspaceFormat.money(transaction.amountInCents, locale: locale)
                        Text("\(sign)\(formattedAmount)")
                            .font(.system(size: 34, weight: .bold, design: .rounded)).monospacedDigit()
                            .foregroundStyle(
                                isIncome ? FinanceVisualStyle.income : FinanceVisualStyle.expense)
                    }
                    if !transaction.category.isEmpty {
                        WorkspaceValue(label: "finance.category", value: transaction.category)
                    }
                    if let accountName { WorkspaceValue(label: "finance.account", value: accountName) }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("finance.date").font(.caption).foregroundStyle(.secondary)
                        if let date = WorkspaceFormat.date(transaction.date) {
                            Text(date, format: .dateTime.year().month(.wide).day())
                        }
                        if let time = FinanceTransactionTime.date(transaction.time) {
                            Text(time, format: .dateTime.hour().minute())
                        }
                    }
                    WorkspaceValue(
                        label: "finance.balance.after",
                        value: transaction.balanceAfterInCents.map {
                            WorkspaceFormat.money($0, locale: locale)
                        } ?? AppLocalization.string("finance.balance.unknown", locale: locale))
                    if !transaction.note.isEmpty {
                        WorkspaceSection(title: "finance.note") {
                            Text(transaction.note).textSelection(.enabled)
                        }
                    }
                }
                .padding(20).frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24))
                .padding(.horizontal, 24).padding(.bottom, 24)
            }
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .workspaceSheet(compact: true)
    }
}

/// 服务端 LocalTime 是当地墙钟时间，使用本地日历构建，展示跟随应用语言。
enum FinanceTransactionTime {
    static func date(_ value: String, calendar: Calendar = .current) -> Date? {
        let parts = value.split(separator: ":")
        guard (2...3).contains(parts.count), let hour = Int(parts[0]), let minute = Int(parts[1]),
            (0...23).contains(hour), (0...59).contains(minute)
        else { return nil }
        if parts.count == 3 {
            guard let seconds = Double(parts[2]), seconds >= 0, seconds < 60 else { return nil }
        }
        return calendar.date(
            from: DateComponents(
                year: 2000, month: 1, day: 15,
                hour: hour, minute: minute, second: 0))
    }
}
