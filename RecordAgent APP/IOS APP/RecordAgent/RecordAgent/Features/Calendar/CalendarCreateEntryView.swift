import SwiftUI

struct CalendarCreateEntryView: View {
    @ObservedObject var model: CalendarViewModel
    let dates: HomeCalendar
    let isDemo: Bool
    let onCreated: (CalendarEntryDraft) async -> Void
    @State private var draft: CalendarEntryDraft
    @State private var isSubmitting = false
    @Environment(\.dismiss) private var dismiss

    init(
        model: CalendarViewModel, date: Date, dates: HomeCalendar, isDemo: Bool,
        onCreated: @escaping (CalendarEntryDraft) async -> Void
    ) {
        self.model = model
        self.dates = dates
        self.isDemo = isDemo
        self.onCreated = onCreated
        _draft = State(initialValue: CalendarEntryDraft(date: date))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("calendar.create.kind", selection: $draft.kind) {
                        ForEach(CalendarEntryDraft.Kind.allCases) { kind in Text(kind.title).tag(kind) }
                    }
                    TextField("calendar.create.title", text: $draft.title)
                    TextField("calendar.create.detail", text: $draft.detail, axis: .vertical).lineLimit(3...6)
                    DatePicker(
                        "home.date.choose", selection: $draft.date, in: dates.supportedDateTimes,
                        displayedComponents: [.date, .hourAndMinute])
                    if draft.kind == .todo {
                        Picker("calendar.create.repeat", selection: $draft.period) {
                            Text("calendar.repeat.none").tag(Optional<CalendarTodoPeriod>.none)
                            Text("calendar.repeat.week").tag(Optional(CalendarTodoPeriod.weekly))
                            Text("calendar.repeat.month").tag(Optional(CalendarTodoPeriod.monthly))
                        }
                    }
                } footer: {
                    if draft.kind == .todo && draft.period != nil { Text("calendar.create.period_hint") }
                }
                if isDemo {
                    Section { Text("home.demo.footer").font(.footnote).foregroundStyle(.secondary) }
                }
            }
            .disabled(isSubmitting)
            .navigationTitle("calendar.create")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") { dismiss() }.disabled(isSubmitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        guard !isSubmitting else { return }
                        isSubmitting = true
                        Task {
                            defer { isSubmitting = false }
                            let submitted = draft
                            if await model.create(submitted) {
                                await onCreated(submitted)
                                dismiss()
                            }
                        }
                    } label: {
                        if isSubmitting { ProgressView() } else { Text("calendar.save") }
                    }
                    .disabled(!draft.isValid || isSubmitting)
                }
            }
        }
        .interactiveDismissDisabled(isSubmitting)
        .alert("home.write.failed", isPresented: $model.showWriteError) {
            Button("common.confirm", role: .cancel) {}
        } message: {
            Text("home.write.failed_detail")
        }
    }
}
