import SwiftUI

struct LibraryDetailView: View {
    let onChanged: () async -> Void
    @StateObject private var model: LibraryDetailViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showsEdit = false
    @State private var showsTrashConfirmation = false

    init(id: String, service: any LibraryServiceProtocol, onChanged: @escaping () async -> Void) {
        self.onChanged = onChanged
        _model = StateObject(wrappedValue: LibraryDetailViewModel(id: id, service: service))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    if model.isLoading && model.document == nil { ProgressView() }
                    if let error = model.errorMessage {
                        WorkspaceErrorView(message: error) { Task { await model.load() } }
                    }
                    if let document = model.document {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(document.title).font(.title2.weight(.medium)).textSelection(.enabled)
                            HStack(spacing: 12) {
                                Text(document.type.title)
                                if let date = WorkspaceFormat.date(document.date) {
                                    Text(date, format: .dateTime.year().month(.wide).day())
                                }
                            }.font(.caption).foregroundStyle(.secondary)
                            if document.trashed {
                                Label("library.document.trashed", systemImage: "trash")
                                    .font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                        if document.body.isEmpty {
                            Text("library.body.empty").font(.body).foregroundStyle(.secondary)
                        } else {
                            Text(document.body).font(.body).lineSpacing(7).textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        HStack(spacing: 24) {
                            Button {
                                Task {
                                    if await model.setFlags(favorite: !document.favorite, trashed: document.trashed) { await onChanged() }
                                }
                            } label: {
                                Label(document.favorite ? LocalizedStringKey("library.favorite.remove") : LocalizedStringKey("library.favorite.add"),
                                      systemImage: document.favorite ? "star.fill" : "star")
                            }.disabled(model.isUpdating || model.isLoading).frame(minHeight: 44)
                            if document.trashed {
                                Button("library.restore") {
                                    Task {
                                        if await model.setFlags(favorite: document.favorite, trashed: false) { await onChanged() }
                                    }
                                }.disabled(model.isUpdating || model.isLoading).frame(minHeight: 44)
                            }
                        }.font(.subheadline)
                        if model.isUpdating { ProgressView() }
                    }
                }
                .padding(24).frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Color(uiColor: .systemBackground).ignoresSafeArea())
            .navigationTitle("library.detail").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("workspace.done") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    if let document = model.document, !document.trashed {
                        Menu {
                            Button("library.edit", systemImage: "pencil") { showsEdit = true }
                            Button(role: .destructive) { showsTrashConfirmation = true } label: {
                                Label("library.trash", systemImage: "trash")
                            }
                        } label: { Image(systemName: "ellipsis") }
                            .accessibilityLabel("library.actions")
                            .disabled(model.isUpdating || model.isLoading)
                    }
                }
            }
        }
        .task { await model.load() }
        .sheet(isPresented: $showsEdit) {
            if let document = model.document {
                LibraryEditorView(draft: LibraryDraft(document: document), creating: false,
                                  service: model.service) { _ in
                    await model.load()
                    await onChanged()
                }
            }
        }
        .confirmationDialog("library.trash.confirm", isPresented: $showsTrashConfirmation, titleVisibility: .visible) {
            Button("library.trash", role: .destructive) {
                guard let document = model.document else { return }
                Task {
                    if await model.setFlags(favorite: document.favorite, trashed: true) { await onChanged() }
                }
            }
            Button("workspace.cancel", role: .cancel) {}
        } message: { Text("library.trash.confirm.description") }
    }
}
