import SwiftUI

/// 会话列表时间分组（纯函数，支持单元测试）。
enum AgentSessionTimeGroup: String, CaseIterable, Identifiable {
    case today
    case yesterday
    case last7Days
    case older

    var id: String { rawValue }

    var localizedTitle: LocalizedStringKey {
        switch self {
        case .today: return "agent.sessions.group.today"
        case .yesterday: return "agent.sessions.group.yesterday"
        case .last7Days: return "agent.sessions.group.last_7_days"
        case .older: return "agent.sessions.group.older"
        }
    }

    static func group(
        _ sessions: [AgentSessionSummary],
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> [(group: AgentSessionTimeGroup, items: [AgentSessionSummary])] {
        let startOfToday = calendar.startOfDay(for: now)
        let startOfYesterday = calendar.date(byAdding: .day, value: -1, to: startOfToday) ?? startOfToday
        let startOf7DaysAgo = calendar.date(byAdding: .day, value: -7, to: startOfToday) ?? startOfToday

        var buckets: [AgentSessionTimeGroup: [AgentSessionSummary]] = [:]
        for session in sessions {
            let date = session.updatedAt
            let bucket: AgentSessionTimeGroup
            if date >= startOfToday {
                bucket = .today
            } else if date >= startOfYesterday {
                bucket = .yesterday
            } else if date >= startOf7DaysAgo {
                bucket = .last7Days
            } else {
                bucket = .older
            }
            buckets[bucket, default: []].append(session)
        }

        return AgentSessionTimeGroup.allCases.compactMap { group in
            guard let items = buckets[group], !items.isEmpty else { return nil }
            return (group: group, items: items)
        }
    }
}

/// 左侧滑入式会话历史抽屉：仿原生 ChatGPT 侧边栏结构（顶部搜索/切换 + 分组历史列表 + 底部新建胶囊按钮）。
struct AgentSessionsDrawerView: View {
    @ObservedObject var viewModel: AgentChatViewModel
    let onClose: () -> Void

    @State private var isSearching = false
    @State private var searchText = ""
    @State private var showFeishuOnly = false
    @State private var renamingSession: AgentSessionSummary?
    @State private var renameDraft = ""
    @FocusState private var isSearchFocused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var activeSource: [AgentSessionSummary] {
        showFeishuOnly ? viewModel.feishuSessions : viewModel.sessions
    }

    private var displayedSessions: [AgentSessionSummary] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return activeSource }
        return activeSource.filter {
            $0.title.localizedCaseInsensitiveContains(query)
                || ($0.lastMessagePreview?.localizedCaseInsensitiveContains(query) ?? false)
        }
    }

    private var groupedSessions: [(group: AgentSessionTimeGroup, items: [AgentSessionSummary])] {
        AgentSessionTimeGroup.group(displayedSessions)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            drawerHeader
                .padding(.horizontal, 18)
                .padding(.top, 10)
                .padding(.bottom, 10)

            channelSwitchBar
                .padding(.horizontal, 18)
                .padding(.bottom, 10)

            if viewModel.isLoadingSessions && displayedSessions.isEmpty {
                Spacer()
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
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
                .padding(.horizontal, 20)
                Spacer()
            } else {
                sessionList
            }

            bottomBar
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Color(uiColor: .systemBackground).ignoresSafeArea())
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

    // MARK: - 顶部标题与可展开搜索框

    private var drawerHeader: some View {
        Group {
            if isSearching {
                HStack(spacing: 10) {
                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        TextField("agent.sessions.search", text: $searchText)
                            .font(.subheadline)
                            .focused($isSearchFocused)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        if !searchText.isEmpty {
                            Button {
                                searchText = ""
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(Color(uiColor: .secondarySystemBackground), in: Capsule())

                    Button("common.cancel") {
                        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                            searchText = ""
                            isSearchFocused = false
                            isSearching = false
                        }
                    }
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                }
            } else {
                HStack(spacing: 12) {
                    Text("agent.sessions.title")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(.primary)
                    Spacer(minLength: 0)
                    Button {
                        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                            isSearching = true
                        }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                            isSearchFocused = true
                        }
                    } label: {
                        Image(systemName: "magnifyingglass")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.primary)
                            .frame(width: 36, height: 36)
                            .background(Color(uiColor: .secondarySystemBackground), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("agent.sessions.search")
                }
                .frame(minHeight: 38)
            }
        }
    }

    private var channelSwitchBar: some View {
        HStack(spacing: 8) {
            channelChip(title: "agent.sessions.shared", isSelected: !showFeishuOnly) {
                showFeishuOnly = false
            }
            channelChip(title: "agent.sessions.feishu", isSelected: showFeishuOnly) {
                showFeishuOnly = true
            }
            Spacer(minLength: 0)
        }
    }

    @ViewBuilder
    private func channelChip(
        title: LocalizedStringKey,
        isSelected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.16)) {
                action()
            }
        } label: {
            Text(title)
                .font(.caption.weight(isSelected ? .semibold : .regular))
                .foregroundStyle(isSelected ? .primary : .secondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    isSelected ? Color(uiColor: .secondarySystemBackground) : Color.clear,
                    in: Capsule()
                )
        }
        .buttonStyle(.plain)
    }

    // MARK: - 会话分组列表

    private var sessionList: some View {
        List {
            if !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                ForEach(displayedSessions) { item in
                    sessionListRow(item)
                }
            } else {
                ForEach(groupedSessions, id: \.group.id) { section in
                    Section {
                        ForEach(section.items) { item in
                            sessionListRow(item)
                        }
                    } header: {
                        Text(section.group.localizedTitle)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .textCase(nil)
                            .padding(.top, 6)
                            .padding(.bottom, 2)
                            .listRowInsets(EdgeInsets(top: 4, leading: 18, bottom: 2, trailing: 14))
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .refreshable {
            await viewModel.refreshSessions()
        }
    }

    @ViewBuilder
    private func sessionListRow(_ item: AgentSessionSummary) -> some View {
        Button {
            isSearchFocused = false
            Task {
                await viewModel.selectSession(item, readOnly: showFeishuOnly)
            }
            onClose()
        } label: {
            sessionRowContent(item)
        }
        .buttonStyle(.plain)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 2, leading: 10, bottom: 2, trailing: 10))
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

    @ViewBuilder
    private func sessionRowContent(_ item: AgentSessionSummary) -> some View {
        let isSelected = viewModel.activeSessionId == item.id
        HStack(spacing: 10) {
            Text(verbatim: item.title)
                .font(.subheadline.weight(isSelected ? .semibold : .regular))
                .foregroundStyle(Color.primary.opacity(isSelected ? 1.0 : 0.88))
                .lineLimit(1)
            Spacer(minLength: 4)
            if showFeishuOnly {
                Text(item.updatedAt, format: .relative(presentation: .named))
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 11)
        .padding(.horizontal, 12)
        .background(
            isSelected ? Color(uiColor: .secondarySystemBackground) : Color.clear,
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
        .contentShape(Rectangle())
    }

    // MARK: - 底部“新对话”胶囊按钮

    private var bottomBar: some View {
        HStack {
            Button {
                isSearchFocused = false
                viewModel.startNewChat()
                onClose()
            } label: {
                HStack(spacing: 7) {
                    Image(systemName: "square.and.pencil")
                        .font(.subheadline.weight(.semibold))
                    Text("agent.new_chat")
                        .font(.subheadline.weight(.semibold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 18)
                .padding(.vertical, 11)
                .background(AppColors.primaryBlue, in: Capsule())
            }
            .buttonStyle(.plain)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.top, 10)
        .padding(.bottom, 12)
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
