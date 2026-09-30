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

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: transaction.type == "income" ? "arrow.down.left" : "arrow.up.right")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(transaction.type == "income" ? FinanceVisualStyle.income : FinanceVisualStyle.expense)
                .frame(width: 40, height: 40)
                .background((transaction.type == "income" ? FinanceVisualStyle.income : FinanceVisualStyle.expense).opacity(0.1), in: RoundedRectangle(cornerRadius: 13))
                .padding(.top, 3)
            VStack(alignment: .leading, spacing: 7) {
                if transaction.category.isEmpty {
                    Text(transaction.typeTitle)
                        .font(.body.weight(.medium))
                } else {
                    Text(transaction.category).font(.body.weight(.medium))
                }
                HStack(spacing: 8) {
                    if let date = WorkspaceFormat.date(transaction.date) {
                        Text(date, format: .dateTime.month(.abbreviated).day())
                    }
                    if let accountName { Text(accountName).lineLimit(1) }
                }
                .font(.caption).foregroundStyle(.secondary)
                if !transaction.note.isEmpty {
                    Text(transaction.note).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 7) {
                Text(WorkspaceFormat.money(transaction.amountInCents, locale: locale))
                    .font(.body.weight(.semibold)).monospacedDigit()
                    .foregroundStyle(transaction.type == "income" ? FinanceVisualStyle.income : FinanceVisualStyle.expense)
                Text(transaction.typeTitle)
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
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
                    VStack(alignment: .leading, spacing: 10) {
                        Text(transaction.typeTitle)
                            .font(.subheadline).foregroundStyle(.secondary)
                        Text(WorkspaceFormat.money(transaction.amountInCents, locale: locale))
                            .font(.largeTitle.weight(.semibold)).monospacedDigit()
                            .foregroundStyle(transaction.type == "income" ? FinanceVisualStyle.income : FinanceVisualStyle.expense)
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
                    WorkspaceValue(label: "finance.account.balance",
                        value: WorkspaceFormat.money(transaction.balanceInCents, locale: locale))
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
              (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        if parts.count == 3 {
            guard let seconds = Double(parts[2]), seconds >= 0, seconds < 60 else { return nil }
        }
        return calendar.date(from: DateComponents(year: 2000, month: 1, day: 15,
                                                  hour: hour, minute: minute, second: 0))
    }
}
