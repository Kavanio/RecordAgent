import SwiftUI

/// 账户金额和名称置于柱体上方，省去手机端拥挤的坐标轴与重复明细列表。
struct FinanceAccountChart: View {
    let insights: FinanceInsights
    @State private var mode: FinanceTransactionFilter = .expense
    @State private var selectedID: String?
    @State private var showsAll = false
    @Environment(\.locale) private var locale
    private var rows: [FinanceAccountFlow] {
        insights.accounts.sorted { amount($0) == amount($1) ? $0.id < $1.id : amount($0) > amount($1) }
    }
    private var visible: [FinanceAccountFlow] { showsAll ? rows : Array(rows.prefix(5)) }
    private func amount(_ account: FinanceAccountFlow) -> Int64 { mode == .expense ? account.expense : account.income }
    private var color: Color { mode == .expense ? FinanceVisualStyle.expense : FinanceVisualStyle.income }
    private var maximum: Int64 { rows.map { amount($0) }.max() ?? 0 }

    var body: some View {
        FinanceChartSection(title: "finance.chart.accounts", symbol: "creditcard") {
            FinanceChartTabs(label: "finance.chart.accounts", options: [.expense, .income], selection: $mode) {
                $0.title
            }
            if maximum == 0 {
                WorkspaceEmptyState(
                    title: "finance.chart.empty", description: "finance.chart.empty.description", symbol: "chart.bar")
            } else {
                LazyVStack(spacing: 22) {
                    ForEach(visible) { row in
                        VStack(alignment: .leading, spacing: 10) {
                            Button {
                                selectedID = selectedID == row.id ? nil : row.id
                            } label: {
                                VStack(alignment: .leading, spacing: 12) {
                                    FinanceChartValueRow(amount: WorkspaceFormat.money(amount(row), locale: locale)) {
                                        if let name = row.name {
                                            Text(name).lineLimit(2)
                                        } else {
                                            Text("finance.account.unknown")
                                        }
                                    }
                                    GeometryReader { geometry in
                                        Capsule().fill(color.opacity(0.08))
                                        Capsule().fill(color.gradient)
                                            .frame(width: geometry.size.width * Double(amount(row)) / Double(maximum))
                                            .modifier(
                                                FinanceChartReveal(
                                                    key: "\(insights.period.key)-\(mode)-\(amount(row))-\(maximum)")
                                            )
                                    }.frame(height: 10).accessibilityHidden(true)
                                }.padding(.vertical, 4).contentShape(Rectangle())
                            }.buttonStyle(.plain).accessibilityAddTraits(selectedID == row.id ? .isSelected : [])
                            if selectedID == row.id {
                                VStack(alignment: .leading, spacing: 8) {
                                    flowRow("finance.expense", row.expense, FinanceVisualStyle.expense)
                                    flowRow("finance.income", row.income, FinanceVisualStyle.income)
                                }.font(.caption).padding(.top, 6)
                            }
                        }
                    }
                }
                if rows.count > 5 {
                    Button {
                        showsAll.toggle()
                    } label: {
                        Text(
                            showsAll
                                ? LocalizedStringKey("finance.chart.show_less")
                                : LocalizedStringKey("finance.chart.show_all")
                        )
                        .frame(minHeight: 44)
                    }
                }
            }
        }
        .onChange(of: mode) { _, _ in selectedID = nil }
    }
    private func flowRow(_ title: LocalizedStringKey, _ amount: Int64, _ color: Color) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(WorkspaceFormat.money(amount, locale: locale))
        }.foregroundStyle(color)
    }
}

struct FinanceRankingChart: View {
    let insights: FinanceInsights
    @State private var groupsCategories = true
    @State private var showsAll = false
    @Environment(\.locale) private var locale
    private var entries: [FinanceRankingEntry] { groupsCategories ? insights.categories : insights.transactions }
    private var visible: [FinanceRankingEntry] { showsAll ? entries : Array(entries.prefix(5)) }

    var body: some View {
        FinanceChartSection(title: "finance.chart.ranking", symbol: "chart.bar.xaxis") {
            FinanceChartTabs(label: "finance.chart.ranking", options: [true, false], selection: $groupsCategories) {
                $0 ? LocalizedStringKey("finance.chart.categories") : LocalizedStringKey("finance.chart.transactions")
            }
            if entries.isEmpty {
                WorkspaceEmptyState(
                    title: "finance.chart.empty", description: "finance.chart.empty.description",
                    symbol: "chart.bar.xaxis")
            } else {
                ForEach(Array(visible.enumerated()), id: \.element.id) { index, entry in
                    let catColor = FinanceVisualStyle.category(entry.category)
                    VStack(alignment: .leading, spacing: 8) {
                        FinanceChartValueRow(amount: WorkspaceFormat.money(entry.amount, locale: locale)) {
                            HStack(alignment: .center, spacing: 8) {
                                Text((index + 1).formatted(.number.locale(locale)))
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(index < 3 ? FinanceVisualStyle.accent : Color.secondary)
                                    .frame(minWidth: 14, alignment: .leading)
                                Circle()
                                    .fill(catColor)
                                    .frame(width: 8, height: 8)
                                if !groupsCategories && !entry.note.isEmpty {
                                    Text(entry.note).lineLimit(2)
                                } else {
                                    categoryLabel(entry.category)
                                }
                            }
                        }
                        HStack {
                            if !groupsCategories, let date = entry.date {
                                Text(date, format: .dateTime.month(.abbreviated).day())
                            }
                            Spacer()
                            Text(share(entry).formatted(.percent.precision(.fractionLength(1)).locale(locale)))
                                .font(.caption.weight(.medium))
                                .foregroundStyle(catColor)
                        }.font(.caption).foregroundStyle(.secondary)
                        GeometryReader { geometry in
                            Capsule().fill(catColor.opacity(0.12))
                            Capsule().fill(catColor.gradient)
                                .frame(width: geometry.size.width * share(entry))
                                .modifier(
                                    FinanceChartReveal(
                                        key:
                                            "\(insights.period.key)-\(groupsCategories)-\(entry.amount)-\(insights.expense)"
                                    ))
                        }.frame(height: 8).accessibilityHidden(true)
                    }.padding(.vertical, 5).accessibilityElement(children: .combine)
                }
                if entries.count > 5 {
                    Button {
                        showsAll.toggle()
                    } label: {
                        Text(
                            showsAll
                                ? LocalizedStringKey("finance.chart.show_less")
                                : LocalizedStringKey("finance.chart.show_all")
                        )
                        .frame(minHeight: 44)
                    }
                }
            }
        }
        .onChange(of: groupsCategories) { _, _ in showsAll = false }
    }
    private func share(_ entry: FinanceRankingEntry) -> Double {
        insights.expense > 0 ? min(1, Double(entry.amount) / Double(insights.expense)) : 0
    }
}
