import Charts
import SwiftUI

struct FinanceAccountChart: View {
    let insights: FinanceInsights
    @State private var mode: FinanceTransactionFilter = .expense
    @State private var selectedID: String?
    @Environment(\.locale) private var locale
    private var rows: [FinanceAccountFlow] {
        insights.accounts.sorted { amount($0) == amount($1) ? $0.id < $1.id : amount($0) > amount($1) }
    }
    private func amount(_ account: FinanceAccountFlow) -> Int64 { mode == .expense ? account.expense : account.income }
    private var color: Color { mode == .expense ? FinanceVisualStyle.expense : FinanceVisualStyle.income }

    var body: some View {
        FinanceChartSection(title: "finance.chart.accounts", symbol: "creditcard") {
            WorkspaceFilters(options: [.expense, .income], selection: $mode) { $0.title }
            if rows.allSatisfy({ amount($0) == 0 }) {
                WorkspaceEmptyState(
                    title: "finance.chart.empty", description: "finance.chart.empty.description", symbol: "chart.bar")
            } else {
                Chart(rows) { row in
                    BarMark(
                        x: .value(AppLocalization.string("finance.amount", locale: locale), Double(amount(row)) / 100),
                        y: .value(AppLocalization.string("finance.account", locale: locale), row.id)
                    )
                    .foregroundStyle(color.gradient).cornerRadius(5)
                    .annotation(position: .trailing) {
                        Text(WorkspaceFormat.money(amount(row), locale: locale)).font(.caption2).monospacedDigit()
                    }
                }
                .chartYScale(domain: rows.map(\.id))
                .chartYAxis {
                    AxisMarks { value in
                        AxisValueLabel {
                            if let id = value.as(String.self), let row = rows.first(where: { $0.id == id }) {
                                if let name = row.name {
                                    Text(name).lineLimit(1)
                                } else {
                                    Text("finance.account.unknown")
                                }
                            }
                        }
                    }
                }
                .chartXAxis { AxisMarks(values: .automatic(desiredCount: 3)) }
                .frame(height: CGFloat(max(1, rows.count)) * 44 + 30)
                .accessibilityLabel("finance.chart.accounts")
                // 金额同时以原生按钮提供，辅助技术不依赖图形坐标命中。
                ForEach(rows) { row in
                    Button {
                        selectedID = selectedID == row.id ? nil : row.id
                    } label: {
                        HStack {
                            if let name = row.name { Text(name) } else { Text("finance.account.unknown") }
                            Spacer()
                            Text(WorkspaceFormat.money(amount(row), locale: locale)).monospacedDigit()
                            Image(systemName: selectedID == row.id ? "chevron.up" : "chevron.down").font(.caption2)
                        }.font(.subheadline).frame(minHeight: 44)
                    }.buttonStyle(.plain)
                    if selectedID == row.id {
                        VStack(alignment: .leading, spacing: 6) {
                            flowRow("finance.expense", row.expense, FinanceVisualStyle.expense)
                            flowRow("finance.income", row.income, FinanceVisualStyle.income)
                        }.font(.caption).padding(12)
                            .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                    }
                }
            }
        }
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
            Picker("finance.chart.ranking", selection: $groupsCategories) {
                Text("finance.chart.categories").tag(true)
                Text("finance.chart.transactions").tag(false)
            }.pickerStyle(.segmented)
            if entries.isEmpty {
                WorkspaceEmptyState(
                    title: "finance.chart.empty", description: "finance.chart.empty.description",
                    symbol: "chart.bar.xaxis")
            } else {
                ForEach(Array(visible.enumerated()), id: \.element.id) { index, entry in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            Text((index + 1).formatted(.number.locale(locale))).font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            if !groupsCategories && !entry.note.isEmpty {
                                Text(entry.note).lineLimit(2)
                            } else {
                                categoryLabel(entry.category)
                            }
                            Spacer(minLength: 4)
                            Text(WorkspaceFormat.money(entry.amount, locale: locale)).monospacedDigit().font(
                                .subheadline.weight(.semibold))
                        }
                        HStack {
                            if !groupsCategories, let date = entry.date {
                                Text(date, format: .dateTime.month(.abbreviated).day())
                            }
                            Spacer()
                            Text(share(entry).formatted(.percent.precision(.fractionLength(1)).locale(locale)))
                        }.font(.caption).foregroundStyle(.secondary)
                        GeometryReader { geometry in
                            Capsule().fill(FinanceVisualStyle.category(entry.category).opacity(0.1))
                            Capsule().fill(FinanceVisualStyle.category(entry.category).gradient)
                                .frame(width: geometry.size.width * share(entry))
                        }.frame(height: 7).accessibilityHidden(true)
                    }.padding(.vertical, 5).accessibilityElement(children: .combine)
                }
                if entries.count > 5 {
                    Button(showsAll ? "finance.chart.show_less" : "finance.chart.show_all") { showsAll.toggle() }.frame(
                        minHeight: 44)
                }
            }
        }
        .onChange(of: groupsCategories) { _, _ in showsAll = false }
    }
    private func share(_ entry: FinanceRankingEntry) -> Double {
        insights.expense > 0 ? min(1, Double(entry.amount) / Double(insights.expense)) : 0
    }
}
