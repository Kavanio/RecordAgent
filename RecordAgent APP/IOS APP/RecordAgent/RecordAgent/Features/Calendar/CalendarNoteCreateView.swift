import SwiftUI

/// 手记采用无卡片写作画布，与任务和日程的结构化表单区分。
struct CalendarNoteCreateView: View {
    @Binding var draft: CalendarEntryDraft
    let dates: HomeCalendar

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            TextField("calendar.create.note.title", text: $draft.title)
                .font(.system(size: 22, weight: .medium))
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

            DatePicker("home.date.choose", selection: $draft.date,
                       in: dates.supportedDateTimes, displayedComponents: .date)
                .datePickerStyle(.compact)
        }
    }
}
