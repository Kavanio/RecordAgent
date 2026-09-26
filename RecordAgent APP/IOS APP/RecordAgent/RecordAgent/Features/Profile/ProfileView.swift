import SwiftUI

/// “我的”主页面：会话身份、五类个人统计与设置入口共享首页的轻盈画布。
struct ProfileView: View {
    let user: AuthUser?
    let onLogout: () async throws -> Void
    @StateObject private var overviewModel: ProfileOverviewViewModel
    @EnvironmentObject private var environment: AppEnvironment
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
                    destinations
                }
                .padding(.horizontal, 24)
                .padding(.top, 22)
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
        VStack(alignment: .leading, spacing: 22) {
            Text("tab.mine")
                .font(.largeTitle.weight(.bold))
                .foregroundStyle(AppColors.textPrimary)

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
                    ProfileAccountView(user: user, onLogout: onLogout)
                } label: {
                    Image(systemName: "arrow.up.right")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.textSecondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("profile.account.title"))
            }
        }
    }

    private var destinations: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("profile.destinations.title")
                .font(.headline)
                .foregroundStyle(AppColors.textPrimary)
                .padding(.bottom, 8)

            NavigationLink {
                ProfileAccountView(user: user, onLogout: onLogout)
            } label: {
                ProfileDirectoryRow(
                    number: 1, title: "profile.account.title",
                    summary: Text("profile.account.summary"))
            }
            Divider()
            NavigationLink {
                ProfilePreferencesView()
            } label: {
                ProfileDirectoryRow(
                    number: 2, title: "profile.preferences.title", summary: languageSummary)
            }
            Divider()
            NavigationLink {
                ProfileAboutView()
            } label: {
                ProfileDirectoryRow(
                    number: 3, title: "profile.about.title", summary: Text("profile.about.summary"))
            }
        }
        .buttonStyle(.plain)
    }

    private var monthStart: Date {
        calendar.dateInterval(of: .month, for: .now)?.start ?? calendar.startOfDay(for: .now)
    }

    private var languageSummary: Text {
        environment.selectedLanguage == .system
            ? Text("language.system")
            : Text(verbatim: environment.selectedLanguage.displayName)
    }

    private struct LoadKey: Equatable {
        let userID: String?
        let month: Date
        let locale: String
    }
}
