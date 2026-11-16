import SwiftUI

/// 设置页内的后端连接配置：支持切换本地/远程服务地址与 API Token，校验通过后立即生效。
struct DebugServerConnectionView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @State private var serverAddress = APIConfiguration.savedServerAddress()
    @State private var apiToken = ""
    @State private var state: SaveState = .idle

    private enum SaveState: Equatable {
        case idle
        case saving
        case saved
        case failed(String)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                AppSettingsGroup(title: "settings.backend.server") {
                    TextField("auth.server.placeholder", text: $serverAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .padding(20)
                }

                AppSettingsGroup(title: "settings.backend.token") {
                    SecureField("settings.backend.token_placeholder", text: $apiToken)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .padding(20)
                }

                Button {
                    Task { await saveAndVerify() }
                } label: {
                    Text(state == .saving ? "settings.backend.saving" : "settings.backend.save")
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 36)
                }
                .buttonStyle(.borderedProminent)
                .disabled(state == .saving)

                switch state {
                case .idle:
                    EmptyView()
                case .saving:
                    ProgressView("debug.server.checking")
                case .saved:
                    Label("settings.backend.saved", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                case .failed(let message):
                    Label(message, systemImage: "exclamationmark.circle")
                        .foregroundStyle(.red)
                }
            }
            .padding(24)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("settings.backend.title")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
    }

    @MainActor
    private func saveAndVerify() async {
        let trimmedAddress = serverAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedAddress.isEmpty else {
            state = .failed(String(localized: "auth.error.empty_server", defaultValue: "请输入后端服务地址"))
            return
        }
        let trimmedToken = apiToken.trimmingCharacters(in: .whitespacesAndNewlines)
        let effectiveToken: String
        if trimmedToken.isEmpty,
           let current = environment.authService.getCurrentSession(),
           let currentServer = try? APIConfiguration.serverIdentity(),
           let candidateURL = try? APIConfiguration.validateBaseURL(trimmedAddress),
           candidateURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) == currentServer
        {
            effectiveToken = current.token
        } else {
            effectiveToken = trimmedToken
        }

        state = .saving
        do {
            _ = try await environment.authService.connect(
                serverAddress: trimmedAddress,
                token: effectiveToken
            )
            serverAddress = APIConfiguration.savedServerAddress()
            apiToken = ""
            state = .saved
        } catch {
            state = .failed(error.localizedDescription)
        }
    }
}
