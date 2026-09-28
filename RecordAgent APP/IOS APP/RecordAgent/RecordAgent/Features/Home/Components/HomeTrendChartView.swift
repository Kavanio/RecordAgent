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
    @State private var selectedDate: Date?
    @State private var dismissTask: Task<Void, Never>?
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(points: [HomeTrendPoint], metric: Metric = .expense) {
        self.points = points
        _metric = State(initialValue: metric)
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
            VStack(spacing: 6) {
                tooltipBar
                chartView
            }
        }
        .onDisappear {
            dismissTask?.cancel()
            dismissTask = nil
        }
        .animation(reduceMotion ? nil : .smooth(duration: 0.48), value: metric)
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
                    dismissTask?.cancel()
                    dismissTask = nil
                    selectedDate = nil
                    withAnimation(reduceMotion ? nil : .smooth(duration: 0.48)) {
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

    private var tooltipBar: some View {
        ZStack {
            if let selectedPoint {
                HStack(spacing: 8) {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(metric.color)
                            .frame(width: 6, height: 6)
                        Text(metric.title)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(metric.color)
                    }
                    Rectangle()
                        .fill(Color.secondary.opacity(0.2))
                        .frame(width: 1, height: 10)
                    Text(selectedPoint.date, format: .dateTime.month().day())
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.secondary)
                    Text(formatted(selectedPoint))
                        .font(.system(size: 12, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(Color.primary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(.ultraThinMaterial)
                        .shadow(color: Color.black.opacity(0.06), radius: 8, x: 0, y: 2)
                        .overlay(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .strokeBorder(metric.color.opacity(0.25), lineWidth: 0.8)
                        )
                }
                .transition(
                    reduceMotion
                        ? .opacity
                        : .scale(scale: 0.92).combined(with: .opacity)
                )
                .accessibilityElement(children: .combine)
            }
        }
        .frame(height: 32)
        .frame(maxWidth: .infinity)
    }

    private var chartView: some View {
        Chart {
            ForEach(points) { point in
                AreaMark(
                    x: .value(AppLocalization.string("home.chart.date", locale: locale), point.date),
                    y: .value(AppLocalization.string("home.chart.value", locale: locale), metric.value(point))
                )
                .interpolationMethod(.monotone)
                .foregroundStyle(
                    LinearGradient(
                        colors: [metric.color.opacity(0.2), metric.color.opacity(0.01)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                LineMark(
                    x: .value(AppLocalization.string("home.chart.date", locale: locale), point.date),
                    y: .value(AppLocalization.string("home.chart.value", locale: locale), metric.value(point))
                )
                .interpolationMethod(.monotone)
                .lineStyle(StrokeStyle(lineWidth: 2.2, lineCap: .round))
                .foregroundStyle(metric.color)
                PointMark(
                    x: .value(AppLocalization.string("home.chart.date", locale: locale), point.date),
                    y: .value(AppLocalization.string("home.chart.value", locale: locale), metric.value(point))
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
                .foregroundStyle(metric.color.opacity(0.35))
                .lineStyle(StrokeStyle(lineWidth: 1.2, dash: [3, 3]))

                PointMark(
                    x: .value(AppLocalization.string("home.chart.date", locale: locale), selectedPoint.date),
                    y: .value(AppLocalization.string("home.chart.value", locale: locale), metric.value(selectedPoint))
                )
                .symbolSize(70)
                .foregroundStyle(metric.color.opacity(0.2))

                PointMark(
                    x: .value(AppLocalization.string("home.chart.date", locale: locale), selectedPoint.date),
                    y: .value(AppLocalization.string("home.chart.value", locale: locale), metric.value(selectedPoint))
                )
                .symbolSize(26)
                .foregroundStyle(metric.color)
            }
        }
        .chartXAxis {
            AxisMarks(values: points.map { $0.date }) { value in
                AxisGridLine().foregroundStyle(.clear)
                AxisTick().foregroundStyle(.clear)
                AxisValueLabel {
                    if let date = value.as(Date.self),
                        let point = points.first(where: { Calendar.current.isDate($0.date, inSameDayAs: date) })
                    {
                        Text(date, format: .dateTime.day())
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(point.id == points.last?.id ? metric.color : .secondary)
                    }
                }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5, dash: [3, 3]))
                    .foregroundStyle(Color.secondary.opacity(0.12))
                AxisValueLabel(anchor: .trailing) {
                    if let doubleVal = value.as(Double.self) {
                        Text(formatYAxis(doubleVal))
                            .font(.system(size: 9, weight: .regular, design: .monospaced))
                            .foregroundStyle(Color.secondary.opacity(0.75))
                    }
                }
            }
        }
        .chartYScale(domain: 0...(maxYValue * 1.15))
        .chartOverlay { proxy in
            GeometryReader { geometry in
                Color.clear
                    .contentShape(Rectangle())
                    .onContinuousHover { phase in
                        switch phase {
                        case .active(let location):
                            dismissTask?.cancel()
                            dismissTask = nil
                            guard let plotFrame = proxy.plotFrame else { return }
                            let plotOrigin = geometry[plotFrame].origin.x
                            withAnimation(reduceMotion ? nil : .interactiveSpring()) {
                                selectedDate = proxy.value(atX: location.x - plotOrigin, as: Date.self)
                            }
                        case .ended:
                            scheduleDismiss()
                        }
                    }
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                dismissTask?.cancel()
                                dismissTask = nil
                                guard let plotFrame = proxy.plotFrame else { return }
                                withAnimation(reduceMotion ? nil : .interactiveSpring()) {
                                    selectedDate = proxy.value(
                                        atX: value.location.x - geometry[plotFrame].origin.x, as: Date.self)
                                }
                            }
                            .onEnded { _ in
                                scheduleDismiss()
                            }
                    )
            }
        }
        .accessibilityLabel(Text(metric.title))
        .frame(height: 106)
    }

    private var selectedPoint: HomeTrendPoint? {
        guard let selectedDate else { return nil }
        return points.min {
            abs($0.date.timeIntervalSince(selectedDate)) < abs($1.date.timeIntervalSince(selectedDate))
        }
    }

    private var maxYValue: Double {
        let maxVal = points.map { metric.value($0) }.max() ?? 0
        return metric == .expense ? max(maxVal, 50) : max(maxVal, 30)
    }

    private func scheduleDismiss() {
        dismissTask?.cancel()
        dismissTask = Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if !Task.isCancelled {
                withAnimation(reduceMotion ? nil : .easeOut(duration: 0.28)) {
                    selectedDate = nil
                }
            }
        }
    }

    private func formatYAxis(_ value: Double) -> String {
        if metric == .expense {
            if value >= 10000 {
                return String(format: "¥%.1fw", value / 10000)
            } else if value >= 1000 {
                return String(format: "¥%.0f", value)
            } else {
                return "¥\(Int(round(value)))"
            }
        } else {
            if value >= 60 {
                let h = Int(value) / 60
                let m = Int(value) % 60
                return m > 0 ? "\(h)h\(m)" : "\(h)h"
            } else {
                return "\(Int(round(value)))m"
            }
        }
    }

    private func formatted(_ point: HomeTrendPoint) -> String {
        metric == .expense
            ? HomeFormat.money(point.expenseInCents, locale: locale)
            : HomeFormat.minutes(point.focusMinutes, locale: locale)
    }
}
