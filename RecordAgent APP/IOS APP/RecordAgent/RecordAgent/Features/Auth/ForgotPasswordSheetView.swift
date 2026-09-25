import SwiftUI

/// 忘记密码/找回密码弹窗视图
public struct ForgotPasswordSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var email: String = ""
    @State private var code: String = ""
    @State private var newPassword: String = ""
    @State private var isSubmitting: Bool = false
    @State private var isSuccess: Bool = false
    @State private var countdown: Int = 0

    public var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("重置密码")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(AppColors.textPrimary)
                    .padding(.top, 16)

                Text("输入您的注册邮箱，我们将向您发送重置验证码。")
                    .font(.system(size: 14))
                    .foregroundColor(AppColors.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                VStack(spacing: 14) {
                    AppTextField(
                        iconName: "envelope",
                        placeholder: "请输入注册邮箱",
                        text: $email,
                        keyboardType: .emailAddress
                    )

                    AppTextField(
                        iconName: "shield.checkered",
                        placeholder: "请输入验证码",
                        text: $code,
                        keyboardType: .numberPad,
                        trailingAction: AnyView(
                            Button(countdown > 0 ? "\(countdown)s" : "获取验证码") {
                                startCountdown()
                            }
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(countdown > 0 ? AppColors.placeholderText : AppColors.primaryBlue)
                            .disabled(countdown > 0 || email.isEmpty)
                        )
                    )

                    AppTextField(
                        iconName: "lock",
                        placeholder: "设置新密码（至少 6 位）",
                        text: $newPassword,
                        isSecure: true
                    )
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)

                if isSuccess {
                    Text("密码重置成功，请使用新密码登录")
                        .font(.system(size: 14))
                        .foregroundColor(.green)
                }

                AppButton(
                    title: isSuccess ? "返回登录" : "确认重置",
                    iconName: isSuccess ? "checkmark" : "arrow.right",
                    isLoading: isSubmitting
                ) {
                    if isSuccess {
                        dismiss()
                    } else {
                        submitReset()
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)

                Spacer()
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .foregroundColor(AppColors.primaryBlue)
                }
            }
        }
    }

    private func startCountdown() {
        countdown = 60
        Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { timer in
            if countdown > 1 {
                countdown -= 1
            } else {
                countdown = 0
                timer.invalidate()
            }
        }
    }

    private func submitReset() {
        isSubmitting = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            isSubmitting = false
            isSuccess = true
        }
    }
}
