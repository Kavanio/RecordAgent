import SwiftUI

struct MobileTaskEditorView: View {
    @State private var draft: MobileTask
    @State private var choosingTimeZone = false
    let creating: Bool
    let onSave: (MobileTask) async throws -> Void
    @Environment(\.locale) private var locale

    init(task: MobileTask, creating: Bool, onSave: @escaping (MobileTask) async throws -> Void) {
        _draft = State(initialValue: task)
        self.creating = creating
        self.onSave = onSave
    }

    private var zone: TimeZone { TimeZone(identifier: draft.timeZone) ?? .current }
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        return calendar
    }

    private var isValid: Bool {
        let title = draft.title.trimmingCharacters(in: .whitespacesAndNewlines)
        return !title.isEmpty && title.utf16.count <= 120 && draft.body.utf16.count <= 500 &&
            (try? MobileTaskSchedule.components(for: normalizedDraft)) != nil &&
            (!draft.enabled || MobileTaskSchedule.nextDate(for: normalizedDraft) != nil)
    }

    private var normalizedDraft: MobileTask {
        var result = draft
        result.title = result.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if result.recurrence != .once { result.onceDate = nil }
        if result.recurrence != .weekly { result.weekday = nil }
        if result.recurrence != .monthly { result.dayOfMonth = nil }
        return result
    }

    private var onceDate: Binding<Date> {
        Binding {
            MobileTaskSchedule.date(draft.onceDate ?? MobileTaskSchedule.dayKey(.now, in: zone), in: zone) ?? .now
        } set: { draft.onceDate = MobileTaskSchedule.dayKey($0, in: zone) }
    }

    private var reminderTime: Binding<Date> {
        Binding {
            var parts = calendar.dateComponents([.year, .month, .day], from: .now)
            parts.hour = draft.hour
            parts.minute = draft.minute
            return calendar.date(from: parts) ?? .now
        } set: {
            let parts = calendar.dateComponents([.hour, .minute], from: $0)
            draft.hour = parts.hour ?? 9
            draft.minute = parts.minute ?? 0
        }
    }

    var body: some View {
        WorkspaceEditor(title: creating ? LocalizedStringKey("tasks.create") : LocalizedStringKey("tasks.edit"),
                        isValid: isValid, onSave: { try await onSave(normalizedDraft) }) {
            WorkspaceTextField(title: "tasks.editor.title", text: $draft.title)
            WorkspaceTextField(title: "tasks.editor.body", text: $draft.body, axis: .vertical)
            Text("tasks.editor.content_limit").font(.footnote).foregroundStyle(.secondary)
            WorkspaceSection(title: "tasks.schedule.title") {
                WorkspaceFilters(options: MobileTaskRecurrence.allCases, selection: $draft.recurrence) { $0.title }
                DatePicker("tasks.schedule.time", selection: reminderTime, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.compact).environment(\.timeZone, zone)
                if draft.recurrence == .once {
                    DatePicker("tasks.schedule.date", selection: onceDate, displayedComponents: .date)
                        .datePickerStyle(.compact).environment(\.timeZone, zone)
                    if draft.enabled && MobileTaskSchedule.nextDate(for: normalizedDraft) == nil {
                        Text("tasks.editor.future_date").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if draft.recurrence == .weekly {
                    Picker("tasks.schedule.weekday", selection: Binding(get: { draft.weekday ?? 1 }, set: { draft.weekday = $0 })) {
                        ForEach(1...7, id: \.self) { day in Text(MobileTaskPresentation.weekday(day, locale: locale)).tag(day) }
                    }
                    .pickerStyle(.menu)
                }
                if draft.recurrence == .monthly {
                    Stepper(value: Binding(get: { draft.dayOfMonth ?? 1 }, set: { draft.dayOfMonth = $0 }), in: 1...28) {
                        Text("tasks.schedule.month_day \(draft.dayOfMonth ?? 1)")
                    }
                    Text("tasks.schedule.month_day_hint").font(.footnote).foregroundStyle(.secondary)
                }
                Button { choosingTimeZone = true } label: {
                    HStack {
                        Text("tasks.timezone").foregroundStyle(.primary)
                        Spacer(minLength: 16)
                        Text(draft.timeZone).foregroundStyle(.secondary)
                        Image(systemName: "chevron.right").font(.caption)
                    }
                }
                .frame(minHeight: 44)
                Text("tasks.timezone.description").font(.footnote).foregroundStyle(.secondary)
            }
            Toggle("tasks.editor.enabled", isOn: $draft.enabled)
            Text("tasks.editor.permission").font(.footnote).foregroundStyle(.secondary)
        }
        .onChange(of: draft.recurrence) { _, recurrence in
            switch recurrence {
            case .once: if draft.onceDate == nil { draft.onceDate = MobileTaskSchedule.dayKey(.now, in: zone) }
            case .weekly: if draft.weekday == nil { draft.weekday = 1 }
            case .monthly: if draft.dayOfMonth == nil { draft.dayOfMonth = 1 }
            case .daily: break
            }
        }
        .sheet(isPresented: $choosingTimeZone) { MobileTaskTimeZoneView(selection: $draft.timeZone) }
    }
}

private struct MobileTaskTimeZoneView: View {
    @Binding var selection: String
    @State private var query = ""
    @Environment(\.dismiss) private var dismiss
    private var identifiers: [String] {
        let all = TimeZone.knownTimeZoneIdentifiers
        return query.isEmpty ? all : all.filter { $0.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        NavigationStack {
            List(identifiers, id: \.self) { identifier in
                Button {
                    selection = identifier
                    dismiss()
                } label: {
                    HStack {
                        Text(identifier).foregroundStyle(.primary)
                        Spacer()
                        if selection == identifier { Image(systemName: "checkmark").foregroundStyle(AppColors.primaryBlue) }
                    }
                    .frame(minHeight: 44)
                }
                .accessibilityAddTraits(selection == identifier ? .isSelected : [])
            }
            .listStyle(.plain)
            .searchable(text: $query, prompt: "tasks.timezone.search")
            .navigationTitle("tasks.timezone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("workspace.close") { dismiss() } } }
        }
    }
}
