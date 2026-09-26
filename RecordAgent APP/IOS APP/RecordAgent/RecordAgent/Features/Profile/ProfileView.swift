import SwiftUI

/// 个人总览：会话身份与五类统计共享首页画布，右上角统一进入设置。
struct ProfileView: View {
    let user: AuthUser?
    let onLogout: () async throws -> Void
    let onOpenActivity: (HomeEntry) -> Void
    @StateObject private var overviewModel: ProfileOverviewViewModel
    @StateObject private var activityModel: ProfileActivityViewModel
    @StateObject private var appearanceModel: ProfileAppearanceViewModel
    @State private var showsSettings = false
    @State private var showsEdit = false
    @Environment(\.locale) private var locale
    @Environment(\.calendar) private var calendar

    init(
        user: AuthUser?,
        overviewService: any ProfileOverviewServiceProtocol,
        activityService: any ProfileActivityServiceProtocol,
        appearanceService: any ProfileAppearanceServiceProtocol,
        onOpenActivity: @escaping (HomeEntry) -> Void,
        onLogout: @escaping () async throws -> Void
    ) {
        self.user = user
        self.onLogout = onLogout
        self.onOpenActivity = onOpenActivity
        _overviewModel = StateObject(
            wrappedValue: ProfileOverviewViewModel(service: overviewService))
        _activityModel = StateObject(
            wrappedValue: ProfileActivityViewModel(service: activityService))
        _appearanceModel = StateObject(
            wrappedValue: ProfileAppearanceViewModel(service: appearanceService))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ProfileIdentityHeader(
                        user: user,
                        appearance: appearanceModel.appearance,
                        onEdit: { showsEdit = true },
                        onSettings: { showsSettings = true })

                    VStack(alignment: .leading, spacing: 26) {
                        ProfileOverviewPanel(state: overviewModel.state)
                        ProfileActivitySection(state: activityModel.state, onOpen: onOpenActivity)
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 32)
                    .frame(maxWidth: 640)
                    .frame(maxWidth: .infinity)
                }
            }
            .ignoresSafeArea(edges: .top)
            .background { HomeCanvas() }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: $showsSettings) {
                ProfileSettingsView(
                    user: user,
                    nicknameOverride: appearanceModel.appearance.nickname,
                    onLogout: onLogout)
            }
            .sheet(isPresented: $showsEdit) {
                if let user {
                    ProfileEditView(user: user, current: appearanceModel.appearance) { updated in
                        await appearanceModel.save(updated, account: user.email)
                    }
                }
            }
            .task(id: LoadKey(userID: user?.id, month: monthStart, locale: locale.identifier)) {
                async let overview: Void = overviewModel.load(userID: user?.id, month: monthStart)
                async let activity: Void = activityModel.load(
                    account: user?.email, month: monthStart, locale: locale)
                _ = await (overview, activity)
            }
            .task(id: user?.email) { await appearanceModel.load(account: user?.email) }
            .refreshable {
                async let overview: Void = overviewModel.load(userID: user?.id, month: monthStart)
                async let activity: Void = activityModel.load(
                    account: user?.email, month: monthStart, locale: locale)
                _ = await (overview, activity)
            }
            .alert(
                "profile.edit.saveFailed",
                isPresented: Binding(
                    get: { appearanceModel.error != nil },
                    set: { if !$0 { appearanceModel.error = nil } }
                )
            ) { Button("common.confirm", role: .cancel) {} }
        }
    }

    private var monthStart: Date {
        calendar.dateInterval(of: .month, for: .now)?.start ?? calendar.startOfDay(for: .now)
    }

    private struct LoadKey: Equatable {
        let userID: String?
        let month: Date
        let locale: String
    }
}
