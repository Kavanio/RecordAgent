import SwiftUI

/// 个人档案扉页：会话资料与编号目录；没有统计来源时仅展示记录题记。
struct ProfileView: View {
    let user: AuthUser?
    let onLogout: () async throws -> Void
    @EnvironmentObject private var environment: AppEnvironment
    @ScaledMetric(relativeTo: .largeTitle) private var nameSize = 38.0

    private var name: String? {
        guard let user else { return nil }
        let nickname = user.nickname?.trimmingCharacters(in: .whitespacesAndNewlines)
        return nickname?.isEmpty == false ? nickname : user.email.components(separatedBy: "@").first
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("profile.cover.label")
                        .font(.caption.weight(.medium))
                        .tracking(2)
                        .foregroundStyle(.secondary)
                        .padding(.bottom, 36)

                    HStack(alignment: .top, spacing: 20) {
                        VStack(alignment: .leading, spacing: 12) {
                            if let name, !name.isEmpty {
                                Text(verbatim: name)
                                    .font(.system(size: nameSize, weight: .semibold))
                                    .tracking(-1)
                                    .fixedSize(horizontal: false, vertical: true)
                            } else {
                                Text("profile.cover.fallback")
                                    .font(.largeTitle.weight(.semibold))
                            }
                            if let user {
                                Text(verbatim: user.email)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                    .textSelection(.enabled)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        // 字母印记只作装饰，不重复朗读用户身份。
                        Group {
                            if let initial = name?.first {
                                Text(verbatim: String(initial).uppercased())
                                    .font(.system(.title2, design: .serif).weight(.medium))
                            } else {
                                Image(systemName: "person")
                                    .font(.title2.weight(.light))
                            }
                        }
                        .frame(width: 54, height: 54)
                        .overlay(Circle().strokeBorder(.primary.opacity(0.18), lineWidth: 1))
                        .padding(.top, 18)
                        .accessibilityHidden(true)
                    }

                    VStack(alignment: .leading, spacing: 20) {
                        Rectangle()
                            .fill(AppColors.primaryBlue)
                            .frame(width: 22, height: 2)
                            .accessibilityHidden(true)
                        Text("profile.cover.note")
                            .font(.system(.title, design: .serif).weight(.regular))
                            .lineSpacing(8)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.top, 56)
                    .padding(.bottom, 48)

                    VStack(spacing: 0) {
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
                                number: 2, title: "profile.preferences.title",
                                summary: languageSummary)
                        }
                        Divider()
                        NavigationLink {
                            ProfileAboutView()
                        } label: {
                            ProfileDirectoryRow(
                                number: 3, title: "profile.about.title",
                                summary: Text("profile.about.summary"))
                        }
                    }
                    .buttonStyle(.plain)

                    Text("profile.cover.footer")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .padding(.top, 36)
                }
                .padding(.horizontal, 28)
                .padding(.top, 24)
                .padding(.bottom, 28)
                .frame(maxWidth: 600, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .background(Color(uiColor: .systemBackground).ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    private var languageSummary: Text {
        environment.selectedLanguage == .system
            ? Text("language.system")
            : Text(verbatim: environment.selectedLanguage.displayName)
    }
}
