import SwiftUI

/// 待办以事项为核心，时间和周期规则为可选安排。
struct CalendarTodoCreateView: View {
    @Binding var draft: CalendarEntryDraft
    let dates: HomeCalendar

    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            HStack(alignment: .firstTextBaseline, spacing: 14) {
                Image(systemName: "circle")
                    .font(.system(size: 19, weight: .ultraLight))
                    .foregroundStyle(CalendarPalette.accent)
                TextField("calendar.create.todo.placeholder", text: $draft.title)
                    .font(.system(size: 18, weight: .medium))
                    .submitLabel(.done)
            }
            .padding(.vertical, 12)
            .overlay(alignment: .bottom) { Rectangle().fill(AppColors.inputBorder).frame(height: 1) }

            DatePicker("calendar.create.todo.when", selection: $draft.date,
                       in: dates.supportedDateTimes, displayedComponents: [.date, .hourAndMinute])
                .datePickerStyle(.compact)

            VStack(alignment: .leading, spacing: 14) {
                Label("calendar.create.repeat", systemImage: "repeat")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(CalendarPalette.ink)
                HStack(spacing: 8) {
                    repeatOption("calendar.repeat.none", period: nil)
                    repeatOption("calendar.repeat.week", period: .weekly)
                    repeatOption("calendar.repeat.month", period: .monthly)
                }
            }

            TextField("calendar.create.detail", text: $draft.detail, axis: .vertical)
                .lineLimit(2...4)
                .padding(14)
                .background(AppColors.segmentBarBg, in: RoundedRectangle(cornerRadius: 14))
        }
    }

    private func repeatOption(_ title: LocalizedStringKey, period: CalendarTodoPeriod?) -> some View {
        let selected = draft.period == period
        return Button { draft.period = period } label: {
            Text(title)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, minHeight: 44)
                .foregroundStyle(selected ? CalendarPalette.accent : CalendarPalette.secondary)
                .background(selected ? CalendarPalette.accent.opacity(0.1) : AppColors.segmentBarBg,
                            in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
