import SwiftUI

/// Debug 真机局域网连通性检查；地址仅保存在本机，不包含凭据。
#if DEBUG
struct DebugServerConnectionView: View {
    @AppStorage("debug_server_base_url") private var serverAddress = ""
    @State private var result: ResultState = .idle

    private enum ResultState: Equatable {
        case idle
        case checking
        case connected
        case failed
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                AppSettingsGroup(title: "debug.server.address") {
                    TextField("debug.server.placeholder", text: $serverAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .padding(20)
                }
                Button {
                    Task { await checkConnection() }
                } label: {
                    Text("debug.server.check")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(result == .checking)

                switch result {
                case .idle: EmptyView()
                case .checking: ProgressView("debug.server.checking")
                case .connected: Label("debug.server.connected", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                case .failed: Label("debug.server.failed", systemImage: "exclamationmark.circle")
                    .foregroundStyle(.red)
                }
            }
            .padding(24)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("debug.server.title")
        .navigationBarTitleDisplayMode(.inline)
    }

    @MainActor
    private func checkConnection() async {
        result = .checking
        result = await DebugServerProbe().isHealthy(address: serverAddress) ? .connected : .failed
    }
}
#endif
