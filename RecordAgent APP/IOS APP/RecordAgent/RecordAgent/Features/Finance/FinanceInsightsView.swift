import Charts
import SwiftUI

struct FinanceInsightsView: View {
    @ObservedObject var model: FinanceWorkspaceViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            VStack(alignment: .leading, spacing: 12) {
                FinanceChartTabs(
                    label: "finance.chart.trend", options: FinanceChartRange.allCases,
                    selection: $model.chartRange
                ) { $0.title }
                Text(
                    model.analysisPeriod
                        .start..<(model.analysisPeriod.calendar.date(
                            byAdding: .day, value: -1, to: model.analysisPeriod.end) ?? model.analysisPeriod.start),
                    format: .interval.day().month().year()
                )
                .font(.caption).foregroundStyle(.secondary)
            }
            if let error = model.visibleInsightsError {
                WorkspaceErrorView(message: error) { Task { await model.loadInsights() } }
            } else if let insights = model.visibleInsights {
                FinanceTrendChart(insights: insights)
                Divider().opacity(0.5)
                FinanceAccountChart(insights: insights)
                Divider().opacity(0.5)
                FinanceRankingChart(insights: insights)
            } else {
                ProgressView("finance.analysis.loading").frame(maxWidth: .infinity, minHeight: 100)
            }
        }
        .task(id: model.analysisKey) { await model.loadInsights() }
    }
}

struct FinanceTrendChart: View {
    let insights: FinanceInsights
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var selection: Double?
    @State private var revealed = false
    @State private var dismissalTask: Task<Void, Never>?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var motionKey: String {
        insights.period.key
            + insights.buckets.map { bucket in
                "\(bucket.expense):\(bucket.income):"
                    + bucket.categories.keys.sorted().map { "\($0):\(bucket.categories[$0] ?? 0)" }.joined(
                        separator: ",")
            }.joined(separator: "|")
    }

    private var selected: FinanceTrendBucket? {
        guard let selection, !insights.buckets.isEmpty else { return nil }
        return insights.buckets[max(0, min(insights.buckets.count - 1, Int(selection.rounded())))]
    }
    private var categories: [String] { insights.categories.map(\.category).sorted() }
    private var ceiling: Double {
        let maximum = insights.buckets.map { Double(max($0.expense, $0.income)) / 100 }.max() ?? 0
        guard maximum > 0 else { return 1 }
        let unit = pow(10, floor(log10(maximum)))
        return ceil(maximum * 1.25 / unit) * unit
    }
    private var tickIndices: [Int] {
        let last = max(0, insights.buckets.count - 1)
        return Array(Set([0, last / 3, last * 2 / 3, last])).sorted()
    }

