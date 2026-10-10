import SwiftUI

/// 从桌面专注与补录流程提炼手机操作；首页不再只展示固定日摘要。
struct RecordWorkspaceView: View {
    @ObservedObject var model: RecordWorkspaceViewModel
    let onBack: () -> Void
    var onChanged: () -> Void = {}
    @Environment(\.locale) private var locale
    @Environment(\.scenePhase) private var scenePhase
    @State private var editor: RecordEditorMode?
    @State private var selectedEntry: StudyEntry?

    var body: some View {
        WorkspaceScreen(title: "tab.record", subtitle: "record.subtitle", onBack: onBack,
                        actionTitle: "record.manual", onAdd: { editor = .manual }) {
            focusSection
            VStack(alignment: .leading, spacing: 18) {
                WorkspaceMonthPicker(date: $model.month)
                overviewSection
            }
            WorkspaceSearchField(text: $model.query, prompt: "record.search")
            historySection
        }
        .refreshable { await model.refresh() }
        .task { await model.restoreActive() }
        .task(id: model.monthID) { await model.loadOverview() }
        .task(id: model.filterID) {
            do { try await Task.sleep(for: .milliseconds(250)) }
            catch { return }
            await model.loadHistory()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.refresh() } }
        }
        .sheet(item: $editor) { mode in
            RecordEditorView(mode: mode, model: model, onChanged: onChanged)
        }
        .sheet(item: $selectedEntry) { entry in
            RecordDetailView(entry: entry, model: model, onChanged: onChanged)
        }
        .alert("workspace.error.title", isPresented: Binding(
            get: { model.writeErrorKey != nil }, set: { if !$0 { model.writeErrorKey = nil } })) {
                Button("workspace.ok", role: .cancel) { model.writeErrorKey = nil }
            } message: {
                Text(RecordFormatting.message(model.writeErrorKey ?? "api.error.request", locale: locale))
            }
    }

    private var focusSection: some View {
        WorkspaceSection(title: "record.focus") {
            if let active = model.active {
                VStack(alignment: .leading, spacing: 18) {
                    Button { selectedEntry = active } label: {
                        HStack(alignment: .top, spacing: 12) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(active.title).font(.headline).foregroundStyle(.primary)
                                Text(active.startedAt, format: .dateTime.month().day().hour().minute())
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 8)
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let elapsed = active.durationSeconds(at: context.date)
                        VStack(alignment: .leading, spacing: 16) {
                            Text(RecordFormatting.duration(elapsed, locale: locale, clock: true))
                                .font(.system(.largeTitle, design: .monospaced).weight(.light))
                                .monospacedDigit()
                                .accessibilityLabel("record.elapsed")
                            Button {
                                Task { if await model.finish() { onChanged() } }
                            } label: {
                                Label("record.finish", systemImage: "stop")
                                    .frame(minHeight: 44)
                            }
                            .disabled(model.isSaving || elapsed < StudyRules.minimumSeconds)
                            if elapsed < StudyRules.minimumSeconds {
                                Text("record.minimum").font(.footnote).foregroundStyle(.secondary)
                            }
                            if elapsed > StudyRules.maximumSeconds {
                                Text("record.overdue").font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } else if model.isRestoringActive {
                ProgressView().frame(minHeight: 44)
            } else {
                Button { editor = .focus } label: {
                    Label("record.start", systemImage: "play")
                        .font(.headline.weight(.medium))
                        .frame(minHeight: 44)
                }
                .disabled(model.isSaving || model.activeErrorKey != nil)
                Text("record.focus_description").font(.footnote).foregroundStyle(.secondary)
            }
            if let key = model.activeErrorKey {
                WorkspaceErrorView(message: RecordFormatting.message(key, locale: locale)) {
                    Task { await model.restoreActive() }
                }
            }
        }
    }

    @ViewBuilder
    private var overviewSection: some View {
        if model.isLoadingOverview && model.overview == nil {
            ProgressView().frame(minHeight: 44)
        } else if let overview = model.overview {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 24) { overviewValues(overview) }
                VStack(alignment: .leading, spacing: 18) { overviewValues(overview) }
            }
        }
        if let key = model.overviewErrorKey {
            WorkspaceErrorView(message: RecordFormatting.message(key, locale: locale)) {
                Task { await model.loadOverview() }
            }
        }
    }

    private func overviewValues(_ overview: StudyOverview) -> some View {
        Group {
            WorkspaceValue(label: "record.minutes", value: overview.minutes.formatted(.number.locale(locale)))
            WorkspaceValue(label: "record.sessions", value: overview.sessionCount.formatted(.number.locale(locale)))
            WorkspaceValue(label: "record.days", value: overview.activeDays.formatted(.number.locale(locale)))
        }
    }

    private var historySection: some View {
        WorkspaceSection(title: "record.history") {
            if model.isLoading && model.entries.isEmpty {
                ProgressView().frame(maxWidth: .infinity, minHeight: 80)
            } else if model.entries.isEmpty && model.historyErrorKey == nil {
                WorkspaceEmptyState(title: "record.empty", description: "record.empty_description", symbol: "clock")
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(model.entries) { entry in
                        Button { selectedEntry = entry } label: {
                            RecordEntryRow(entry: entry)
                        }
                        .buttonStyle(.plain)
                        Divider()
                    }
                }
            }
            if let key = model.historyErrorKey {
                WorkspaceErrorView(message: RecordFormatting.message(key, locale: locale)) {
                    Task { await model.retryHistory() }
                }
            }
            if model.hasMore {
                Button {
                    Task { await model.loadMore() }
                } label: {
                    if model.isLoadingMore { ProgressView().frame(maxWidth: .infinity, minHeight: 44) }
                    else { Text("workspace.load_more").frame(maxWidth: .infinity, minHeight: 44) }
                }
                .disabled(model.isLoading || model.isLoadingMore)
            }
        }
    }
}

private struct RecordEntryRow: View {
    let entry: StudyEntry
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: entry.isActive ? "timer" : "clock")
                .foregroundStyle(.secondary).padding(.top, 3)
            VStack(alignment: .leading, spacing: 7) {
                Text(entry.title).font(.body).foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
                Text(entry.startedAt, format: .dateTime.month().day().hour().minute())
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            if entry.isActive {
                Text("record.in_progress").font(.caption).foregroundStyle(.secondary)
            } else {
                Text(RecordFormatting.duration(entry.durationSeconds(), locale: locale))
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary).padding(.top, 4)
        }
        .padding(.vertical, 18)
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .contentShape(Rectangle())
    }
}
