import SwiftUI

/// 关于页从应用包读取版本，协议内容复用登录流程的统一入口。
struct ProfileAboutView: View {
    @State private var agreement: AgreementType?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                VStack(alignment: .leading, spacing: 12) {
                    Text(verbatim: "RecordAgent")
                        .font(.largeTitle.weight(.semibold))
                    Text("profile.about.note")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 20)

                AppSettingsGroup(title: "profile.about.app") {
                    AppSettingsRow(title: "profile.about.version") {
                        if let version = Bundle.main.object(
                            forInfoDictionaryKey: "CFBundleShortVersionString") as? String
                        {
                            Text(verbatim: version).foregroundStyle(.secondary)
                        } else {
                            Text("profile.account.unavailable").foregroundStyle(.secondary)
                        }
                    }
                }
                AppSettingsGroup(title: "profile.about.legal") {
                    agreementButton(.privacy)
                    Divider().padding(.horizontal, 20)
                    agreementButton(.userTerms)
                }
            }
            .padding(24)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("profile.about.title")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .sheet(item: $agreement) { AgreementSheetView(type: $0) }
    }

    private func agreementButton(_ type: AgreementType) -> some View {
        Button {
            agreement = type
        } label: {
            AppSettingsRow(title: type.title) {
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
        }
        .buttonStyle(.plain)
    }
}
