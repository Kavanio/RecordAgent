import SwiftUI

/// 账户信息与退出交互；退出成功后由根协调器关闭已登录界面。
struct ProfileAccountView: View {
    let user: AuthUser?
    let nicknameOverride: String?
    let onLogout: () async throws -> Void
    @State private var confirmLogout = false
    @State private var isLoggingOut = false
    @State private var logoutFailed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                AppSettingsGroup(title: "profile.account.details") {
                    AppSettingsRow(title: "profile.account.nickname") {
                        if let nickname = nicknameOverride ?? user?.nickname, !nickname.isEmpty {
                            Text(verbatim: nickname).foregroundStyle(.secondary)
                        } else {
                            Text("profile.account.unset").foregroundStyle(.secondary)
                        }
                    }
                    Divider().padding(.horizontal, 20)
                    AppSettingsRow(title: "profile.account.email") {
                        if let user {
                            Text(verbatim: user.email)
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                                .fixedSize(horizontal: false, vertical: true)
                        } else {
                            Text("profile.account.unavailable").foregroundStyle(.secondary)
                        }
                    }
                }

                Button(role: .destructive) {
                    confirmLogout = true
                } label: {
                    HStack(spacing: 10) {
                        if isLoggingOut { ProgressView() }
                        Text(isLoggingOut ? "profile.logout.progress" : "auth.logout")
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.red)
                .disabled(isLoggingOut)
            }
            .padding(24)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("profile.account.title")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .navigationBarBackButtonHidden(isLoggingOut)
        .confirmationDialog(
            "profile.logout.confirm", isPresented: $confirmLogout, titleVisibility: .visible
        ) {
            Button("auth.logout", role: .destructive) { isLoggingOut = true }
            Button("common.cancel", role: .cancel) {}
        }
        .alert("profile.logout.failed", isPresented: $logoutFailed) {
            Button("common.confirm", role: .cancel) {}
        } message: {
            Text("profile.logout.retry")
        }
        .task(id: isLoggingOut) {
            guard isLoggingOut else { return }
            do {
                try await onLogout()
            } catch is CancellationError {
                // 离开账户页时取消尚未完成的退出请求。
                isLoggingOut = false
            } catch {
                isLoggingOut = false
                logoutFailed = true
            }
        }
    }
}