    var body: some View {
        FinanceChartSection(title: "finance.chart.trend", symbol: "chart.xyaxis.line") {
            VStack(alignment: .leading, spacing: 10) {
                if typeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 12) { totals }
                } else {
                    HStack(alignment: .top, spacing: 20) { totals }
                }
                amountRow("finance.chart.net", insights.income - insights.expense, color: .secondary)
                    .font(.caption)
            }
            if insights.expense == 0 && insights.income == 0 {
                WorkspaceEmptyState(
                    title: "finance.chart.empty", description: "finance.chart.empty.description",
                    symbol: "chart.xyaxis.line")
            } else {
                Chart {
                    ForEach(Array(insights.buckets.enumerated()), id: \.element.id) { index, bucket in
                        if insights.expense > 0 {
                            AreaMark(
                                x: .value(axisDate, Double(index)),
                                y: .value(axisAmount, Double(bucket.expense) / 100)
                            )
                            .interpolationMethod(.monotone)
                            .foregroundStyle(
                                LinearGradient(
                                    colors: [FinanceVisualStyle.expense.opacity(0.18), FinanceVisualStyle.expense.opacity(0.01)],
                                    startPoint: .top,
                                    endPoint: .bottom
                                )
                            )

                            LineMark(
                                x: .value(axisDate, Double(index)),
                                y: .value(axisAmount, Double(bucket.expense) / 100)
                            )
                            .interpolationMethod(.monotone)
                            .lineStyle(StrokeStyle(lineWidth: 2.4, lineCap: .round, lineJoin: .round))
                            .foregroundStyle(FinanceVisualStyle.expense)

                            if bucket.expense > 0 {
                                PointMark(
                                    x: .value(axisDate, Double(index)),
                                    y: .value(axisAmount, Double(bucket.expense) / 100)
                                )
                                .foregroundStyle(FinanceVisualStyle.expense)
                                .symbolSize(20)
                            }
                        }
                        if insights.income > 0 {
                            LineMark(
                                x: .value(axisDate, Double(index)),
                                y: .value(axisAmount, Double(bucket.income) / 100)
                            )
                            .interpolationMethod(.monotone)
                            .lineStyle(StrokeStyle(lineWidth: 2.0, lineCap: .round, lineJoin: .round, dash: [4, 3]))
                            .foregroundStyle(FinanceVisualStyle.income)

                            if bucket.income > 0 {
                                PointMark(
                                    x: .value(axisDate, Double(index)),
                                    y: .value(axisAmount, Double(bucket.income) / 100)
                                )
                                .foregroundStyle(FinanceVisualStyle.income)
                                .symbol(.square)
                                .symbolSize(18)
                            }
                        }
                    }
                    if let selection, let selectedIndex = Int(selection.rounded()),
                       (0..<insights.buckets.count).contains(selectedIndex) {
                        let selectedBucket = insights.buckets[selectedIndex]
                        RuleMark(
                            x: .value(axisDate, Double(selectedIndex))
                        )
                        .foregroundStyle(Color.secondary.opacity(0.35))
                        .lineStyle(StrokeStyle(lineWidth: 1.2, dash: [3, 3]))

                        if insights.expense > 0 {
                            PointMark(
                                x: .value(axisDate, Double(selectedIndex)),
                                y: .value(axisAmount, Double(selectedBucket.expense) / 100)
                            )
                            .symbolSize(60)
                            .foregroundStyle(FinanceVisualStyle.expense.opacity(0.18))

                            PointMark(
                                x: .value(axisDate, Double(selectedIndex)),
                                y: .value(axisAmount, Double(selectedBucket.expense) / 100)
                            )
                            .symbolSize(24)
                            .foregroundStyle(FinanceVisualStyle.expense)
                        }

                        if insights.income > 0 && selectedBucket.income > 0 {
                            PointMark(
                                x: .value(axisDate, Double(selectedIndex)),
                                y: .value(axisAmount, Double(selectedBucket.income) / 100)
                            )
                            .symbolSize(50)
                            .foregroundStyle(FinanceVisualStyle.income.opacity(0.18))

                            PointMark(
                                x: .value(axisDate, Double(selectedIndex)),
                                y: .value(axisAmount, Double(selectedBucket.income) / 100)
                            )
                            .symbolSize(20)
                            .foregroundStyle(FinanceVisualStyle.income)
                        }
                    }
                }
                // 整数位置均匀分布自然日/月，两端各留半格，月末点和柱不会贴边裁切。
                .chartXScale(domain: -0.5...Double(max(1, insights.buckets.count)) - 0.5)
                .chartYScale(domain: 0...ceiling)
                .chartPlotStyle { plot in
                    plot.mask(Rectangle().scaleEffect(x: revealed || reduceMotion ? 1 : 0, anchor: .leading))
                }
                .chartOverlay { proxy in
                    GeometryReader { geometry in
                        ZStack(alignment: .topLeading) {
                            // 与首页共用交互方式：触摸和指针均由这一层映射到绘图区。
                            Color.clear.contentShape(Rectangle())
                                .gesture(
                                    DragGesture(minimumDistance: 0)
                                        .onChanged { value in
                                            dismissalTask?.cancel()
                                            select(at: value.location, proxy: proxy, geometry: geometry)
                                        }
                                        .onEnded { _ in dismissAfterDelay() }
                                )
                                .onContinuousHover { phase in
                                    dismissalTask?.cancel()
                                    switch phase {
                                    case .active(let point): select(at: point, proxy: proxy, geometry: geometry)
                                    case .ended: selection = nil
                                    }
                                }
                            if let selected, let selection, let frame = proxy.plotFrame,
                                let x = proxy.position(forX: selection.rounded())
                            {
                                let plot = geometry[frame]
                                let tooltipWidth: CGFloat = 164
                                let targetX = plot.minX + x - tooltipWidth / 2
                                let clampedX = max(6, min(geometry.size.width - tooltipWidth - 6, targetX))
                                FinanceTrendTooltip(bucket: selected, isYear: insights.period.range == .year) {
                                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                                        self.selection = nil
                                    }
                                }
                                .frame(width: tooltipWidth)
                                .offset(x: clampedX, y: 6)
                                .transition(reduceMotion ? .opacity : .scale(scale: 0.94).combined(with: .opacity))
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    }
                }
                .chartXAxis(.hidden)
                .chartYAxis {
                    AxisMarks(position: .leading, values: [0, ceiling / 2, ceiling]) { value in
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.08))
                        AxisValueLabel {
                            if let amount = value.as(Double.self) {
                                Text(
                                    amount.formatted(
                                        .number.notation(.compactName).precision(.fractionLength(0...1)).locale(locale))
                                )
                                .font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .frame(height: typeSize.isAccessibilitySize ? 240 : 210)
                .accessibilityLabel("finance.chart.trend")
                // 显式页脚不参与 Charts 的标签碰撞隐藏，始末日期始终可见。
                HStack(spacing: 0) {
                    ForEach(tickIndices, id: \.self) { index in
                        Text(
                            insights.buckets[index].date,
                            format: insights.period.range == .year ? .dateTime.month(.abbreviated) : .dateTime.day()
                        )
                        .frame(
                            maxWidth: .infinity,
                            alignment: index == 0 ? .leading : index == insights.buckets.count - 1 ? .trailing : .center
                        )
                    }
                }.font(.caption2).foregroundStyle(.secondary).padding(.leading, 30)

                Text("finance.chart.touch_hint").font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if !categories.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 16) {
                            ForEach(categories, id: \.self) { category in
                                HStack(spacing: 6) {
                                    Circle().fill(FinanceVisualStyle.category(category)).frame(width: 6, height: 6)
                                    categoryLabel(category)
                                }
                            }
                        }.font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .task(id: motionKey) {
            revealed = false
            guard !reduceMotion else {
                revealed = true
                return
            }
            do { try await Task.sleep(for: .milliseconds(30)) } catch { return }
            withAnimation(.easeOut(duration: 0.75)) { revealed = true }
        }
        .onChange(of: motionKey) { _, _ in
            dismissalTask?.cancel()
            selection = nil
        }
        .onDisappear { dismissalTask?.cancel() }
    }

    private func select(at point: CGPoint, proxy: ChartProxy, geometry: GeometryProxy) {
        guard let frame = proxy.plotFrame else { return }
        let plot = geometry[frame]
        guard plot.width > 0, point.y >= plot.minY, point.y <= plot.maxY else { return }
        let x = min(max(point.x - plot.minX, 0), plot.width)
        guard let value = proxy.value(atX: x, as: Double.self), value.isFinite else { return }
        withAnimation(reduceMotion ? nil : .interactiveSpring()) {
            selection = Double(max(0, min(insights.buckets.count - 1, Int(value.rounded()))))
        }
    }

    private func dismissAfterDelay() {
        dismissalTask?.cancel()
        dismissalTask = Task { @MainActor in
            do { try await Task.sleep(for: .seconds(2)) } catch { return }
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.28)) {
                selection = nil
            }
        }
    }

