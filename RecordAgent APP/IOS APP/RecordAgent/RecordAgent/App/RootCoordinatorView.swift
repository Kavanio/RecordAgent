import SwiftUI

/// 根导航协调器：负责在未登录（登录页）与已登录（主界面）之间无缝切换
@MainActor
public struct RootCoordinatorView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var authViewModel: AuthViewModel

    public init(authViewModel: AuthViewModel) {
        _authViewModel = StateObject(wrappedValue: authViewModel)
    }

    public var body: some View {
        Group {
            if authViewModel.isRestoringSession {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(uiColor: .systemBackground).ignoresSafeArea())
            } else if authViewModel.isAuthenticated {
                MainTabView(
                    homeService: environment.homeService,
                    calendarService: environment.calendarService,
                    financeService: environment.financeService,
                    financeWorkspaceService: environment.financeWorkspaceService,
                    libraryService: environment.libraryService,
                    studyService: environment.studyService,
                    mobileTaskService: environment.mobileTaskService,
                    mobileTaskNotifications: environment.mobileTaskNotifications,
                    notificationAccountID: notificationAccountID,
                    profileOverviewService: environment.profileOverviewService,
                    profileActivityService: environment.profileActivityService,
                    profileAppearanceService: environment.profileAppearanceService,
                    user: environment.authService.getCurrentSession()?.user,
                    onLogout: {
                        try await environment.authService.logout()
                        await environment.mobileTaskNotifications.clearAll()
                        authViewModel.isAuthenticated = false
                    })
            } else {
                LoginView(viewModel: authViewModel)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: authViewModel.isAuthenticated)
        .onReceive(NotificationCenter.default.publisher(for: .apiSessionExpired)) { _ in
            authViewModel.isAuthenticated = false
            Task { await environment.mobileTaskNotifications.clearAll() }
        }
    }

    /// 服务器身份与稳定用户 ID 一起隔离设备通知，避免切换测试服务后串用任务。
    private var notificationAccountID: String {
        guard let user = environment.authService.getCurrentSession()?.user,
              let server = try? APIConfiguration.serverIdentity() else { return "" }
        return "\(server)|\(user.id)"
    }

}

/// 登录后的主界面预览容器（待对接 Phase 1 日历与主工作台）
struct AuthenticatedMainView: View {
    let onLogout: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer()

                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 64))
                    .foregroundColor(AppColors.primaryBlue)

                Text("登录成功！")
                    .font(.title.bold())
                    .foregroundColor(AppColors.textPrimary)

                Text("欢迎使用 RecordAgent 移动端。\n即将为您加载日历、财务、资料、记录与任务。")
                    .font(.subheadline)
                    .foregroundColor(AppColors.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)

                Spacer()

                Button(action: onLogout) {
                    Text("退出登录")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(.red)
                        .padding(.vertical, 12)
                        .padding(.horizontal, 32)
                        .background(Color.red.opacity(0.1))
                        .clipShape(Capsule())
                }
                .padding(.bottom, 40)
            }
            .navigationTitle("RecordAgent")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
