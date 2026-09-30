import Charts
import SwiftUI

struct FinanceInsightsView: View {
    @ObservedObject var model: FinanceWorkspaceViewModel
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            WorkspaceFilters(options: FinanceChartRange.allCases, selection: $model.chartRange) { $0.title }
            Text(
                model.analysisPeriod
                    .start..<(model.analysisPeriod.calendar.date(
                        byAdding: .day, value: -1, to: model.analysisPeriod.end) ?? model.analysisPeriod.start),
                format: .interval.day().month().year()
            )
            .font(.caption).foregroundStyle(.secondary)
            if let error = model.visibleInsightsError {
                WorkspaceErrorView(message: error) { Task { await model.loadInsights() } }
            } else if let insights = model.visibleInsights {
                FinanceTrendChart(insights: insights)
                FinanceAccountChart(insights: insights)
                FinanceRankingChart(insights: insights)
            } else {
                ProgressView("finance.analysis.loading").frame(maxWidth: .infinity, minHeight: 100)
            }
        }
        .padding(16)
        .background(Color(uiColor: .systemGroupedBackground), in: RoundedRectangle(cornerRadius: 28))
        .task(id: model.analysisKey) { await model.loadInsights() }
    }
}

struct FinanceTrendChart: View {
    let insights: FinanceInsights
    @Environment(\.locale) private var locale
    @State private var selection: Date?
    private var selected: FinanceTrendBucket? {
        guard let selection else { return nil }
        return insights.buckets.min {
            abs($0.date.timeIntervalSince(selection)) < abs($1.date.timeIntervalSince(selection))
        }
    }
    private var categories: [String] { insights.categories.map(\.category).sorted() }

    var body: some View {
        FinanceChartSection(title: "finance.chart.trend", symbol: "chart.xyaxis.line") {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 16) { totals }
                VStack(alignment: .leading, spacing: 12) { totals }
            }
            if insights.expense == 0 && insights.income == 0 {
                empty
            } else {
                Chart {
                    ForEach(insights.buckets) { bucket in
                        ForEach(categories, id: \.self) { category in
                            if let amount = bucket.categories[category], amount > 0 {
                                BarMark(
                                    x: .value(AppLocalization.string("finance.date", locale: locale), bucket.date),
                                    y: .value(
                                        AppLocalization.string("finance.amount", locale: locale), Double(amount) / 100)
                                )
                                .foregroundStyle(FinanceVisualStyle.category(category).opacity(0.55))
                            }
                        }
                        LineMark(
                            x: .value(AppLocalization.string("finance.date", locale: locale), bucket.date),
                            y: .value(
                                AppLocalization.string("finance.expense", locale: locale), Double(bucket.expense) / 100),
                            series: .value(
                                AppLocalization.string("finance.chart.trend", locale: locale),
                                AppLocalization.string("finance.expense", locale: locale))
                        )
                        .foregroundStyle(FinanceVisualStyle.expense)
                        .lineStyle(StrokeStyle(lineWidth: 2.5))
                        LineMark(
                            x: .value(AppLocalization.string("finance.date", locale: locale), bucket.date),
                            y: .value(
                                AppLocalization.string("finance.income", locale: locale), Double(bucket.income) / 100),
                            series: .value(
                                AppLocalization.string("finance.chart.trend", locale: locale),
                                AppLocalization.string("finance.income", locale: locale))
                        )
                        .foregroundStyle(FinanceVisualStyle.income)
                        .lineStyle(StrokeStyle(lineWidth: 2, dash: [5, 4]))
                    }
                    if let selected {
                        RuleMark(x: .value(AppLocalization.string("finance.date", locale: locale), selected.date))
                            .foregroundStyle(Color.secondary.opacity(0.4))
                    }
                }
                .chartXSelection(value: $selection)
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 4)) { value in
                        AxisValueLabel(
                            format: insights.period.range == .year
                                ? .dateTime.month(.abbreviated) : .dateTime.day().month(.abbreviated))
                    }
                }
                .chartYAxis { AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) }
                .frame(height: 200)
                .accessibilityLabel("finance.chart.trend")
                if let selected {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(
                            selected.date,
                            format: insights.period.range == .year ? .dateTime.year().month() : .dateTime.month().day()
                        )
                        .font(.subheadline.weight(.semibold))
                        amountRow("finance.expense", selected.expense, color: FinanceVisualStyle.expense)
                        amountRow("finance.income", selected.income, color: FinanceVisualStyle.income)
                        ForEach(selected.categories.keys.sorted(), id: \.self) { category in
                            HStack {
                                categoryLabel(category)
                                Spacer()
                                Text(WorkspaceFormat.money(selected.categories[category] ?? 0, locale: locale))
                                    .monospacedDigit()
                            }.foregroundStyle(FinanceVisualStyle.category(category))
                        }
                    }
                    .font(.caption).padding(14)
                    .background(
                        Color(uiColor: .tertiarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
                } else {
                    Text("finance.chart.touch_hint").font(.caption).foregroundStyle(.secondary)
                }
                if !categories.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 14) {
                            ForEach(categories, id: \.self) { category in
                                HStack(spacing: 5) {
                                    Circle().fill(FinanceVisualStyle.category(category)).frame(width: 7, height: 7)
                                    categoryLabel(category)
                                }
                            }
                        }.font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .onChange(of: insights.period.key) { _, _ in selection = nil }
    }

    @ViewBuilder private var totals: some View {
        metric("finance.expense", insights.expense, FinanceVisualStyle.expense)
        metric("finance.income", insights.income, FinanceVisualStyle.income)
        metric("finance.chart.net", insights.income - insights.expense, .primary)
    }
    private func metric(_ title: LocalizedStringKey, _ amount: Int64, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(WorkspaceFormat.money(amount, locale: locale)).font(.subheadline.weight(.semibold)).monospacedDigit()
                .foregroundStyle(color)
        }
    }
    private func amountRow(_ title: LocalizedStringKey, _ amount: Int64, color: Color) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(WorkspaceFormat.money(amount, locale: locale)).monospacedDigit()
        }.foregroundStyle(color)
    }
    private var empty: some View {
        WorkspaceEmptyState(
            title: "finance.chart.empty", description: "finance.chart.empty.description", symbol: "chart.xyaxis.line")
    }
}

@ViewBuilder func categoryLabel(_ category: String) -> some View {
    if category.isEmpty { Text("finance.category.uncategorized") } else { Text(category) }
}
