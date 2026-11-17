import SwiftUI

enum AppTab: String, CaseIterable, Identifiable {
    case home, calendar, chat, workspace, finance, library, record, tasks, profile
    var id: String { rawValue }
    static let primaryTabs: [AppTab] = [.home, .calendar, .chat, .workspace, .profile]
    static let workspaceTabs: [AppTab] = [.finance, .library, .record, .tasks]

    var navigationTab: AppTab {
        Self.workspaceTabs.contains(self) ? .workspace : self
    }

    var navigationTitle: LocalizedStringKey {
        self == .profile ? "tab.mine" : title
    }

    var title: LocalizedStringKey {
        switch self {
        case .home: return "tab.home"
        case .calendar: return "tab.calendar"
        case .chat: return "tab.chat"
        case .workspace: return "tab.workspace"
        case .finance: return "tab.finance"
        case .library: return "tab.library"
        case .record: return "tab.record"
        case .tasks: return "tab.tasks"
        case .profile: return "tab.profile"
        }
    }
    var icon: String {
        switch self {
        case .home: return "house"
        case .calendar: return "calendar"
        case .chat: return "bubble.left.and.bubble.right"
        case .workspace: return "square.grid.2x2"
        case .finance: return "chart.pie"
        case .library: return "books.vertical"
        case .record: return "timer"
        case .tasks: return "bell"
        case .profile: return "person.crop.circle"
        }
    }
    var category: HomeCategory? {
        switch self {
        case .finance: return .finance
        case .library: return .library
        case .record: return .record
        default: return nil
        }
    }
}

/// 主容器持有按日状态，切换 Tab 不销毁首页的选择与待办修改。
@MainActor
struct MainTabView: View {
    @State private var selectedTab: AppTab = .home
    @State private var isAgentDrawerOpen = false
    @State private var drawerDragTranslation: CGFloat = 0
    @State private var isDraggingDrawer = false
    @StateObject private var homeModel: HomeViewModel
    @StateObject private var financeModel: FinanceWorkspaceViewModel
    @StateObject private var libraryModel: LibraryWorkspaceViewModel
    @StateObject private var recordModel: RecordWorkspaceViewModel
    @StateObject private var tasksModel: MobileTaskViewModel
    @StateObject private var agentModel: AgentChatViewModel
    @ObservedObject private var proactiveNotifications: ProactiveNotificationCenter
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let user: AuthUser?
    let notificationAccountID: String
    let calendarService: any CalendarDataServiceProtocol
    let financeService: any FinanceServiceProtocol
    let profileOverviewService: any ProfileOverviewServiceProtocol
    let profileActivityService: any ProfileActivityServiceProtocol
    let profileAppearanceService: any ProfileAppearanceServiceProtocol
    let onLogout: () async throws -> Void

    init(
        homeService: any HomeDataServiceProtocol,
        calendarService: any CalendarDataServiceProtocol,
        financeService: any FinanceServiceProtocol,
        financeWorkspaceService: any FinanceWorkspaceServiceProtocol,
        libraryService: any LibraryServiceProtocol,
        studyService: any StudyServiceProtocol,
        mobileTaskService: any MobileTaskServiceProtocol,
        mobileTaskNotifications: any MobileTaskNotificationProtocol,
        notificationAccountID: String,
        profileOverviewService: any ProfileOverviewServiceProtocol,
        profileActivityService: any ProfileActivityServiceProtocol,
        profileAppearanceService: any ProfileAppearanceServiceProtocol,
        agentService: any AgentServiceProtocol,
        proactiveNotifications: ProactiveNotificationCenter,
        user: AuthUser?,
        onLogout: @escaping () async throws -> Void
    ) {
        _homeModel = StateObject(wrappedValue: HomeViewModel(service: homeService))
        _financeModel = StateObject(wrappedValue: FinanceWorkspaceViewModel(date: .now, service: financeWorkspaceService))
        _libraryModel = StateObject(wrappedValue: LibraryWorkspaceViewModel(date: .now, service: libraryService))
        _recordModel = StateObject(wrappedValue: RecordWorkspaceViewModel(service: studyService))
        _tasksModel = StateObject(wrappedValue: MobileTaskViewModel(service: mobileTaskService, notifications: mobileTaskNotifications, accountID: notificationAccountID))
        _agentModel = StateObject(wrappedValue: AgentChatViewModel(service: agentService))
        self.proactiveNotifications = proactiveNotifications
        self.notificationAccountID = notificationAccountID
        self.calendarService = calendarService
        self.financeService = financeService
        self.profileOverviewService = profileOverviewService
        self.profileActivityService = profileActivityService
        self.profileAppearanceService = profileAppearanceService
        self.user = user
        self.onLogout = onLogout
    }

