import SwiftUI

enum RecordEditorMode: String, Identifiable {
    case focus
    case manual
    var id: String { rawValue }
    var title: LocalizedStringKey { self == .focus ? "record.start" : "record.manual" }
}

struct RecordEditorView: View {
    let mode: RecordEditorMode
    @ObservedObject var model: RecordWorkspaceViewModel
    let onChanged: () -> Void
    @Environment(\.locale) private var locale
    @State private var title = ""
    @State private var start = Date.now.addingTimeInterval(-30 * 60)
    @State private var end = Date.now

    private var isValid: Bool {
        StudyRules.isValidTitle(title) && !model.isSaving
            && (mode == .focus || StudyRules.isValidInterval(start: start, end: end))
            && (mode != .focus || model.active == nil)
    }

    var body: some View {
        WorkspaceEditor(title: mode.title, isValid: isValid, onSave: {
            do {
                try await model.create(title: title, start: mode == .manual ? start : nil,
                                       end: mode == .manual ? end : nil)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                throw RecordEditorSaveError(message: RecordFormatting.message(
                    RecordWorkspaceViewModel.errorKey(error), locale: locale))
            }
            onChanged()
        }) {
            WorkspaceTextField(title: "record.title", text: $title, axis: .vertical)
            if mode == .manual {
                DatePicker("record.started_at", selection: $start, in: ...Date.now,
                           displayedComponents: [.date, .hourAndMinute])
                DatePicker("record.ended_at", selection: $end, in: ...Date.now,
                           displayedComponents: [.date, .hourAndMinute])
                Text("record.manual_description").font(.footnote).foregroundStyle(.secondary)
                if !StudyRules.isValidInterval(start: start, end: end) {
                    Text("record.error.interval").font(.footnote).foregroundStyle(.red)
                }
            } else {
                Text("record.start_description").font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

private struct RecordEditorSaveError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}
