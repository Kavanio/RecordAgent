import SwiftUI

enum AppTab: String, CaseIterable, Identifiable {
    case home, calendar, workspace, finance, library, record, tasks, profile
    var id: String { rawValue }
    static let primaryTabs: [AppTab] = [.home, .calendar, .workspace, .profile]
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
    @StateObject private var homeModel: HomeViewModel
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let user: AuthUser?
    let calendarService: any CalendarDataServiceProtocol
    let profileOverviewService: any ProfileOverviewServiceProtocol
    let profileActivityService: any ProfileActivityServiceProtocol
    let profileAppearanceService: any ProfileAppearanceServiceProtocol
    let onLogout: () async throws -> Void

    init(
        homeService: any HomeDataServiceProtocol,
        calendarService: any CalendarDataServiceProtocol,
        profileOverviewService: any ProfileOverviewServiceProtocol,
        profileActivityService: any ProfileActivityServiceProtocol,
        profileAppearanceService: any ProfileAppearanceServiceProtocol,
        user: AuthUser?,
        onLogout: @escaping () async throws -> Void
    ) {
        _homeModel = StateObject(wrappedValue: HomeViewModel(service: homeService))
        self.calendarService = calendarService
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
        ZStack {
            switch selectedTab {
            case .home:
                HomeView(viewModel: homeModel)
                    .transition(pageTransition)
            case .calendar:
                CalendarView(home: homeModel, service: calendarService)
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
                    onLogout: onLogout
                )
                .transition(pageTransition)
            default:
                WorkspacePreviewView(
                    tab: selectedTab,
                    viewModel: homeModel,
                    onBack: selectedTab.navigationTab == .workspace
                        ? { selectedTab = .workspace } : nil
                )
                .id(selectedTab)
                .transition(pageTransition)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.23), value: selectedTab)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            MainNavigationBar(selection: $selectedTab)
        }
        .tint(AppColors.primaryBlue)
        .task(id: LoadKey(date: homeModel.selectedDate, locale: locale.identifier)) {
            await homeModel.load(locale: locale)
        }
    }

    private var pageTransition: AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .offset(y: 8))
    }
}
