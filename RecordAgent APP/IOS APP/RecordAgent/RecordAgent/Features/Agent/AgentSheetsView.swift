import SwiftUI

/// 会话列表面板：支持桌面/手机共用会话切换、搜索、重命名、删除，以及飞书只读历史查看。
struct AgentSessionsSheet: View {
    @ObservedObject var viewModel: AgentChatViewModel
    let onClose: () -> Void

    @State private var searchText = ""
    @State private var showFeishuOnly = false
    @State private var renamingSession: AgentSessionSummary?
    @State private var renameDraft = ""

    private var displayedSessions: [AgentSessionSummary] {
        let source = showFeishuOnly ? viewModel.feishuSessions : viewModel.sessions
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return source }
        return source.filter {
            $0.title.localizedCaseInsensitiveContains(query)
                || ($0.lastMessagePreview?.localizedCaseInsensitiveContains(query) ?? false)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            WorkspaceSheetHeader(title: "agent.sessions.title", onClose: onClose)
                .padding(.horizontal, 24)
                .padding(.top, 24)
                .padding(.bottom, 14)

            VStack(spacing: 12) {
                WorkspaceSearchField(text: $searchText, prompt: "agent.sessions.search")

                HStack(spacing: 20) {
                    channelFilterButton(title: "agent.sessions.shared", isSelected: !showFeishuOnly) {
                        showFeishuOnly = false
                    }
                    channelFilterButton(title: "agent.sessions.feishu", isSelected: showFeishuOnly) {
                        showFeishuOnly = true
                    }
                    Spacer()
                    if !showFeishuOnly {
                        Button {
                            viewModel.startNewChat()
                            onClose()
                        } label: {
                            Label("agent.new_chat", systemImage: "square.and.pencil")
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(AppColors.primaryBlue)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 8)

            if viewModel.isLoadingSessions && displayedSessions.isEmpty {
                Spacer()
                ProgressView()
                Spacer()
            } else if displayedSessions.isEmpty {
                Spacer()
                WorkspaceEmptyState(
                    title: showFeishuOnly ? "agent.sessions.feishu_empty" : "agent.sessions.empty",
                    description: showFeishuOnly
                        ? "agent.sessions.feishu_empty_desc"
                        : "agent.sessions.empty_desc",
                    symbol: "bubble.left.and.bubble.right"
                )
                .padding(.horizontal, 24)
                Spacer()
            } else {
                List {
                    ForEach(displayedSessions) { item in
                        Button {
                            Task {
                                await viewModel.selectSession(item, readOnly: showFeishuOnly)
                                onClose()
                            }
                        } label: {
                            sessionRow(item)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 6, leading: 24, bottom: 6, trailing: 24))
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            if !showFeishuOnly {
                                Button(role: .destructive) {
                                    Task { await viewModel.deleteSession(item) }
                                } label: {
                                    Label("agent.session.delete", systemImage: "trash")
                                }
                                Button {
                                    renameDraft = item.title
                                    renamingSession = item
                                } label: {
                                    Label("agent.session.rename", systemImage: "pencil")
                                }
                                .tint(AppColors.primaryBlue)
                            }
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .refreshable {
                    await viewModel.refreshSessions()
                }
            }
        }
        .background(Color(uiColor: .systemBackground).ignoresSafeArea())
        .workspaceSheet(compact: false)
        .alert("agent.session.rename_title", isPresented: Binding(
            get: { renamingSession != nil },
            set: { if !$0 { renamingSession = nil } }
        )) {
            TextField("agent.session.rename_placeholder", text: $renameDraft)
            Button("common.cancel", role: .cancel) { renamingSession = nil }
            Button("common.confirm") {
                if let target = renamingSession {
                    Task { await viewModel.renameSession(target, newTitle: renameDraft) }
                }
                renamingSession = nil
            }
        }
    }

    @ViewBuilder
    private func channelFilterButton(
        title: LocalizedStringKey,
        isSelected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(isSelected ? .semibold : .regular))
                .foregroundStyle(isSelected ? .primary : .secondary)
                .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func sessionRow(_ item: AgentSessionSummary) -> some View {
        let isSelected = viewModel.activeSessionId == item.id
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: item.title)
                    .font(.body.weight(isSelected ? .semibold : .regular))
                    .foregroundStyle(isSelected ? AppColors.primaryBlue : .primary)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Text(item.updatedAt, format: .relative(presentation: .named))
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            if let preview = item.lastMessagePreview, !preview.isEmpty {
                Text(verbatim: preview)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 14)
        .background(
            isSelected ? AppColors.primaryBlue.opacity(0.08) : Color.clear,
            in: RoundedRectangle(cornerRadius: 14, style: .continuous)
        )
        .contentShape(Rectangle())
    }
}

/// 当前会话任务计划书只读面板。
struct AgentPlanSheet: View {
    let content: String
    let onClose: () -> Void

    var body: some View {
        WorkspaceDetailSheet(title: "agent.plan.title", compact: false, onClose: onClose) {
            AgentMarkdownView(text: content)
        }
    }
}