    private var axisDate: String { AppLocalization.string("finance.date", locale: locale) }
    private var axisAmount: String { AppLocalization.string("finance.amount", locale: locale) }
    @ViewBuilder private var totals: some View {
        metric("finance.expense", insights.expense, FinanceVisualStyle.expense, dashed: false)
        metric("finance.income", insights.income, FinanceVisualStyle.income, dashed: true)
    }
    private func metric(_ title: LocalizedStringKey, _ amount: Int64, _ color: Color, dashed: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Path { path in
                    path.move(to: .zero)
                    path.addLine(to: CGPoint(x: 14, y: 0))
                }
                .stroke(color, style: StrokeStyle(lineWidth: 2, dash: dashed ? [3, 2] : []))
                .frame(width: 14, height: 2).accessibilityHidden(true)
                Text(title).foregroundStyle(.secondary)
            }.font(.caption)
            Text(WorkspaceFormat.money(amount, locale: locale)).font(.title3.weight(.semibold))
                .monospacedDigit().foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.65)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private func amountRow(_ title: LocalizedStringKey, _ amount: Int64, color: Color) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(WorkspaceFormat.money(amount, locale: locale)).monospacedDigit()
        }
        .foregroundStyle(color)
    }
}

@ViewBuilder func categoryLabel(_ category: String) -> some View {
    if category.isEmpty { Text("finance.category.uncategorized") } else { Text(category) }
}
