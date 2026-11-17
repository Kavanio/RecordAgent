import SwiftUI
import UIKit

/// 主动推送与提醒设置页：管理系统通知权限、三大事件订阅开关、前台 SSE 与 APNs 通道状态及测试推送。
struct ProactivePushSettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.openURL) private var openURL
    @State private var isSendingTest = false
    @State private var testFeedback: String?

    private var pushCenter: ProactiveNotificationCenter {
        environment.proactiveNotifications
    }

    var body: some View {
        ProactivePushSettingsContent(
            pushCenter: pushCenter,
            isSendingTest: $isSendingTest,
            testFeedback: $testFeedback,
            openSystemSettings: {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    openURL(url)
                }
            }
        )
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("push.settings.title")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
    }
}

private struct ProactivePushSettingsContent: View {
    @ObservedObject var pushCenter: ProactiveNotificationCenter
    @Binding var isSendingTest: Bool
    @Binding var testFeedback: String?
    let openSystemSettings: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 28) {
                permissionGroup
                categoryGroup
                channelStatusGroup
                testGroup
            }
            .padding(24)
        }
        .task {
            await pushCenter.refreshPermission()
            await pushCenter.refreshNoticesHistory()
        }
    }

    private var permissionGroup: some View {
        AppSettingsGroup(title: "push.settings.section.permission") {
            AppSettingsRow(title: "push.permission.status") {
                permissionBadge
            }
            if pushCenter.permission == .notDetermined {
                Divider().padding(.horizontal, 20)
                Button {
                    Task { await pushCenter.requestNotificationPermission() }
                } label: {
                    AppSettingsRow(title: "push.permission.request") {
                        Image(systemName: "bell.badge")
                            .foregroundStyle(AppColors.primaryBlue)
                    }
                }
                .buttonStyle(.plain)
            } else if pushCenter.permission == .denied {
                Divider().padding(.horizontal, 20)
                Button(action: openSystemSettings) {
                    AppSettingsRow(title: "push.permission.open_settings") {
                        Image(systemName: "arrow.up.forward.app")
                            .foregroundStyle(AppColors.primaryBlue)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var categoryGroup: some View {
        AppSettingsGroup(title: "push.settings.section.categories") {
            categoryToggleRow(
                category: .companion,
                binding: Binding(
                    get: { pushCenter.preferences.companionEnabled },
                    set: { value in
                        var next = pushCenter.preferences
                        next.companionEnabled = value
                        Task { await pushCenter.updatePreferences(next) }
                    }
                )
            )
            Divider().padding(.horizontal, 20)
            categoryToggleRow(
                category: .signal,
                binding: Binding(
                    get: { pushCenter.preferences.signalEnabled },
                    set: { value in
                        var next = pushCenter.preferences
                        next.signalEnabled = value
                        Task { await pushCenter.updatePreferences(next) }
                    }
                )
            )
            Divider().padding(.horizontal, 20)
            categoryToggleRow(
                category: .automation,
                binding: Binding(
                    get: { pushCenter.preferences.automationEnabled },
                    set: { value in
                        var next = pushCenter.preferences
                        next.automationEnabled = value
                        Task { await pushCenter.updatePreferences(next) }
                    }
                )
            )
        }
    }

    private func categoryToggleRow(
        category: ProactivePushCategory,
        binding: Binding<Bool>
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle(isOn: binding) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(category.titleKey)
                        .font(.body)
                        .foregroundStyle(.primary)
                    Text(category.descriptionKey)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(AppColors.primaryBlue)
        }
        .padding(20)
    }

    private var channelStatusGroup: some View {
        AppSettingsGroup(title: "push.settings.section.channels") {
            AppSettingsRow(title: "push.channel.sse") {
                Text(pushCenter.sseConnected ? "push.status.connected" : "push.status.reconnecting")
                    .font(.subheadline)
                    .foregroundStyle(pushCenter.sseConnected ? AppColors.primaryBlue : .secondary)
            }
            Divider().padding(.horizontal, 20)
            AppSettingsRow(title: "push.channel.apns_device") {
                apnsDeviceLabel
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }
            Divider().padding(.horizontal, 20)
            AppSettingsRow(title: "push.channel.apns_server") {
                serverApnsLabel
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }
            if case .signingUnavailable = pushCenter.apnsState {
                Divider().padding(.horizontal, 20)
                Text("push.apns.signing_hint")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(20)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var testGroup: some View {
        AppSettingsGroup(title: "push.settings.section.diagnostics") {
            Button {
                guard !isSendingTest else { return }
                isSendingTest = true
                testFeedback = nil
                Task {
                    let result = await pushCenter.sendTestPush()
                    isSendingTest = false
                    if let result {
                        testFeedback = result.apnsSummary
                    } else {
                        testFeedback = pushCenter.lastSyncError ?? String(localized: "push.test.failed")
                    }
                }
            } label: {
                AppSettingsRow(title: "push.test.button") {
                    if isSendingTest {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "paperplane")
                            .foregroundStyle(AppColors.primaryBlue)
                    }
                }
            }
            .buttonStyle(.plain)
            .disabled(isSendingTest)

            if let testFeedback, !testFeedback.isEmpty {
                Divider().padding(.horizontal, 20)
                Text(verbatim: testFeedback)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(20)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var permissionBadge: some View {
        let key: LocalizedStringKey
        switch pushCenter.permission {
        case .authorized: key = "push.permission.authorized"
        case .provisional: key = "push.permission.provisional"
        case .denied: key = "push.permission.denied"
        case .notDetermined: key = "push.permission.not_determined"
        }
        return Text(key)
            .font(.subheadline)
            .foregroundStyle(pushCenter.permission.canRegister ? AppColors.primaryBlue : .secondary)
    }

    private var apnsDeviceLabel: Text {
        switch pushCenter.apnsState {
        case .idle:
            return Text("push.apns.state.idle")
        case .registering:
            return Text("push.apns.state.registering")
        case .registered(let prefix):
            return Text(verbatim: prefix)
        case .signingUnavailable:
            return Text("push.apns.state.signing_unavailable")
        case .failed:
            return Text("push.apns.state.failed")
        }
    }

    private var serverApnsLabel: Text {
        guard let status = pushCenter.serverStatus else {
            return Text("push.apns.server.unknown")
        }
        if status.apnsEnabled && status.apnsConfigured {
            return Text("push.apns.server.ready")
        }
        return Text("push.apns.server.unconfigured")
    }
}
