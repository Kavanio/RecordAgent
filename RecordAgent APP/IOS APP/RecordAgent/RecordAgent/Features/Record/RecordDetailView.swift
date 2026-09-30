import SwiftUI

struct RecordDetailView: View {
    let entry: StudyEntry
    @ObservedObject var model: RecordWorkspaceViewModel
    let onChanged: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var confirmsDelete = false
    @State private var errorKey: String?

    private var current: StudyEntry {
        if model.active?.id == entry.id { return model.active ?? entry }
        return model.entries.first { $0.id == entry.id } ?? entry
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    Text(current.title).font(.title2.weight(.medium))
                    WorkspaceSection(title: "record.time") {
                        VStack(alignment: .leading, spacing: 18) {
                            dateValue("record.started_at", date: current.startedAt)
                            if let end = current.endedAt {
                                dateValue("record.ended_at", date: end)
                                WorkspaceValue(label: "record.duration", value: RecordFormatting.duration(current.durationSeconds(), locale: locale))
                            } else {
                                Text("record.in_progress").foregroundStyle(.secondary)
                            }
                            WorkspaceValue(label: "record.time_zone", value: current.timeZone)
                        }
                    }
                    Divider()
                    Button("workspace.delete", role: .destructive) { confirmsDelete = true }
                        .frame(minHeight: 44)
                        .disabled(model.isSaving)
                    if let key = errorKey {
                        Text(RecordFormatting.message(key, locale: locale))
                            .font(.footnote).foregroundStyle(.red)
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Color(uiColor: .systemBackground).ignoresSafeArea())
            .navigationTitle("record.detail")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("workspace.close") { dismiss() }.disabled(model.isSaving)
                }
            }
            .confirmationDialog("record.delete_confirm", isPresented: $confirmsDelete, titleVisibility: .visible) {
                Button("workspace.delete", role: .destructive) {
                    Task {
                        errorKey = nil
                        do { try await model.delete(current); onChanged(); dismiss() }
                        catch is CancellationError { }
                        catch { errorKey = RecordWorkspaceViewModel.errorKey(error) }
                    }
                }
                Button("workspace.cancel", role: .cancel) { }
            } message: {
                Text("record.delete_description")
            }
            .interactiveDismissDisabled(model.isSaving)
        }
    }

    private func dateValue(_ title: LocalizedStringKey, date: Date) -> some View {
        var format = Date.FormatStyle(date: .abbreviated, time: .shortened)
        format.locale = locale
        format.timeZone = TimeZone(identifier: current.timeZone) ?? .current
        return VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(date.formatted(format))
                .font(.body).textSelection(.enabled)
        }
    }
}
