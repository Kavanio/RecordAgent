import SwiftUI

/// 手记采用无卡片写作画布，与任务和日程的结构化表单区分。
struct CalendarNoteCreateView: View {
    @Binding var draft: CalendarEntryDraft
    let dates: HomeCalendar

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text("calendar.create.note.identity")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(CalendarPalette.accent)
                Text(draft.date, format: .dateTime.year().month(.wide).day().weekday(.wide))
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(CalendarPalette.ink)
                Text("calendar.create.note.subtitle")
                    .font(.subheadline)
                    .foregroundStyle(CalendarPalette.secondary)
            }

            TextField("calendar.create.note.title", text: $draft.title)
                .font(.system(size: 27, weight: .medium))
                .submitLabel(.next)
                .padding(.vertical, 12)
                .overlay(alignment: .bottom) { Rectangle().fill(AppColors.inputBorder).frame(height: 1) }

            ZStack(alignment: .topLeading) {
                if draft.detail.isEmpty {
                    Text("calendar.create.note.body")
                        .foregroundStyle(AppColors.placeholderText)
                        .padding(.top, 8)
                        .padding(.leading, 5)
                        .allowsHitTesting(false)
                }
                TextEditor(text: $draft.detail)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 270)
                    .accessibilityLabel("calendar.create.note.body")
            }
            .font(.body)
            .foregroundStyle(CalendarPalette.ink)

            HStack {
                DatePicker("home.date.choose", selection: $draft.date,
                           in: dates.supportedDateTimes, displayedComponents: .date)
                    .datePickerStyle(.compact)
                Spacer(minLength: 0)
                HStack(spacing: 3) {
                    Text(draft.detail.count, format: .number)
                    Text("calendar.create.note.characters")
                }
                .font(.footnote)
                .foregroundStyle(CalendarPalette.secondary)
            }
        }
    }
}
