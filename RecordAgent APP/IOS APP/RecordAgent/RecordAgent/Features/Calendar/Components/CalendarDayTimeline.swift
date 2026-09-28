import SwiftUI

struct CalendarDayTimeline: View {
    let snapshot: HomeDaySnapshot
    let savingIDs: Set<String>
    let onOpen: (HomeEntry) -> Void
    let onToggle: (HomeEntry) -> Void
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var typeSize
    @ScaledMetric(relativeTo: .largeTitle) private var daySize = 60.0

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
                VStack(spacing: 0) {
                    ForEach(snapshot.entries.sorted { $0.date < $1.date }) { entry in
                        timelineRow(entry)
                    }
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

    private func timelineRow(_ entry: HomeEntry) -> some View {
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
        .padding(.leading, 36)
        .padding(.bottom, 10)
        .background(alignment: .leading) {
            TimelineThread()
                .stroke(
                    CalendarPalette.secondary.opacity(0.18),
                    style: StrokeStyle(lineWidth: 3, lineCap: .round, dash: [2, 5])
                )
                .frame(width: 26)
                .overlay(alignment: .top) {
                    Circle().fill(entry.kind.calendarColor)
                        .overlay(Circle().stroke(Color(uiColor: .systemBackground), lineWidth: 2))
                        .frame(width: 11, height: 11).offset(y: 13)
                }
                .accessibilityHidden(true)
        }
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

private struct TimelineThread: Shape {
    func path(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: CGPoint(x: rect.midX, y: 0))
            path.addCurve(
                to: CGPoint(x: rect.midX, y: rect.maxY),
                control1: CGPoint(x: rect.minX, y: rect.height * 0.3),
                control2: CGPoint(x: rect.maxX, y: rect.height * 0.7))
        }
    }
}
