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
            Chart(points) { point in
                AreaMark(
                    x: .value(AppLocalization.string("home.chart.date", locale: locale), point.date),
                    y: .value(AppLocalization.string("home.chart.value", locale: locale), metric.value(point))
                )
                .interpolationMethod(.monotone)
                .foregroundStyle(
                    LinearGradient(
                        colors: [metric.color.opacity(0.2), metric.color.opacity(0.01)], startPoint: .top,
                        endPoint: .bottom))
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
            .chartXAxis(.hidden)
            .chartYAxis(.hidden)
            .chartYScale(domain: 0...max((points.map { metric.value($0) }.max() ?? 0) * 1.15, 1))
            .frame(height: 82)
            .accessibilityLabel(Text(metric.title))
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
        .animation(reduceMotion ? nil : .smooth(duration: 0.35), value: metric)
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
        HStack(spacing: 16) {
            ForEach(Metric.allCases) { item in
                Button {
                    metric = item
                } label: {
                    Text(item.title)
                        .font(.caption.weight(metric == item ? .semibold : .regular))
                        .foregroundStyle(metric == item ? item.color : .secondary)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(metric == item ? .isSelected : [])
            }
        }
    }
    private func formatted(_ point: HomeTrendPoint) -> String {
        metric == .expense
            ? HomeFormat.money(point.expenseInCents, locale: locale)
            : HomeFormat.minutes(point.focusMinutes, locale: locale)
    }
}
