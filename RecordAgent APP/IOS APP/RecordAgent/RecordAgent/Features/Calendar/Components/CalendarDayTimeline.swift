import SwiftUI

struct CalendarDayTimeline: View {
    let snapshot: HomeDaySnapshot
    let savingIDs: Set<String>
    let onOpen: (HomeEntry) -> Void
    let onToggle: (HomeEntry) -> Void
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .largeTitle) private var daySize = 60.0

    private var entries: [HomeEntry] { snapshot.entries.sorted { $0.date < $1.date } }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .center, spacing: 14) {
                    dayNumber
                    dayHeading
                }
                VStack(alignment: .leading, spacing: 4) {
                    dayNumber
                    dayHeading
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
                .backgroundPreferenceValue(TimelineNodeAnchors.self) { anchors in
                    GeometryReader { geometry in
                        Canvas { context, _ in
                            var path = Path()
                            var previous: (index: Int, point: CGPoint)?
                            for (index, entry) in entries.enumerated() {
                                guard let anchor = anchors[entry.id] else {
                                    previous = nil
                                    continue
                                }
                                let frame = geometry[anchor]
                                let point = CGPoint(x: frame.midX, y: frame.midY)
                                if let previous, previous.index + 1 == index {
                                    let distance = point.y - previous.point.y
                                    let drift: CGFloat = index.isMultiple(of: 2) ? 11 : -9
                                    path.move(to: previous.point)
                                    path.addCurve(
                                        to: point,
                                        control1: CGPoint(
                                            x: previous.point.x + drift, y: previous.point.y + distance * 0.36),
                                        control2: CGPoint(x: point.x - drift * 0.7, y: point.y - distance * 0.32))
                                }
                                previous = (index, point)
                            }
                            context.stroke(
                                path, with: .color(CalendarPalette.secondary.opacity(0.23)),
                                style: StrokeStyle(lineWidth: 3, lineCap: .round, dash: [1, 6]))
                        }
                    }
                    .accessibilityHidden(true)
                }
            }
        }
    }

    private var dayNumber: some View {
        Text(snapshot.date, format: .dateTime.day())
            .font(.system(size: daySize, weight: .light, design: .serif).italic())
            .foregroundStyle(
                LinearGradient(
                    colors: [CalendarPalette.accent.opacity(0.45), CalendarPalette.accent],
                    startPoint: .topLeading, endPoint: .bottomTrailing)
            )
            .accessibilityHidden(true)
    }

    private var dayHeading: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(snapshot.date, format: .dateTime.month().day().weekday(.wide))
                .font(.headline).foregroundStyle(CalendarPalette.ink)
            Text(
                "calendar.day.summary \(snapshot.completedTodos) \(snapshot.todos.count) \(HomeFormat.money(snapshot.expenseInCents, locale: locale)) \(snapshot.entries.filter { $0.kind == .schedule }.count)"
            )
            .font(.caption).foregroundStyle(CalendarPalette.secondary)
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func timelineRow(_ entry: HomeEntry, index: Int) -> some View {
        HStack(alignment: .top, spacing: 4) {
            Circle().fill(entry.kind.calendarColor)
                .frame(width: 12, height: 12)
                .overlay(Circle().stroke(Color(uiColor: .systemBackground), lineWidth: 2))
                .frame(width: 28, height: 44)
                .offset(x: TimelineThread.horizontalOffset(for: index))
                .scrollTransition(.animated(.smooth(duration: 0.5)), axis: .vertical) { content, phase in
                    content
                        .scaleEffect(reduceMotion || phase.isIdentity ? 1 : 0.55)
                        .opacity(reduceMotion || phase.isIdentity ? 1 : 0.35)
                }
                .anchorPreference(key: TimelineNodeAnchors.self, value: .bounds) { [entry.id: $0] }
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
            .scrollTransition(.animated(.smooth(duration: 0.5)), axis: .vertical) { content, phase in
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
    static func reduce(value: inout [String: Anchor<CGRect>], nextValue: () -> [String: Anchor<CGRect>]) {
        value.merge(nextValue(), uniquingKeysWith: { _, newest in newest })
    }
}

private enum TimelineThread {
    static func horizontalOffset(for index: Int) -> CGFloat {
        let offsets: [CGFloat] = [-3, 4, -1, 5, -4, 2, 0]
        return offsets[index % offsets.count]
    }
}
