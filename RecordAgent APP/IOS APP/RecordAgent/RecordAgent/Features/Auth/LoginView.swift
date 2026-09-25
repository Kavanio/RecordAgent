import SwiftUI

/// 完全对齐高保真设计图的登录与注册主视图
@MainActor
public struct LoginView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel: AuthViewModel
    @FocusState private var focusedField: FormField?

    private enum FormField {
        case email, password, code
    }

    public init(viewModel: AuthViewModel) {
        _viewModel = StateObject(wrappedValue: viewModel)
    }

    public var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .top) {
                // 背景洁净底色
                AppColors.pageBackground
                    .ignoresSafeArea()

                // 点击空白处收起键盘
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture {
                        focusedField = nil
                    }

                // 主滚动容器
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 0) {
                        // 1. 顶部语言切换行
                        topLanguageBar

                        // 2. 头部品牌标语与插画区
                        heroHeaderSection
                            .padding(.top, 2)
                            .padding(.horizontal, 22)

                        // 3. 登录模式分段选择器与注册入口
                        segmentSwitcherSection
                            .padding(.top, 20)
                            .padding(.horizontal, 22)

                        // 4. 表单输入区
                        formInputSection
                            .padding(.top, 16)
                            .padding(.horizontal, 22)

                        // 5. 辅助选项（记住我 / 忘记密码）
                        if viewModel.authMode != .register {
                            auxiliaryOptionsSection
                                .padding(.top, 14)
                                .padding(.horizontal, 24)
                        }

                        // 6. 主操作按钮（登录/注册）
                        submitButtonSection
                            .padding(.top, 18)
                            .padding(.horizontal, 22)

                        // 7. 同步标语与官方高清底部装饰
                        bottomBrandingSection
                            .padding(.top, 24)
                            .padding(.horizontal, 22)

                        // 8. 隐私政策与用户协议
                        legalFooterSection
                    }
                    .frame(minHeight: geometry.size.height)
                }
            }
        }
        .alert(Text("common.alert.title"), isPresented: $viewModel.showErrorAlert) {
            Button("common.ok", role: .cancel) {}
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
        .sheet(isPresented: $viewModel.showAgreementSheet) {
            AgreementSheetView(type: viewModel.agreementType)
        }
        .sheet(isPresented: $viewModel.showForgotPasswordSheet) {
            ForgotPasswordSheetView()
        }
    }

    // MARK: - 1. 顶部语言切换行

    private var topLanguageBar: some View {
        HStack {
            Spacer()
            Button(action: {
                withAnimation(.easeInOut(duration: 0.2)) {
                    let nextLanguage: AppLanguage = (environment.selectedLanguage == .en) ? .zhHans : .en
                    environment.setLanguage(nextLanguage)
                }
            }) {
                HStack(spacing: 4) {
                    Image(systemName: "globe")
                        .font(.system(size: 12))
                    Text(environment.selectedLanguage == .en ? "En / 中" : "中 / En")
                        .font(.system(size: 13, weight: .regular))
                }
                .foregroundColor(AppColors.textSecondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Color.white.opacity(0.65))
                .clipShape(Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 22)
        .padding(.top, 4)
    }

    // MARK: - 2. 头部 Slogan 与插画区

    private var heroHeaderSection: some View {
        HStack(alignment: .bottom, spacing: 6) {
            // 左侧艺术书法字体标语
            Image("LoginPageTitle")
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity, alignment: .leading)
                .frame(height: 176)

            // 右侧插画区
            VStack(alignment: .trailing, spacing: -6) {
                Image("LoginPageBubble")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 76, height: 64)
                    .padding(.trailing, 18)

                Image("LoginPageBanner")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 178, height: 134)
            }
        }
    }

    // MARK: - 3. 胶囊模式切换栏

    private var segmentSwitcherSection: some View {
        HStack(spacing: 0) {
            if viewModel.authMode == .register {
                Text("auth.register.mode_title")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(AppColors.textPrimary)

                Spacer()

                Button(action: {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                        viewModel.authMode = .passwordLogin
                    }
                }) {
                    HStack(spacing: 3) {
                        Text("auth.back_to_login")
                            .font(.system(size: 14, weight: .medium))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .foregroundColor(AppColors.textSecondary)
                }
                .buttonStyle(.plain)
            } else {
                HStack(spacing: 3) {
                    segmentButton(titleKey: "auth.mode.password", mode: .passwordLogin)
                    segmentButton(titleKey: "auth.mode.code", mode: .codeLogin)
                }
                .padding(3)
                .background(AppColors.segmentBarBg)
                .clipShape(Capsule())

                Spacer()

                Button(action: {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                        viewModel.authMode = .register
                    }
                }) {
                    HStack(spacing: 3) {
                        Text("auth.register.switch")
                            .font(.system(size: 14, weight: .regular))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 11, weight: .medium))
                    }
                    .foregroundColor(AppColors.textSecondary)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func segmentButton(titleKey: LocalizedStringKey, mode: AuthMode) -> some View {
        let isSelected = viewModel.authMode == mode
        return Button(action: {
            withAnimation(.spring(response: 0.28, dampingFraction: 0.8)) {
                viewModel.authMode = mode
            }
        }) {
            Text(titleKey)
                .font(.system(size: 13.5, weight: isSelected ? .medium : .regular))
                .foregroundColor(isSelected ? AppColors.segmentSelectedText : AppColors.textSecondary)
                .padding(.horizontal, 16)
                .padding(.vertical, 7)
                .background(
                    isSelected ? AppColors.segmentSelectedBg : Color.clear
                )
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: - 4. 表单输入区

    private var formInputSection: some View {
        VStack(spacing: 12) {
            AppTextField(
                iconName: "envelope",
                placeholderKey: "auth.email.placeholder",
                text: $viewModel.email,
                keyboardType: .emailAddress
            )
            .focused($focusedField, equals: .email)

            switch viewModel.authMode {
            case .passwordLogin:
                AppTextField(
                    iconName: "lock",
                    placeholderKey: "auth.password.placeholder",
                    text: $viewModel.password,
                    isSecure: true
                )
                .focused($focusedField, equals: .password)

            case .codeLogin:
                AppTextField(
                    iconName: "shield.checkered",
                    placeholderKey: "auth.code.placeholder",
                    text: $viewModel.verificationCode,
                    keyboardType: .numberPad,
                    trailingAction: AnyView(verificationCodeButton)
                )
                .focused($focusedField, equals: .code)

            case .register:
                AppTextField(
                    iconName: "shield.checkered",
                    placeholderKey: "auth.code.placeholder",
                    text: $viewModel.verificationCode,
                    keyboardType: .numberPad,
                    trailingAction: AnyView(verificationCodeButton)
                )
                .focused($focusedField, equals: .code)

                AppTextField(
                    iconName: "lock",
                    placeholderKey: "auth.password.set_placeholder",
                    text: $viewModel.password,
                    isSecure: true
                )
                .focused($focusedField, equals: .password)
            }
        }
    }

    private var verificationCodeButton: some View {
        Button(action: {
            viewModel.sendVerificationCode()
        }) {
            if viewModel.countdownSeconds > 0 {
                Text("auth.code.resend \(viewModel.countdownSeconds)")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(AppColors.placeholderText)
                    .padding(.horizontal, 6)
            } else {
                Text("auth.code.get")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(AppColors.primaryBlue)
                    .padding(.horizontal, 6)
            }
        }
        .disabled(viewModel.countdownSeconds > 0)
        .buttonStyle(.plain)
    }

    // MARK: - 5. 辅助选项（记住我 / 忘记密码）

    private var auxiliaryOptionsSection: some View {
        HStack {
            Button(action: {
                viewModel.rememberMe.toggle()
            }) {
                HStack(spacing: 8) {
                    ZStack {
                        if viewModel.rememberMe {
                            RoundedRectangle(cornerRadius: 3.5)
                                .fill(AppColors.primaryBlue)
                                .frame(width: 16, height: 16)

                            Image(systemName: "checkmark")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(.white)
                        } else {
                            RoundedRectangle(cornerRadius: 3.5)
                                .stroke(Color(red: 0.78, green: 0.81, blue: 0.86), lineWidth: 1.2)
                                .frame(width: 16, height: 16)
                        }
                    }

                    Text("auth.remember_me")
                        .font(.system(size: 13))
                        .foregroundColor(AppColors.textSecondary)
                }
            }
            .buttonStyle(.plain)

            Spacer()

            Button(action: {
                viewModel.showForgotPasswordSheet = true
            }) {
                Text("auth.forgot_password")
                    .font(.system(size: 13))
                    .foregroundColor(AppColors.textSecondary)
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - 6. 主操作按钮

    private var submitButtonSection: some View {
        AppButton(
            viewModel.authMode == .register ? "auth.register.button" : "auth.login.button",
            iconName: "arrow.right",
            isLoading: viewModel.isLoading,
            isEnabled: true
        ) {
            focusedField = nil
            viewModel.submit()
        }
    }

    // MARK: - 7. 底部同步标语与官方高清装饰

    private var bottomBrandingSection: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                Rectangle()
                    .fill(Color(red: 0.88, green: 0.90, blue: 0.93))
                    .frame(width: 32, height: 0.8)

                Text("auth.sync_slogan")
                    .font(.system(size: 12))
                    .foregroundColor(AppColors.textTertiary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)

                Rectangle()
                    .fill(Color(red: 0.88, green: 0.90, blue: 0.93))
                    .frame(width: 32, height: 0.8)
            }

            Image("LoginPageFooter")
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity)
                .frame(height: 72)
                .padding(.horizontal, 10)
        }
    }

    // MARK: - 8. 隐私政策与协议

    private var legalFooterSection: some View {
        HStack(spacing: 12) {
            Button {
                viewModel.agreementType = .privacy
                viewModel.showAgreementSheet = true
            } label: {
                Text("auth.agreement.privacy_title")
            }
            .foregroundColor(AppColors.textTertiary)

            Text("|")
                .foregroundColor(Color(red: 0.88, green: 0.90, blue: 0.93))

            Button {
                viewModel.agreementType = .userTerms
                viewModel.showAgreementSheet = true
            } label: {
                Text("auth.agreement.terms_title")
            }
            .foregroundColor(AppColors.textTertiary)
        }
        .font(.system(size: 12))
        .buttonStyle(.plain)
        .padding(.top, 14)
        .padding(.bottom, 28)
    }
}
