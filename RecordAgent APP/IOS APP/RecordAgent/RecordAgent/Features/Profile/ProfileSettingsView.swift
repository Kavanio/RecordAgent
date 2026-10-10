import SwiftUI

/// 从个人总览的齿轮入口集中访问账户、偏好与应用信息。
struct ProfileSettingsView: View {
    let user: AuthUser?
    let nicknameOverride: String?
    let onLogout: () async throws -> Void
    @EnvironmentObject private var environment: AppEnvironment

    var body: some View {
        ScrollView {
            VStack(spacing: 28) {
                AppSettingsGroup(title: "profile.settings.section.account") {
                    NavigationLink {
                        ProfileAccountView(
                            user: user, nicknameOverride: nicknameOverride, onLogout: onLogout)
                    } label: {
                        settingsRow(title: "profile.account.title")
                    }
                    .buttonStyle(.plain)
                }

                AppSettingsGroup(title: "profile.settings.section.app") {
                    #if DEBUG
                    NavigationLink {
                        DebugServerConnectionView()
                    } label: {
                        settingsRow(title: "debug.server.title")
                    }
                    .buttonStyle(.plain)
                    Divider().padding(.horizontal, 20)
                    #endif
                    NavigationLink {
                        ProfilePreferencesView()
                    } label: {
                        AppSettingsRow(title: "profile.preferences.title") {
                            HStack(spacing: 8) {
                                languageSummary
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                chevron
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    Divider().padding(.horizontal, 20)
                    NavigationLink {
                        ProfileAboutView()
                    } label: {
                        settingsRow(title: "profile.about.title")
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(24)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("profile.settings.title")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
    }

    private func settingsRow(title: LocalizedStringKey) -> some View {
        AppSettingsRow(title: title) { chevron }
    }

    private var chevron: some View {
        Image(systemName: "chevron.right")
            .font(.caption)
            .foregroundStyle(.secondary)
            .accessibilityHidden(true)
    }

    private var languageSummary: Text {
        environment.selectedLanguage == .system
            ? Text("language.system")
            : Text(verbatim: environment.selectedLanguage.displayName)
    }
}
