import SwiftUI

/// 只协调保存与错误状态；三种记录各自拥有独立的编辑布局。
struct CalendarCreateEntryView: View {
    @ObservedObject var model: CalendarViewModel
    let dates: HomeCalendar
    let isDemo: Bool
    let onCreated: (CalendarEntryDraft) async -> Void
    @State private var draft: CalendarEntryDraft
    @State private var isSubmitting = false
    @Environment(\.dismiss) private var dismiss

    init(
        model: CalendarViewModel, kind: CalendarEntryDraft.Kind, date: Date, dates: HomeCalendar,
        isDemo: Bool, onCreated: @escaping (CalendarEntryDraft) async -> Void
    ) {
        self.model = model
        self.dates = dates
        self.isDemo = isDemo
        self.onCreated = onCreated
        _draft = State(initialValue: CalendarEntryDraft(
            kind: kind, date: date, recurrenceWeekday: (dates.calendar.component(.weekday, from: date) + 5) % 7 + 1))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                switch draft.kind {
                case .todo:
                    CalendarTodoCreateView(draft: $draft, dates: dates)
                case .schedule:
                    CalendarScheduleCreateView(draft: $draft, dates: dates)
                case .note:
                    CalendarNoteCreateView(draft: $draft, dates: dates)
                }
                if isDemo {
                    Text("home.demo.footer")
                        .font(.footnote)
                        .foregroundStyle(AppColors.textSecondary)
                }
            }
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 26)
            .padding(.top, 28)
            .padding(.bottom, 28)
        }
        .disabled(isSubmitting)
        .scrollDismissesKeyboard(.interactively)
        .background { Color(uiColor: .systemBackground).ignoresSafeArea() }
        .navigationTitle(draft.kind.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("common.cancel") { dismiss() }.disabled(isSubmitting)
            }
        }
        .safeAreaInset(edge: .bottom) {
            AppButton("calendar.save", iconName: "checkmark", isLoading: isSubmitting,
                      isEnabled: draft.isValid, action: save)
                .frame(maxWidth: 640)
                .padding(.horizontal, 26)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity)
                .background { Color(uiColor: .systemBackground).ignoresSafeArea(edges: .bottom) }
        }
        .interactiveDismissDisabled(isSubmitting)
        .alert("home.write.failed", isPresented: $model.showWriteError) {
            Button("common.confirm", role: .cancel) {}
        } message: {
            Text("home.write.failed_detail")
        }
    }

    private func save() {
        guard draft.isValid && !isSubmitting else { return }
        isSubmitting = true
        Task {
            defer { isSubmitting = false }
            let submitted = draft
            if await model.create(submitted) {
                await onCreated(submitted)
            }
        }
    }
}
