import SwiftUI

struct LibraryWorkspaceView: View {
    @ObservedObject var model: LibraryWorkspaceViewModel
    let onBack: () -> Void
    var onChanged: () -> Void = {}
    @State private var showsCreate = false
    @State private var selectedItem: LibraryItem?

    var body: some View {
        WorkspaceScreen(title: "tab.library", subtitle: "library.workspace.subtitle",
                        onBack: onBack, actionTitle: "library.create", onAdd: { showsCreate = true }) {
            WorkspaceSearchField(text: $model.query, prompt: "library.search")
            WorkspaceFilters(options: LibraryCollection.allCases, selection: $model.collection) { $0.title }
            HStack {
                Text("library.type").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Picker("library.type", selection: $model.type) {
                    Text("library.type.all").tag(Optional<LibraryDocumentKind>.none)
                    ForEach(LibraryDocumentKind.allCases) { Text($0.title).tag(Optional($0)) }
                }.pickerStyle(.menu)
            }
            if let error = model.errorMessage {
                WorkspaceErrorView(message: error) { Task { await model.load(reset: model.items.isEmpty) } }
            }
            if model.items.isEmpty && model.isLoading { ProgressView() }
            if model.items.isEmpty && !model.isLoading && model.errorMessage == nil {
                WorkspaceEmptyState(title: model.collection == .trash ? "library.trash.empty.title" : "library.empty.title",
                    description: model.collection == .trash ? "library.trash.empty.description" : "library.empty.description", symbol: "doc.text")
            }
            LazyVStack(spacing: 0) {
                ForEach(model.items) { item in
                    Button { selectedItem = item } label: { LibraryItemRow(item: item) }.buttonStyle(.plain)
                    Divider()
                }
                if model.nextOffset != nil {
                    Button("workspace.load_more") { Task { await model.load(reset: false) } }
                        .frame(minHeight: 44).disabled(model.isLoading)
                }
                if model.isLoading && !model.items.isEmpty { ProgressView().padding(.top, 16) }
            }
        }
        .task(id: model.filterKey) {
            do { try await Task.sleep(for: .milliseconds(250)); try Task.checkCancellation() }
            catch { return }
            await model.loadIfNeeded()
        }
        .refreshable { await model.load(reset: true) }
        .sheet(isPresented: $showsCreate) {
            LibraryEditorView(draft: LibraryDraft(date: WorkspaceFormat.dayKey(model.initialDate), type: model.type ?? .quick), creating: true, service: model.service) { _ in
                await model.load(reset: true)
                onChanged()
            }
        }
        .sheet(item: $selectedItem) { item in
            LibraryDetailView(id: item.id, service: model.service) {
                await model.load(reset: true)
                onChanged()
            }
        }
    }
}
