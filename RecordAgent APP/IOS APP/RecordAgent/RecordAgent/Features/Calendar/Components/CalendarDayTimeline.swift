import SwiftUI

struct CalendarDayTimeline: View {
    let snapshot: HomeDaySnapshot
    let savingIDs: Set<String>
    let onOpen: (HomeEntry) -> Void
    let onToggle: (HomeEntry) -> Void
    @Environment(\.calendar) private var calendar
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .largeTitle) private var daySize = 60.0

    private var entries: [HomeEntry] { snapshot.entries.sorted { $0.date < $1.date } }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .center, spacing: 12) {
                    dateHeading
                    Spacer(minLength: 0)
                    daySummary
                }
                VStack(alignment: .leading, spacing: 12) {
                    dateHeading
                    daySummary
                }
            }
            if snapshot.entries.isEmpty {
                ContentUnavailableView(
                    "calendar.day.empty", systemImage: "sun.horizon",
                    description: Text("calendar.day.empty.detail"))
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                        timelineRow(entry, index: index)
                        if index + 1 < entries.count {
                            Color.clear.frame(
                                height: CalendarTimelineSpacing.gap(
                                    between: entry.date, and: entries[index + 1].date)
                            )
                            .accessibilityHidden(true)
                        }
                    }
                }
                .overlayPreferenceValue(TimelineNodeAnchors.self) { anchors in
                    GeometryReader { geometry in
                        Canvas { context, _ in
                            var path = Path()
                            var previous: (index: Int, frame: CGRect)?
                            for (index, entry) in entries.enumerated() {
                                guard let anchor = anchors[entry.id] else {
                                    previous = nil
                                    continue
                                }
                                let frame = geometry[anchor]
                                if let previous, previous.index + 1 == index {
                                    // 线段只连接两个图标外缘；图标本身保留空白，形成穿线节点。
                                    let start = CGPoint(x: previous.frame.midX, y: previous.frame.midY + 15)
                                    let end = CGPoint(x: frame.midX, y: frame.midY - 15)
                                    let distance = end.y - start.y
                                    let swing =
                                        min(27, max(10, distance * 0.2))
                                        * TimelineRail.bend(for: index)
                                    path.move(to: start)
                                    path.addCurve(
                                        to: end,
                                        control1: CGPoint(
                                            x: TimelineRail.clamp(start.x + swing), y: start.y + distance * 0.32),
                                        control2: CGPoint(
                                            x: TimelineRail.clamp(end.x + swing), y: end.y - distance * 0.32))
                                }
                                previous = (index, frame)
                            }
                            context.stroke(
                                path, with: .color(CalendarPalette.secondary.opacity(0.42)),
                                style: StrokeStyle(lineWidth: 2.5, lineCap: .round, dash: [2, 5]))
                        }
                    }
                    .accessibilityHidden(true)
                    .allowsHitTesting(false)
                }
            }
        }
    }

    private var dayNumber: some View {
        Text(calendar.component(.day, from: snapshot.date), format: .number)
            .font(.system(size: daySize, weight: .light, design: .serif))
            .monospacedDigit()
            .foregroundStyle(
                LinearGradient(
                    colors: [CalendarPalette.accent.opacity(0.45), CalendarPalette.accent],
                    startPoint: .topLeading, endPoint: .bottomTrailing)
            )
            .fixedSize()
    }

    private var dateHeading: some View {
        HStack(alignment: .center, spacing: 12) {
            dayNumber
            VStack(alignment: .leading, spacing: 3) {
                Text(snapshot.date, format: .dateTime.month(.abbreviated))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(CalendarPalette.secondary)
                Text(snapshot.date, format: .dateTime.weekday(.wide))
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(CalendarPalette.ink)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(snapshot.date, format: .dateTime.month().day().weekday(.wide)))
    }

    private var daySummary: some View {
        Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 10) {
            GridRow(alignment: .top) {
                summaryMetric("calendar.kind.todo", value: "\(snapshot.completedTodos)/\(snapshot.todos.count)", kind: .todo)
                summaryMetric(
                    "calendar.kind.expense",
                    value: HomeFormat.money(snapshot.expenseInCents, locale: locale), kind: .expense)
            }
            GridRow(alignment: .top) {
                summaryMetric(
                    "calendar.kind.income",
                    value: HomeFormat.money(snapshot.incomeInCents, locale: locale), kind: .income)
                summaryMetric(
                    "calendar.kind.schedule",
                    value: "\(snapshot.entries.filter { $0.kind == .schedule }.count)", kind: .schedule)
            }
        }
    }

    private func summaryMetric(_ title: LocalizedStringKey, value: String, kind: HomeEntry.Kind) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.caption2).foregroundStyle(CalendarPalette.secondary)
            Text(verbatim: value)
                .font(.subheadline.weight(.medium).monospacedDigit())
                .foregroundStyle(kind.calendarColor)
        }
        .fixedSize(horizontal: true, vertical: false)
        .accessibilityElement(children: .combine)
    }

    private func timelineRow(_ entry: HomeEntry, index: Int) -> some View {
        HStack(alignment: .top, spacing: 4) {
            HStack(spacing: 0) {
                Color.clear.frame(width: TimelineRail.center(for: index) - 14)
                Image(systemName: entry.kind.calendarSymbol)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(entry.kind.calendarColor)
                    .frame(width: 28, height: 44)
                    .anchorPreference(key: TimelineNodeAnchors.self, value: .bounds) { [entry.id: $0] }
                Spacer(minLength: 0)
            }
            .frame(width: TimelineRail.width, height: 44)
            .scrollTransition(.animated(.smooth(duration: 0.5)), axis: .vertical) { [reduceMotion] content, phase in
                content
                    .scaleEffect(reduceMotion || phase.isIdentity ? 1 : 0.55)
                    .opacity(reduceMotion || phase.isIdentity ? 1 : 0.35)
            }
            .accessibilityHidden(true)
            HStack(alignment: .top, spacing: 4) {
                Button {
                    onOpen(entry)
                } label: {
                    Group {
                        if typeSize.isAccessibilitySize {
                            VStack(alignment: .leading, spacing: 8) {
                                timeLabel(entry)
                                entryContent(entry)
                            }
                        } else {
                            HStack(alignment: .top, spacing: 16) {
                                timeLabel(entry).frame(minWidth: 48, alignment: .leading)
                                entryContent(entry).frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("home.entry.open")
                if entry.kind == .todo {
                    Button {
                        onToggle(entry)
                    } label: {
                        Group {
                            if savingIDs.contains(entry.id) {
                                ProgressView().controlSize(.small)
                            } else {
                                Image(systemName: entry.isCompleted ? "checkmark.circle.fill" : "circle")
                            }
                        }
                        .frame(width: 44, height: 44)
                        .foregroundStyle(entry.kind.calendarColor)
                    }
                    .buttonStyle(.plain)
                    .disabled(savingIDs.contains(entry.id))
                    .accessibilityLabel(Text(entry.title))
                    .accessibilityValue(Text(entry.isCompleted ? "home.todo.done" : "home.todo.pending"))
                    .accessibilityHint(Text(entry.isCompleted ? "home.todo.undo" : "home.todo.complete"))
                }
            }
            .scrollTransition(.animated(.smooth(duration: 0.5)), axis: .vertical) { [reduceMotion] content, phase in
                content
                    .opacity(reduceMotion || phase.isIdentity ? 1 : 0.12)
                    .offset(y: reduceMotion || phase.isIdentity ? 0 : 26)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func timeLabel(_ entry: HomeEntry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.date, format: .dateTime.hour().minute())
                .font(.subheadline.weight(.medium).monospacedDigit())
                .foregroundStyle(CalendarPalette.ink)
            Text(entry.kind.title).font(.caption).foregroundStyle(entry.kind.calendarColor)
        }
    }

    private func entryContent(_ entry: HomeEntry) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(entry.title).font(.subheadline.weight(.medium))
                .foregroundStyle(CalendarPalette.ink)
                .strikethrough(entry.kind == .todo && entry.isCompleted)
                .multilineTextAlignment(.leading)
            if !entry.sampleImageNames.isEmpty {
                HomePhotoStrip(names: entry.sampleImageNames)
            } else {
                switch entry.kind {
                case .expense, .income:
                    Text(
                        HomeFormat.money(
                            entry.kind == .expense ? -entry.amountInCents : entry.amountInCents, locale: locale)
                    )
                    .font(.caption).foregroundStyle(CalendarPalette.secondary)
                case .focus:
                    Text(HomeFormat.minutes(entry.durationMinutes, locale: locale))
                        .font(.caption).foregroundStyle(CalendarPalette.secondary)
                case .todo:
                    Text(entry.isCompleted ? "home.todo.done" : "home.todo.pending")
                        .font(.caption).foregroundStyle(CalendarPalette.secondary)
                default:
                    if !entry.detail.isEmpty {
                        Text(entry.detail).font(.caption).foregroundStyle(CalendarPalette.secondary)
                            .lineLimit(2).multilineTextAlignment(.leading)
                    }
                }
            }
        }
    }
}

/// 触控内容有最低高度，空白随相邻事件的真实分钟数增长，并在长间隔时收敛。
enum CalendarTimelineSpacing {
    static func gap(between earlier: Date, and later: Date) -> CGFloat {
        let minutes = max(0, later.timeIntervalSince(earlier) / 60)
        return min(124, max(12, CGFloat(minutes) * 0.28))
    }
}

private struct TimelineNodeAnchors: PreferenceKey {
    static var defaultValue: [String: Anchor<CGRect>] = [:]
    static func reduce(
        value: inout [String: Anchor<CGRect>], nextValue: () -> [String: Anchor<CGRect>]
    ) {
        value.merge(nextValue(), uniquingKeysWith: { _, newest in newest })
    }
}

private enum TimelineRail {
    static let width: CGFloat = 76
    private static let centers: [CGFloat] = [28, 46, 47, 31, 54, 42, 26, 36, 53, 40]
    private static let bends: [CGFloat] = [-1, 1, 1, -1, -1, 1, -1, 1, 1, -1]

    static func center(for index: Int) -> CGFloat { centers[index % centers.count] }
    static func bend(for index: Int) -> CGFloat { bends[index % bends.count] }
    static func clamp(_ x: CGFloat) -> CGFloat { min(width - 8, max(8, x)) }
}
