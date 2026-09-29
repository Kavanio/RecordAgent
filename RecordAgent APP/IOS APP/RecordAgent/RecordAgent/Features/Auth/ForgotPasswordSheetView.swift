import SwiftUI

/// 忘记密码/找回密码弹窗视图
public struct ForgotPasswordSheetView: View {
    let authService: AuthServiceProtocol
    @Environment(\.dismiss) private var dismiss
    @State private var email: String = ""
    @State private var code: String = ""
    @State private var newPassword: String = ""
    @State private var isSubmitting: Bool = false
    @State private var isSuccess: Bool = false
    @State private var countdown: Int = 0
    @State private var isSendingCode = false
    @State private var errorMessage: String?

    public var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("auth.forgot_password.title")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(AppColors.textPrimary)
                    .padding(.top, 16)

                Text("auth.forgot_password.subtitle")
                    .font(.system(size: 14))
                    .foregroundColor(AppColors.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                VStack(spacing: 14) {
                    AppTextField(
                        iconName: "envelope",
                        placeholderKey: "auth.email.placeholder",
                        text: $email,
                        keyboardType: .emailAddress
                    )

                    AppTextField(
                        iconName: "shield.checkered",
                        placeholderKey: "auth.code.placeholder",
                        text: $code,
                        keyboardType: .numberPad,
                        trailingAction: AnyView(
                            Button(action: {
                                startCountdown()
                            }) {
                                if countdown > 0 {
                                    Text("auth.code.resend \(countdown)")
                                        .font(.system(size: 13, weight: .medium))
                                        .foregroundColor(AppColors.placeholderText)
                                } else {
                                    Text("auth.code.get")
                                        .font(.system(size: 13, weight: .medium))
                                        .foregroundColor(AppColors.primaryBlue)
                                }
                            }
                            .disabled(countdown > 0 || email.isEmpty || isSendingCode)
                        )
                    )

                    AppTextField(
                        iconName: "lock",
                        placeholderKey: "auth.password.set_placeholder",
                        text: $newPassword,
                        isSecure: true
                    )
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)

                if isSuccess {
                    Text("auth.forgot_password.success")
                        .font(.system(size: 14))
                        .foregroundColor(.green)
                }

                AppButton(
                    isSuccess ? "auth.back_to_login" : "auth.forgot_password.confirm",
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
                    Button("common.cancel") { dismiss() }
                        .foregroundColor(AppColors.primaryBlue)
                }
            }
        }
        .alert(Text("common.alert.title"), isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("common.ok", role: .cancel) { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func startCountdown() {
        if let error = AuthValidator.validateEmail(email) {
            errorMessage = error.localizedDescription
            return
        }
        isSendingCode = true
        Task {
            defer { isSendingCode = false }
            do {
                try await authService.sendVerificationCode(email: email, purpose: .resetPassword)
                countdown = 60
                while countdown > 0 {
                    try await Task.sleep(for: .seconds(1))
                    countdown -= 1
                }
            } catch {
                if !(error is CancellationError) { errorMessage = error.localizedDescription }
            }
        }
    }

    private func submitReset() {
        if let error = AuthValidator.validateEmail(email)
            ?? AuthValidator.validateCode(code)
            ?? AuthValidator.validatePassword(newPassword) {
            errorMessage = error.localizedDescription
            return
        }
        isSubmitting = true
        Task {
            defer { isSubmitting = false }
            do {
                try await authService.resetPassword(
                    email: email, code: code, newPassword: newPassword)
                isSuccess = true
            } catch {
                if !(error is CancellationError) { errorMessage = error.localizedDescription }
            }
        }
    }
}
