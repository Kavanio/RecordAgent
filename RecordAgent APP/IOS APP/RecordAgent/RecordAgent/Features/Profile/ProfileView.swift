import SwiftUI

/// 个人总览：会话身份与五类统计共享首页画布，右上角统一进入设置。
struct ProfileView: View {
    let user: AuthUser?
    let onLogout: () async throws -> Void
    @StateObject private var overviewModel: ProfileOverviewViewModel
    @Environment(\.locale) private var locale
    @Environment(\.calendar) private var calendar

    init(
        user: AuthUser?,
        overviewService: any ProfileOverviewServiceProtocol,
        onLogout: @escaping () async throws -> Void
    ) {
        self.user = user
        self.onLogout = onLogout
        _overviewModel = StateObject(
            wrappedValue: ProfileOverviewViewModel(service: overviewService))
    }

    private var name: String? {
        guard let user else { return nil }
        let nickname = user.nickname?.trimmingCharacters(in: .whitespacesAndNewlines)
        return nickname?.isEmpty == false ? nickname : user.email.components(separatedBy: "@").first
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    identity
                    ProfileOverviewPanel(state: overviewModel.state)
                }
                .padding(.horizontal, 24)
                .padding(.top, 18)
                .padding(.bottom, 32)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .background { HomeCanvas() }
            .toolbar(.hidden, for: .navigationBar)
            .task(id: LoadKey(userID: user?.id, month: monthStart, locale: locale.identifier)) {
                await overviewModel.load(userID: user?.id, month: monthStart)
            }
            .refreshable {
                await overviewModel.load(userID: user?.id, month: monthStart)
            }
        }
    }

    private var identity: some View {
        HStack(spacing: 16) {
            ProfileAvatarView(name: name, avatarURLString: user?.avatarUrl)
            VStack(alignment: .leading, spacing: 5) {
                if let name, !name.isEmpty {
                    Text(verbatim: name)
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(AppColors.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    Text("profile.cover.fallback")
                        .font(.title2.weight(.semibold))
                }
                if let user {
                    Text(verbatim: user.email)
                        .font(.footnote)
                        .foregroundStyle(AppColors.textSecondary)
                        .lineLimit(2)
                        .textSelection(.enabled)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            NavigationLink {
                ProfileSettingsView(user: user, onLogout: onLogout)
            } label: {
                Image(systemName: "gearshape")
                    .font(.system(size: 20, weight: .regular))
                    .foregroundStyle(AppColors.textPrimary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(Text("profile.settings.title"))
            .buttonStyle(.plain)
        }
        .frame(minHeight: 62)
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
