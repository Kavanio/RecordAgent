import SwiftUI

/// 个人中心/我的页面
struct ProfileView: View {
    @EnvironmentObject private var environment: AppEnvironment
    let onLogout: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    // 用户简要资料卡片
                    HStack(spacing: 16) {
                        Circle()
                            .fill(AppColors.segmentSelectedBg)
                            .frame(width: 60, height: 60)
                            .overlay(
                                Image(systemName: "person.fill")
                                    .font(.system(size: 28))
                                    .foregroundColor(AppColors.primaryBlue)
                            )

                        VStack(alignment: .leading, spacing: 4) {
                            Text("profile.user_name")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(AppColors.textPrimary)

                            Text("profile.recorded_days \(128)")
                                .font(.system(size: 13))
                                .foregroundColor(AppColors.textSecondary)
                        }

                        Spacer()
                    }
                    .padding(20)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .shadow(color: Color.black.opacity(0.03), radius: 8, y: 2)
                    .padding(.horizontal, 16)
                    .padding(.top, 16)

                    // 通用设置卡片
                    VStack(alignment: .leading, spacing: 12) {
                        Text("profile.settings.section.general")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(AppColors.textSecondary)
                            .padding(.horizontal, 4)

                        VStack(spacing: 0) {
                            // 语言切换选项行
                            HStack {
                                Image(systemName: "globe")
                                    .font(.system(size: 18))
                                    .foregroundColor(AppColors.primaryBlue)
                                    .frame(width: 28)

                                Text("profile.settings.language")
                                    .font(.system(size: 15, weight: .regular))
                                    .foregroundColor(AppColors.textPrimary)

                                Spacer()

                                Menu {
                                    ForEach(AppLanguage.allCases) { lang in
                                        Button {
                                            withAnimation(.easeInOut(duration: 0.2)) {
                                                environment.setLanguage(lang)
                                            }
                                        } label: {
                                            HStack {
                                                Text(lang.displayName)
                                                if environment.selectedLanguage == lang {
                                                    Image(systemName: "checkmark")
                                                }
                                            }
                                        }
                                    }
                                } label: {
                                    HStack(spacing: 6) {
                                        Text(environment.selectedLanguage.displayName)
                                            .font(.system(size: 14))
                                            .foregroundColor(AppColors.textSecondary)

                                        Image(systemName: "chevron.up.chevron.down")
                                            .font(.system(size: 12))
                                            .foregroundColor(AppColors.placeholderText)
                                    }
                                    .padding(.vertical, 8)
                                    .padding(.horizontal, 10)
                                    .background(Color.black.opacity(0.03))
                                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                                }
                            }
                            .padding(.horizontal, 16)
                            .frame(height: 54)
                        }
                        .background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .shadow(color: Color.black.opacity(0.03), radius: 8, y: 2)
                    }
                    .padding(.horizontal, 16)

                    Spacer(minLength: 40)

                    // 退出登录按钮
                    Button(action: onLogout) {
                        Text("auth.logout")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(Color.red.opacity(0.9))
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .background(Color.red.opacity(0.08))
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 24)
                }
            }
            .background(AppColors.pageBackground)
            .navigationTitle(Text("tab.profile"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
