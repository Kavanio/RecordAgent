import SwiftUI

struct TasksWorkspaceView: View {
    @ObservedObject var model: MobileTaskViewModel
    let onBack: () -> Void
    @State private var editor: MobileTaskEditorSelection?
    @State private var showDetail = false
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL

    var body: some View {
        WorkspaceScreen(title: "tasks.title", subtitle: "tasks.subtitle", onBack: onBack,
                        actionTitle: "tasks.create", onAdd: { editor = MobileTaskEditorSelection(task: .draft(), creating: true) }) {
            notificationStatus
            VStack(alignment: .leading, spacing: 12) {
                WorkspaceSearchField(text: $model.query, prompt: "tasks.search")
                WorkspaceFilters(options: MobileTaskFilter.allCases, selection: $model.filter) { $0.title }
            }
            if model.isLoading && model.tasks.isEmpty { ProgressView().frame(maxWidth: .infinity) }
            if let error = model.loadError { WorkspaceErrorView(message: error) { Task { await model.load() } } }
            if !model.isLoading && model.loadError == nil && model.tasks.isEmpty {
                WorkspaceEmptyState(title: "tasks.empty.title", description: "tasks.empty.description", symbol: "bell")
            }
            LazyVStack(alignment: .leading, spacing: 24) {
                ForEach(model.tasks) { task in
                    Button {
                        showDetail = true
                        Task { await model.open(task) }
                    } label: {
                        HStack(alignment: .top, spacing: 16) {
                            Image(systemName: task.enabled ? "bell" : "bell.slash")
                                .foregroundStyle(task.enabled ? AppColors.primaryBlue : Color.secondary)
                                .font(.title3).frame(minWidth: 26, minHeight: 44)
                            VStack(alignment: .leading, spacing: 7) {
                                Text(task.title).font(.headline).foregroundStyle(.primary)
                                if !task.body.isEmpty { Text(task.body).font(.subheadline).foregroundStyle(.secondary).lineLimit(2) }
                                HStack(spacing: 8) {
                                    Text(task.recurrence.title)
                                    Text(model.registration(for: task).title)
                                }
                                .font(.caption).foregroundStyle(.secondary)
                                if let next = MobileTaskSchedule.nextDate(for: task), task.enabled {
                                    Text(MobileTaskPresentation.date(next, task: task, locale: locale))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary).padding(.top, 8)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .frame(minHeight: 44)
                }
                if model.hasMore { Button("workspace.load_more") { Task { await model.loadMore() } }.disabled(model.isLoading) }
            }
        }
        .task(id: model.requestKey) {
            if !model.query.isEmpty {
                do { try await Task.sleep(for: .milliseconds(300)) } catch { return }
            }
            await model.load()
        }
        .task { await model.synchronizeNotifications() }
        .sheet(item: $editor) { selection in
            MobileTaskEditorView(task: selection.task, creating: selection.creating) { draft in
                try await model.save(draft, creating: selection.creating)
            }
        }
        .sheet(isPresented: $showDetail) { MobileTaskDetailView(model: model) }
    }

    @ViewBuilder private var notificationStatus: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("tasks.device.title").font(.headline)
            if model.permission == .notDetermined {
                Text("tasks.device.permission_description").font(.subheadline).foregroundStyle(.secondary)
                Button("tasks.device.allow") { Task { await model.requestNotificationPermission() } }.frame(minHeight: 44)
            } else if model.permission == .denied {
                Text("tasks.device.denied_description").font(.subheadline).foregroundStyle(.secondary)
                Button("tasks.device.settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .frame(minHeight: 44)
            } else {
                Text("tasks.device.ready").font(.subheadline).foregroundStyle(.secondary)
            }
            if model.synchronizationFailed {
                Text("tasks.device.sync_failed").font(.footnote).foregroundStyle(.secondary)
                Button("workspace.retry") { Task { await model.synchronizeNotifications(force: true) } }.frame(minHeight: 44)
            }
            if model.observationUploadFailed { Text("tasks.history.upload_failed").font(.footnote).foregroundStyle(.secondary) }
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

struct MobileTaskEditorSelection: Identifiable {
    let task: MobileTask
    let creating: Bool
    var id: String { task.id }
}
