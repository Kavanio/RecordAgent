import SwiftUI

struct MobileTaskDetailView: View {
    @ObservedObject var model: MobileTaskViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var editing = false
    @State private var confirmingDelete = false
    @State private var operationError: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                if let task = model.selectedTask {
                    VStack(alignment: .leading, spacing: 28) {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(task.title).font(.largeTitle.weight(.semibold))
                            if !task.body.isEmpty { Text(task.body).font(.body).textSelection(.enabled) }
                            Label(model.registration(for: task).title, systemImage: model.registration(for: task).symbol)
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                        WorkspaceSection(title: "tasks.schedule.title") {
                            Text(task.recurrence.title).font(.title3)
                            if task.recurrence == .weekly, let weekday = task.weekday {
                                Text(MobileTaskPresentation.weekday(weekday, locale: locale))
                            }
                            if task.recurrence == .monthly, let day = task.dayOfMonth { Text("tasks.schedule.month_day \(day)") }
                            if let next = MobileTaskSchedule.nextDate(for: task) {
                                WorkspaceValue(label: "tasks.schedule.next", value: MobileTaskPresentation.date(next, task: task, locale: locale))
                            } else if let parts = try? MobileTaskSchedule.components(for: task), let calendar = parts.calendar,
                                      let date = calendar.date(from: parts) {
                                WorkspaceValue(label: "tasks.schedule.time", value: MobileTaskPresentation.date(date, task: task, locale: locale))
                            }
                            WorkspaceValue(label: "tasks.timezone", value: task.timeZone)
                            Text("tasks.registration.explanation").font(.footnote).foregroundStyle(.secondary)
                        }
                        WorkspaceSection(title: "tasks.history.title") {
                            Text("tasks.history.description").font(.footnote).foregroundStyle(.secondary)
                            if model.isLoadingHistory && model.deliveries.isEmpty { ProgressView() }
                            if let error = model.historyError { WorkspaceErrorView(message: error) { Task { await model.reloadDetail() } } }
                            if !model.isLoadingHistory && model.historyError == nil && model.deliveries.isEmpty {
                                Text("tasks.history.empty").font(.subheadline).foregroundStyle(.secondary)
                            }
                            ForEach(model.deliveries) { delivery in
                                Label {
                                    Text(delivery.deliveredAt, format: .dateTime.year().month().day().hour().minute())
                                } icon: { Image(systemName: "bell.badge") }
                                .font(.subheadline)
                            }
                            if model.historyHasMore { Button("workspace.load_more") { Task { await model.loadMoreHistory() } }.disabled(model.isLoadingHistory) }
                        }
                        VStack(alignment: .leading, spacing: 12) {
                            Button(task.enabled ? LocalizedStringKey("tasks.pause") : LocalizedStringKey("tasks.enable")) {
                                Task {
                                    operationError = nil
                                    do { try await model.toggle(task) }
                                    catch is CancellationError { }
                                    catch { operationError = error.localizedDescription }
                                }
                            }
                            .frame(minHeight: 44).disabled(model.isSaving)
                            Button("workspace.delete", role: .destructive) { confirmingDelete = true }
                                .frame(minHeight: 44).disabled(model.isSaving)
                            if let operationError {
                                WorkspaceErrorView(message: operationError) {
                                    self.operationError = nil
                                    Task { await model.reloadDetail() }
                                }
                            }
                        }
                    }
                    .padding(24).frame(maxWidth: .infinity, alignment: .leading)
                } else { ProgressView().padding(24) }
            }
            .background(Color(uiColor: .systemBackground).ignoresSafeArea())
            .navigationTitle("tasks.detail.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("workspace.close") { dismiss() }.disabled(model.isSaving) }
                ToolbarItem(placement: .topBarTrailing) { Button("workspace.edit") { editing = true }.disabled(model.selectedTask == nil || model.isSaving) }
            }
            .sheet(isPresented: $editing) {
                if let task = model.selectedTask {
                    MobileTaskEditorView(task: task, creating: false) { draft in try await model.save(draft, creating: false) }
                }
            }
            .confirmationDialog("tasks.delete.confirm", isPresented: $confirmingDelete, titleVisibility: .visible) {
                Button("workspace.delete", role: .destructive) {
                    guard let task = model.selectedTask else { return }
                    Task {
                        do { try await model.delete(task); dismiss() }
                        catch is CancellationError { }
                        catch { operationError = error.localizedDescription }
                    }
                }
            }
        }
        .tint(AppColors.primaryBlue)
        .interactiveDismissDisabled(model.isSaving)
    }
}
