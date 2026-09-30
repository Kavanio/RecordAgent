import SwiftUI

struct LibraryEditorView: View {
    let onSaved: (LibraryDocument) async -> Void
    @StateObject private var model: LibraryEditorViewModel
    @State private var date: Date
    @State private var showsAdoptConfirmation = false

    init(draft: LibraryDraft, creating: Bool, service: any LibraryServiceProtocol,
         onSaved: @escaping (LibraryDocument) async -> Void) {
        self.onSaved = onSaved
        _model = StateObject(wrappedValue: LibraryEditorViewModel(draft: draft, creating: creating, service: service))
        _date = State(initialValue: WorkspaceFormat.date(draft.date) ?? .now)
    }

    var body: some View {
        WorkspaceEditor(title: model.creating ? "library.create" : "library.edit",
                        isValid: model.draft.isValid && !model.isLoadingLatest, onSave: save) {
            WorkspaceTextField(title: "library.title", text: $model.draft.title)
                .font(.title3.weight(.medium))
            HStack {
                Text("library.type").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Picker("library.type", selection: $model.draft.type) {
                    ForEach(LibraryDocumentKind.allCases) { Text($0.title).tag($0) }
                }.pickerStyle(.menu)
            }
            DatePicker("library.date", selection: $date, in: HomeCalendar(calendar: .current).supportedDates,
                       displayedComponents: .date)
            VStack(alignment: .leading, spacing: 12) {
                Text("library.body").font(.caption).foregroundStyle(.secondary)
                TextField("library.body.prompt", text: $model.draft.body, axis: .vertical)
                    .lineLimit(12...40).lineSpacing(7).font(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !model.draft.isValid && (!model.draft.title.isEmpty || !model.draft.body.isEmpty) {
                Text("library.validation").font(.footnote).foregroundStyle(.secondary)
            }
            if model.hasConflict { conflictReview }
        }
        .confirmationDialog("library.conflict.adopt.confirm", isPresented: $showsAdoptConfirmation, titleVisibility: .visible) {
            Button("library.conflict.adopt", role: .destructive) {
                model.adoptLatest()
                if let latestDate = WorkspaceFormat.date(model.draft.date) { date = latestDate }
            }
            Button("workspace.cancel", role: .cancel) {}
        }
    }

    private func save() async throws {
        model.draft.date = WorkspaceFormat.dayKey(date)
        let document = try await model.save()
        await onSaved(document)
    }

    private var conflictReview: some View {
        WorkspaceSection(title: "library.conflict.title") {
            Text("library.conflict.description").font(.subheadline).foregroundStyle(.secondary)
            if let latest = model.latest {
                VStack(alignment: .leading, spacing: 10) {
                    Text("library.conflict.latest").font(.caption).foregroundStyle(.secondary)
                    Text(latest.title).font(.subheadline.weight(.medium))
                    Text(latest.body).font(.subheadline).foregroundStyle(.secondary).lineLimit(8)
                    if latest.trashed {
                        Text("library.document.trashed").font(.footnote).foregroundStyle(.secondary)
                    } else {
                        Button("library.conflict.adopt") { showsAdoptConfirmation = true }.frame(minHeight: 44)
                    }
                }
            } else {
                Button("library.conflict.compare") { Task { await model.loadLatest() } }
                    .frame(minHeight: 44).disabled(model.isLoadingLatest)
            }
            if let error = model.comparisonError {
                WorkspaceErrorView(message: error) { Task { await model.loadLatest() } }
            }
            if model.isLoadingLatest { ProgressView() }
            Button("library.conflict.copy") { model.keepDraftAsNew() }.frame(minHeight: 44)
        }
    }
}
