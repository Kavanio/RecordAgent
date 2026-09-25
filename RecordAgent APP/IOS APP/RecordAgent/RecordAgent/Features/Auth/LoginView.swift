import SwiftUI

/// 完全对齐高保真设计图的登录与注册主视图
@MainActor
public struct LoginView: View {
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

                        // 2. 头部品牌标语与生动插画
                        heroHeaderSection
                            .padding(.top, 4)
                            .padding(.horizontal, 20)

                        // 3. 登录模式分段选择器与注册入口
                        segmentSwitcherSection
                            .padding(.top, 24)
                            .padding(.horizontal, 20)

                        // 4. 表单输入区
                        formInputSection
                            .padding(.top, 18)
                            .padding(.horizontal, 20)

                        // 5. 辅助选项（记住我 / 忘记密码）
                        if viewModel.authMode != .register {
                            auxiliaryOptionsSection
                                .padding(.top, 14)
                                .padding(.horizontal, 22)
                        }

                        // 6. 主操作按钮（登录/注册）
                        submitButtonSection
                            .padding(.top, 20)
                            .padding(.horizontal, 20)

                        // 7. 同步标语与底部装饰插画
                        bottomBrandingSection
                            .padding(.top, 28)
                            .padding(.horizontal, 20)

                        // 8. 隐私政策与用户协议
                        legalFooterSection
                            .padding(.top, 24)
                            .padding(.bottom, max(geometry.safeAreaInsets.bottom, 20))
                    }
                    .frame(minHeight: geometry.size.height)
                }
            }
        }
        .alert("提示", isPresented: $viewModel.showErrorAlert) {
            Button("好的", role: .cancel) {}
        } message: {
            Text(viewModel.errorMessage ?? "未知错误")
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
                    viewModel.toggleLanguage()
                }
            }) {
                Text(viewModel.currentLanguage == "中" ? "中 / En" : "En / 中")
                    .font(.system(size: 14, weight: .regular))
                    .foregroundColor(AppColors.textSecondary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.white.opacity(0.6))
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.top, 6)
    }

    // MARK: - 2. 头部 Slogan 与插画区

    private var heroHeaderSection: some View {
        HStack(alignment: .bottom, spacing: 6) {
            // 左侧排版标语
            VStack(alignment: .leading, spacing: 3) {
                Text("空闲的时候，")
                    .font(.system(size: 26, weight: .bold, design: .serif))
                    .foregroundColor(AppColors.textPrimary)

                // "record" 艺术字手写体带高光
                ZStack(alignment: .leading) {
                    // 底层淡蓝荧光笔高光
                    RoundedRectangle(cornerRadius: 6)
                        .fill(AppColors.primaryBlue.opacity(0.18))
                        .frame(width: 140, height: 20)
                        .rotationEffect(.degrees(-1.5))
                        .offset(x: 2, y: 10)

                    Text("record")
                        .font(.system(size: 34, weight: .medium, design: .serif))
                        .italic()
                        .foregroundColor(AppColors.primaryBlue)
                }

                Text("点什么吗？")
                    .font(.system(size: 26, weight: .bold, design: .serif))
                    .foregroundColor(AppColors.textPrimary)

                VStack(alignment: .leading, spacing: 3) {
                    Text("用记录，")
                    Text("把生活过成喜欢的样子。")
                }
                .font(.system(size: 12.5, weight: .regular))
                .foregroundColor(AppColors.textSecondary)
                .padding(.top, 6)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            // 右侧生动插画（蓝发角色 + 电脑 + 书本 + 咖啡 + ready to record气泡）
            Image("HeroIllustration")
                .resizable()
                .scaledToFit()
                .frame(width: 175, height: 175)
                .offset(y: 6)
        }
    }

    // MARK: - 3. 胶囊模式切换栏

    private var segmentSwitcherSection: some View {
        HStack(spacing: 0) {
            if viewModel.authMode == .register {
                // 注册模式下的专属标题
                Text("新用户注册")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(AppColors.textPrimary)

                Spacer()

                Button(action: {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                        viewModel.authMode = .passwordLogin
                    }
                }) {
                    HStack(spacing: 2) {
                        Text("返回登录")
                            .font(.system(size: 14, weight: .medium))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    .foregroundColor(AppColors.textSecondary)
                }
                .buttonStyle(.plain)
            } else {
                // 登录模式下的胶囊切换器
                HStack(spacing: 4) {
                    segmentButton(title: "密码登录", mode: .passwordLogin)
                    segmentButton(title: "验证码登录", mode: .codeLogin)
                }
                .padding(3)
                .background(AppColors.segmentBarBg)
                .clipShape(Capsule())

                Spacer()

                // 右侧 "注册 >"
                Button(action: {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                        viewModel.authMode = .register
                    }
                }) {
                    HStack(spacing: 2) {
                        Text("注册")
                            .font(.system(size: 14, weight: .medium))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    .foregroundColor(AppColors.textSecondary)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func segmentButton(title: String, mode: AuthMode) -> some View {
        let isSelected = viewModel.authMode == mode
        return Button(action: {
            withAnimation(.spring(response: 0.28, dampingFraction: 0.8)) {
                viewModel.authMode = mode
            }
        }) {
            Text(title)
                .font(.system(size: 14, weight: isSelected ? .medium : .regular))
                .foregroundColor(isSelected ? AppColors.segmentSelectedText : AppColors.textSecondary)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
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
            // 邮箱输入框
            AppTextField(
                iconName: "envelope",
                placeholder: "请输入邮箱地址",
                text: $viewModel.email,
                keyboardType: .emailAddress
            )
            .focused($focusedField, equals: .email)

            // 根据当前模式展示不同字段
            switch viewModel.authMode {
            case .passwordLogin:
                AppTextField(
                    iconName: "lock",
                    placeholder: "请输入密码",
                    text: $viewModel.password,
                    isSecure: true
                )
                .focused($focusedField, equals: .password)

            case .codeLogin:
                AppTextField(
                    iconName: "shield.checkered",
                    placeholder: "请输入验证码",
                    text: $viewModel.verificationCode,
                    keyboardType: .numberPad,
                    trailingAction: AnyView(verificationCodeButton)
                )
                .focused($focusedField, equals: .code)

            case .register:
                AppTextField(
                    iconName: "shield.checkered",
                    placeholder: "请输入邮箱验证码",
                    text: $viewModel.verificationCode,
                    keyboardType: .numberPad,
                    trailingAction: AnyView(verificationCodeButton)
                )
                .focused($focusedField, equals: .code)

                AppTextField(
                    iconName: "lock",
                    placeholder: "设置密码（不少于 6 位）",
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
            Text(viewModel.countdownSeconds > 0 ? "\(viewModel.countdownSeconds)s 后重发" : "获取验证码")
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(viewModel.countdownSeconds > 0 ? AppColors.placeholderText : AppColors.primaryBlue)
                .padding(.horizontal, 6)
        }
        .disabled(viewModel.countdownSeconds > 0)
        .buttonStyle(.plain)
    }

    // MARK: - 5. 辅助选项（记住我 / 忘记密码）

    private var auxiliaryOptionsSection: some View {
        HStack {
            // 记住我复选框
            Button(action: {
                viewModel.rememberMe.toggle()
            }) {
                HStack(spacing: 8) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 4)
                            .stroke(viewModel.rememberMe ? AppColors.primaryBlue : AppColors.placeholderText, lineWidth: 1.4)
                            .frame(width: 18, height: 18)

                        if viewModel.rememberMe {
                            RoundedRectangle(cornerRadius: 3)
                                .fill(AppColors.primaryBlue)
                                .frame(width: 14, height: 14)

                            Image(systemName: "checkmark")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(.white)
                        }
                    }

                    Text("记住我")
                        .font(.system(size: 13))
                        .foregroundColor(AppColors.textSecondary)
                }
            }
            .buttonStyle(.plain)

            Spacer()

            // 忘记密码？
            Button(action: {
                viewModel.showForgotPasswordSheet = true
            }) {
                Text("忘记密码？")
                    .font(.system(size: 13))
                    .foregroundColor(AppColors.textSecondary)
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - 6. 主操作按钮

    private var submitButtonSection: some View {
        AppButton(
            title: viewModel.authMode == .register ? "注册并登录" : "登录",
            iconName: "arrow.right",
            isLoading: viewModel.isLoading,
            isEnabled: true
        ) {
            focusedField = nil
            viewModel.submit()
        }
    }

    // MARK: - 7. 底部同步标语与生动装饰

    private var bottomBrandingSection: some View {
        VStack(spacing: 14) {
            // "—— 一个账号，同步桌面与 iPhone 记录 ——"
            HStack(spacing: 8) {
                Rectangle()
                    .fill(AppColors.inputBorder)
                    .frame(height: 0.8)
                    .frame(maxWidth: 40)

                Text("一个账号，同步桌面与 iPhone 记录")
                    .font(.system(size: 12))
                    .foregroundColor(AppColors.textTertiary)

                Rectangle()
                    .fill(AppColors.inputBorder)
                    .frame(height: 0.8)
                    .frame(maxWidth: 40)
            }

            // 底部两侧的精巧艺术字与彩色圆角方块
            HStack(alignment: .bottom) {
                // 左下角手写体 Record a Better You.
                VStack(alignment: .leading, spacing: 2) {
                    Text("Record")
                        .font(.system(size: 14, weight: .medium, design: .serif))
                        .italic()
                    Text("a Better You.")
                        .font(.system(size: 14, weight: .medium, design: .serif))
                        .italic()
                }
                .foregroundColor(AppColors.primaryBlue.opacity(0.85))
                .rotationEffect(.degrees(-6))
                .padding(.leading, 8)

                Spacer()

                // 右下角三块彩色倾斜圆角装饰图形
                Image("BottomShapes")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 85, height: 75)
                    .offset(x: 10, y: 10)
            }
            .frame(height: 80)
        }
    }

    // MARK: - 8. 隐私政策与协议

    private var legalFooterSection: some View {
        HStack(spacing: 12) {
            Button("隐私政策") {
                viewModel.agreementType = .privacy
                viewModel.showAgreementSheet = true
            }
            .foregroundColor(AppColors.textTertiary)

            Text("|")
                .foregroundColor(AppColors.inputBorder)

            Button("用户协议") {
                viewModel.agreementType = .userTerms
                viewModel.showAgreementSheet = true
            }
            .foregroundColor(AppColors.textTertiary)
        }
        .font(.system(size: 12))
        .buttonStyle(.plain)
    }
}
