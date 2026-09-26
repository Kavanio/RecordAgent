import Charts
import SwiftUI

/// 选中日及此前六天的真实数值趋势；无网格、外框和装饰性假进度。
struct HomeTrendChartView: View {
    enum Metric: String, CaseIterable, Identifiable {
        case expense, focus
        var id: String { rawValue }
        var title: LocalizedStringKey { self == .expense ? "home.chart.expense" : "home.chart.focus" }
        var color: Color { self == .expense ? HomeCategory.finance.color : HomeCategory.record.color }
        func value(_ point: HomeTrendPoint) -> Double {
            self == .expense ? Double(point.expenseInCents) / 100 : Double(point.focusMinutes)
        }
    }

    let points: [HomeTrendPoint]
    @State private var metric: Metric = .expense
    @State private var blend = 0.0
    @State private var selectedDate: Date?
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(points: [HomeTrendPoint], metric: Metric = .expense) {
        self.points = points
        _metric = State(initialValue: metric)
        _blend = State(initialValue: metric == .expense ? 0 : 1)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ViewThatFits(in: .horizontal) {
                HStack {
                    title
                    Spacer(minLength: 16)
                    controls
                }
                VStack(alignment: .leading, spacing: 4) {
                    title
                    controls
                }
            }
            VStack(spacing: 5) {
                ZStack {
                    if let selectedPoint {
                        HStack(spacing: 8) {
                            Text(selectedPoint.date, format: .dateTime.month().day())
                            Text(formatted(selectedPoint)).fontWeight(.semibold)
                        }
                        .font(.caption.monospacedDigit())
                        .padding(.horizontal, 11)
                        .padding(.vertical, 6)
                        .background(.thinMaterial, in: Capsule())
                        .accessibilityElement(children: .combine)
                    }
                }
                .frame(height: 32)
                .frame(maxWidth: .infinity)
                Chart {
                    ForEach(points) { point in
                        AreaMark(
                            x: .value(AppLocalization.string("home.chart.date", locale: locale), point.date),
                            y: .value(AppLocalization.string("home.chart.value", locale: locale), plotValue(point))
                        )
                        .interpolationMethod(.monotone)
                        .foregroundStyle(
                            LinearGradient(
                                colors: [metric.color.opacity(0.2), metric.color.opacity(0.01)], startPoint: .top,
                                endPoint: .bottom))
                        LineMark(
                            x: .value(AppLocalization.string("home.chart.date", locale: locale), point.date),
                            y: .value(AppLocalization.string("home.chart.value", locale: locale), plotValue(point))
                        )
                        .interpolationMethod(.monotone)
                        .lineStyle(StrokeStyle(lineWidth: 2.2, lineCap: .round))
                        .foregroundStyle(metric.color)
                        PointMark(
                            x: .value(AppLocalization.string("home.chart.date", locale: locale), point.date),
                            y: .value(AppLocalization.string("home.chart.value", locale: locale), plotValue(point))
                        )
                        .symbolSize(point.id == points.last?.id ? 34 : 12)
                        .foregroundStyle(metric.color)
                        .accessibilityLabel(Text(point.date, format: .dateTime.month().day()))
                        .accessibilityValue(formatted(point))
                    }
                    if let selectedPoint {
                        RuleMark(
                            x: .value(AppLocalization.string("home.chart.date", locale: locale), selectedPoint.date)
                        )
                        .foregroundStyle(.secondary.opacity(0.32))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    }
                }
                .chartXAxis(.hidden)
                .chartYAxis(.hidden)
                .chartYScale(domain: 0...1.15)
                .chartOverlay { proxy in
                    GeometryReader { geometry in
                        Color.clear
                            .contentShape(Rectangle())
                            .onContinuousHover { phase in
                                switch phase {
                                case .active(let location):
                                    guard let plotFrame = proxy.plotFrame else { return }
                                    let plotOrigin = geometry[plotFrame].origin.x
                                    selectedDate = proxy.value(atX: location.x - plotOrigin, as: Date.self)
                                case .ended:
                                    selectedDate = nil
                                }
                            }
                            .gesture(
                                DragGesture(minimumDistance: 0)
                                    .onChanged { value in
                                        guard let plotFrame = proxy.plotFrame else { return }
                                        selectedDate = proxy.value(
                                            atX: value.location.x - geometry[plotFrame].origin.x, as: Date.self)
                                    }
                                    .onEnded { _ in selectedDate = nil }
                            )
                    }
                }
                .accessibilityLabel(Text(metric.title))
                .frame(height: 82)
            }
            HStack {
                ForEach(points) { point in
                    Text(point.date, format: .dateTime.day())
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(point.id == points.last?.id ? metric.color : .secondary)
                        .frame(maxWidth: .infinity)
                }
            }
            .accessibilityHidden(true)
        }
        .animation(reduceMotion ? nil : .smooth(duration: 0.48), value: blend)
    }

    private var title: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("home.chart.title").font(.subheadline.weight(.medium))
            if let latest = points.last {
                Text(formatted(latest)).font(.caption).foregroundStyle(metric.color)
                    .contentTransition(.numericText())
            }
        }
    }
    private var controls: some View {
        Menu {
            ForEach(Metric.allCases) { item in
                Button {
                    selectedDate = nil
                    withAnimation(reduceMotion ? nil : .smooth(duration: 0.48)) {
                        blend = item == .expense ? 0 : 1
                        metric = item
                    }
                } label: {
                    if metric == item { Label(item.title, systemImage: "checkmark") } else { Text(item.title) }
                }
            }
        } label: {
            HStack(spacing: 4) {
                Text(metric.title)
                Image(systemName: "chevron.down").font(.system(size: 9, weight: .semibold))
            }
            .font(.caption.weight(.medium))
            .foregroundStyle(.secondary)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
    }
    private var selectedPoint: HomeTrendPoint? {
        guard let selectedDate else { return nil }
        return points.min {
            abs($0.date.timeIntervalSince(selectedDate)) < abs($1.date.timeIntervalSince(selectedDate))
        }
    }
    private func plotValue(_ point: HomeTrendPoint) -> Double {
        let expenseMax = max(points.map { Metric.expense.value($0) }.max() ?? 0, 1)
        let focusMax = max(points.map { Metric.focus.value($0) }.max() ?? 0, 1)
        return Metric.expense.value(point) / expenseMax * (1 - blend) + Metric.focus.value(point) / focusMax * blend
    }
    private func formatted(_ point: HomeTrendPoint) -> String {
        metric == .expense
            ? HomeFormat.money(point.expenseInCents, locale: locale)
            : HomeFormat.minutes(point.focusMinutes, locale: locale)
    }
}
