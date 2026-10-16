import SwiftUI

/// 已接入的本机偏好，语言选择沿用应用唯一持久化入口。
struct ProfilePreferencesView: View {
    @EnvironmentObject private var environment: AppEnvironment

    var body: some View {
        ScrollView {
            AppSettingsGroup(title: "profile.settings.language") {
                ForEach(AppLanguage.allCases) { language in
                    Button {
                        environment.setLanguage(language)
                    } label: {
                        AppSettingsRow(
                            title: language == .system
                                ? "language.system" : LocalizedStringKey(language.displayName)
                        ) {
                            if environment.selectedLanguage == language {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(AppColors.primaryBlue)
                                    .accessibilityHidden(true)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(
                        environment.selectedLanguage == language ? .isSelected : [])
                    if language != AppLanguage.allCases.last {
                        Divider().padding(.horizontal, 20)
                    }
                }
            }
            .padding(24)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("profile.preferences.title")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
    }
}
