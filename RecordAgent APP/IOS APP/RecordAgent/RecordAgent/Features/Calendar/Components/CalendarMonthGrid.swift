import SwiftUI

struct CalendarMonthGrid: View {
    let selectedDate: Date
    let dates: HomeCalendar
    let markers: [CalendarDayMarker]
    let onSelect: (Date) -> Void
    let onShiftMonth: (Int) -> Void
    @ScaledMetric(relativeTo: .body) private var cellHeight = 44.0

    private var days: [Date] { dates.monthGrid(containing: selectedDate) }

    var body: some View {
        let lookup = Dictionary(uniqueKeysWithValues: markers.map { ($0.date, $0) })
        VStack(spacing: 8) {
            HStack(spacing: 0) {
                ForEach(Array(days.prefix(7)), id: \.self) { date in
                    Text(date, format: .dateTime.weekday(.narrow))
                        .font(.subheadline)
                        .foregroundStyle(CalendarPalette.secondary)
                        .frame(maxWidth: .infinity)
                }
            }
            .accessibilityHidden(true)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 7), spacing: 4) {
                ForEach(days, id: \.self) { date in
                    dayCell(date, marker: lookup[date])
                }
            }
        }
        .contentShape(Rectangle())
        .simultaneousGesture(
            DragGesture(minimumDistance: 24).onEnded { value in
                let horizontal = value.translation.width
                guard abs(horizontal) > 60,
                    abs(horizontal) > abs(value.translation.height) * 1.4
                else { return }
                onShiftMonth(horizontal < 0 ? 1 : -1)
            }
        )
    }

    private func dayCell(_ date: Date, marker: CalendarDayMarker?) -> some View {
        let selected = dates.calendar.isDate(date, inSameDayAs: selectedDate)
        let sameMonth = dates.calendar.isDate(date, equalTo: selectedDate, toGranularity: .month)
        let hasPhoto = marker?.sampleImageName != nil
        return Button {
            onSelect(date)
        } label: {
            VStack(spacing: 4) {
                Text(date, format: .dateTime.day())
                    .font(.body.weight(selected ? .semibold : .regular))
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
                    .foregroundStyle(
                        hasPhoto
                            ? .white
                            : selected
                                ? CalendarPalette.accent
                                : sameMonth ? CalendarPalette.ink : CalendarPalette.secondary.opacity(0.7))
                HStack(spacing: 3) {
                    ForEach(Array((marker?.kinds ?? []).enumerated()), id: \.offset) { _, kind in
                        Circle().fill(kind.calendarColor).frame(width: 4, height: 4)
                    }
                }
                .frame(height: 5)
                .accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity)
            .frame(height: max(44, cellHeight))
            .background {
                if let name = marker?.sampleImageName {
                    GeometryReader { geometry in
                        Image(name).resizable().scaledToFill()
                            .frame(width: geometry.size.width, height: geometry.size.height)
                            .overlay(.black.opacity(sameMonth ? 0.3 : 0.48)).clipped()
                    }
                } else if selected {
                    RoundedRectangle(cornerRadius: 10).fill(CalendarPalette.accent.opacity(0.12))
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(alignment: .bottom) {
                if dates.calendar.isDateInToday(date) && !selected {
                    Capsule().fill(CalendarPalette.accent).frame(width: 12, height: 2).padding(.bottom, 2)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!dates.supportedDates.contains(date))
        .accessibilityLabel(Text(date, format: .dateTime.year().month().day().weekday()))
        .accessibilityValue(
            Text(marker?.kinds.isEmpty == false ? "calendar.day.has_items" : "calendar.day.no_items")
        )
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
