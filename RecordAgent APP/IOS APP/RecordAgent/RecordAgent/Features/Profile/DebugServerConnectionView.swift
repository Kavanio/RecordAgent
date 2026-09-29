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
        guard let baseURL = URL(string: serverAddress.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = baseURL.scheme?.lowercased(),
              ["http", "https"].contains(scheme),
              baseURL.host != nil,
              let url = URL(string: "/actuator/health", relativeTo: baseURL)?.absoluteURL
        else {
            result = .failed
            return
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let healthy = (response as? HTTPURLResponse)?.statusCode == 200
                && String(data: data, encoding: .utf8)?.contains("\"UP\"") == true
            result = healthy ? .connected : .failed
        } catch {
            result = .failed
        }
    }
}
#endif