    private struct LoadKey: Equatable {
        let date: Date
        let locale: String
    }

    var body: some View {
        GeometryReader { proxy in
            let drawerWidth = min(max(proxy.size.width * 0.80, 270), 340)
            let isChatActive = selectedTab == .chat
            let baseOffset: CGFloat = (isChatActive && isAgentDrawerOpen) ? drawerWidth : 0
            let rawOffset = baseOffset + (isChatActive ? drawerDragTranslation : 0)
            let currentOffset = min(max(rawOffset, 0), drawerWidth)
            let progress = drawerWidth > 0 ? currentOffset / drawerWidth : 0

            ZStack(alignment: .leading) {
                if isChatActive && progress > 0.001 {
                    AgentSessionsDrawerView(
                        viewModel: agentModel,
                        onClose: { closeAgentDrawer() }
                    )
                    .frame(width: drawerWidth)
                    .offset(x: -drawerWidth * 0.16 * (1 - progress))
                    .opacity(0.45 + 0.55 * progress)
                    .accessibilityHidden(progress < 0.5)
                }

                mainContentShell
                    .overlay {
                        if progress > 0.001 {
                            Color.black.opacity(0.06 * progress)
                                .ignoresSafeArea()
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    closeAgentDrawer()
                                }
                                .gesture(closeDrawerDragGesture(drawerWidth: drawerWidth))
                        }
                    }
                    .overlay(alignment: .leading) {
                        if progress > 0.001 {
                            Rectangle()
                                .fill(Color(uiColor: .separator).opacity(0.22 * progress))
                                .frame(width: 0.5)
                                .ignoresSafeArea()
                        }
                    }
                    .mask {
                        UnevenRoundedRectangle(
                            topLeadingRadius: progress > 0.001 ? 26 * progress : 0,
                            bottomLeadingRadius: progress > 0.001 ? 26 * progress : 0
                        )
                        .ignoresSafeArea()
                    }
                    .shadow(
                        color: Color.black.opacity(0.13 * progress),
                        radius: 20,
                        x: -6,
                        y: 0
                    )
                    .offset(x: currentOffset)
                    .simultaneousGesture(edgeOpenDrawerGesture(drawerWidth: drawerWidth), including: .all)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(uiColor: .systemBackground).ignoresSafeArea())
        }
        .onChange(of: selectedTab) { _, newTab in
            if newTab != .chat && (isAgentDrawerOpen || drawerDragTranslation != 0) {
                isAgentDrawerOpen = false
                drawerDragTranslation = 0
                isDraggingDrawer = false
            }
        }
        .tint(AppColors.primaryBlue)
        .task(id: LoadKey(date: homeModel.selectedDate, locale: locale.identifier)) {
            await homeModel.load(locale: locale)
        }
        .task {
            await tasksModel.synchronizeNotifications()
            await proactiveNotifications.activate(accountID: notificationAccountID)
            handlePendingProactiveRoute()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task {
                    await tasksModel.synchronizeNotifications()
                    await proactiveNotifications.activate(accountID: notificationAccountID)
                }
            } else if phase == .background {
                proactiveNotifications.enterBackground()
            }
        }
        .onChange(of: proactiveNotifications.pendingRoute) { _, _ in
            handlePendingProactiveRoute()
        }
        .onChange(of: proactiveNotifications.latestNoticeSignal) { _, _ in
            if selectedTab == .chat {
                Task { await agentModel.refreshActiveSessionIfCompanionUpdated() }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .apiSessionExpired)) { _ in
            Task { await tasksModel.deactivate() }
        }
    }

    private var mainContentShell: some View {
        ZStack {
            switch selectedTab {
            case .home:
                HomeView(viewModel: homeModel)
                    .transition(pageTransition)
            case .calendar:
                CalendarView(home: homeModel, service: calendarService, financeService: financeService)
                .transition(pageTransition)
            case .chat:
                AgentChatView(
                    viewModel: agentModel,
                    onOpenSessionsDrawer: { openAgentDrawer() }
                )
                .transition(pageTransition)
            case .workspace:
                WorkspaceHubView { selectedTab = $0 }
                    .transition(pageTransition)
            case .profile:
                ProfileView(
                    user: user,
                    overviewService: profileOverviewService,
                    activityService: profileActivityService,
                    appearanceService: profileAppearanceService,
                    onOpenActivity: { entry in
                        homeModel.openFromProfile(entry)
                        selectedTab = .home
                    },
                    onLogout: {
                        try await onLogout()
                        await tasksModel.deactivate()
                    }
                )
                .transition(pageTransition)
            case .finance:
                FinanceWorkspaceView(model: financeModel, financeService: financeService,
                                     onBack: { selectedTab = .workspace }, onChanged: refreshHome)
                    .transition(pageTransition)
            case .library:
                LibraryWorkspaceView(model: libraryModel,
                                     onBack: { selectedTab = .workspace }, onChanged: refreshHome)
                    .transition(pageTransition)
            case .record:
                RecordWorkspaceView(model: recordModel,
                                    onBack: { selectedTab = .workspace }, onChanged: refreshHome)
                    .transition(pageTransition)
            case .tasks:
                TasksWorkspaceView(model: tasksModel, onBack: { selectedTab = .workspace })
                    .transition(pageTransition)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.23), value: selectedTab)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .systemBackground).ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            MainNavigationBar(selection: $selectedTab)
        }
    }

    private var drawerAnimation: Animation? {
        reduceMotion ? nil : .spring(response: 0.34, dampingFraction: 0.88)
    }

    private func openAgentDrawer() {
        dismissKeyboard()
        withAnimation(drawerAnimation) {
            isAgentDrawerOpen = true
            drawerDragTranslation = 0
            isDraggingDrawer = false
        }
    }

    private func closeAgentDrawer() {
        dismissKeyboard()
        withAnimation(drawerAnimation) {
            isAgentDrawerOpen = false
            drawerDragTranslation = 0
            isDraggingDrawer = false
        }
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    /// 仅从对话页左边缘（<= 28pt）向右滑时跟手拉出历史抽屉，不干扰表格与列表滑动。
    private func edgeOpenDrawerGesture(drawerWidth: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                guard selectedTab == .chat, !isAgentDrawerOpen else { return }
                if !isDraggingDrawer {
                    guard value.startLocation.x <= 28,
                          value.translation.width > 0,
                          abs(value.translation.width) > abs(value.translation.height) * 1.2 else {
                        return
                    }
                    isDraggingDrawer = true
                    dismissKeyboard()
                }
                drawerDragTranslation = min(max(value.translation.width, 0), drawerWidth)
            }
            .onEnded { value in
                guard isDraggingDrawer else { return }
                isDraggingDrawer = false
                let predicted = value.predictedEndTranslation.width
                let shouldOpen = drawerDragTranslation > drawerWidth * 0.35 || predicted > drawerWidth * 0.5
                withAnimation(drawerAnimation) {
                    isAgentDrawerOpen = shouldOpen
                    drawerDragTranslation = 0
                }
            }
    }

    /// 抽屉开启时，支持在右侧露出的主界面遮罩区向左拖回关闭。
    private func closeDrawerDragGesture(drawerWidth: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard isAgentDrawerOpen else { return }
                if value.translation.width < 0 {
                    drawerDragTranslation = max(value.translation.width, -drawerWidth)
                }
            }
            .onEnded { value in
                guard isAgentDrawerOpen else { return }
                let predicted = value.predictedEndTranslation.width
                let shouldClose = drawerDragTranslation < -drawerWidth * 0.25 || predicted < -drawerWidth * 0.45
                withAnimation(drawerAnimation) {
                    isAgentDrawerOpen = !shouldClose
                    drawerDragTranslation = 0
                }
            }
    }

    private func handlePendingProactiveRoute() {
        guard let route = proactiveNotifications.consumePendingRoute() else { return }
        switch route {
        case .chat(let sessionId):
            selectedTab = .chat
            Task { await agentModel.openSessionFromNotification(sessionId: sessionId) }
        case .tasks:
            selectedTab = .tasks
        case .home:
            selectedTab = .home
        }
    }

    private func refreshHome() {
        Task {
            await homeModel.load(locale: locale)
            await financeModel.refresh()
        }
    }

    private var pageTransition: AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .offset(y: 8))
    }
}
