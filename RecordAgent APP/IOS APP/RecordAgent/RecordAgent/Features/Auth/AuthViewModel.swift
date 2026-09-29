import SwiftUI
import Combine

@MainActor
public final class AuthViewModel: ObservableObject {
    // MARK: - 模式
    @Published public var authMode: AuthMode = .passwordLogin

    // MARK: - 表单输入
    @Published public var email: String = ""
    @Published public var password: String = ""
    @Published public var verificationCode: String = ""
    @Published public var rememberMe: Bool = true

    // MARK: - UI 状态
    @Published public var isLoading: Bool = false
    @Published public var errorMessage: String? = nil
    @Published public var showErrorAlert: Bool = false
    @Published public var showAgreementSheet: Bool = false
    @Published public var agreementType: AgreementType = .privacy
    @Published public var showForgotPasswordSheet: Bool = false
    @Published public var isAuthenticated: Bool = false
    @Published public var isRestoringSession: Bool = true

    // MARK: - 验证码倒计时
    @Published public var countdownSeconds: Int = 0
    private var countdownTimer: AnyCancellable?

    // MARK: - 依赖
    private let authService: AuthServiceProtocol
    private let keychain: KeychainService

    public init(authService: AuthServiceProtocol, keychain: KeychainService = .shared) {
        self.authService = authService
        self.keychain = keychain

        // 尝试加载上次记住的邮箱
        if let savedEmail = keychain.getSavedEmail() {
            self.email = savedEmail
            self.rememberMe = true
        } else {
            self.rememberMe = false
        }

        Task {
            do {
                isAuthenticated = try await authService.restoreSession() != nil
            } catch {
                isAuthenticated = false
            }
            isRestoringSession = false
        }
    }

    // MARK: - 表单提交主入口

    public func submit() {
        guard !isLoading else { return }
        errorMessage = nil

        switch authMode {
        case .passwordLogin:
            executePasswordLogin()
        case .codeLogin:
            executeCodeLogin()
        case .register:
            executeRegister()
        }
    }

    // MARK: - 具体动作实现

    private func executePasswordLogin() {
        if let error = AuthValidator.validateEmail(email) {
            presentError(error.localizedDescription)
            return
        }
        if let error = AuthValidator.validatePassword(password) {
            presentError(error.localizedDescription)
            return
        }

        isLoading = true
        Task {
            do {
                _ = try await authService.loginWithPassword(email: email, password: password)
                handleLoginSuccess()
            } catch {
                presentError(error.localizedDescription)
            }
            isLoading = false
        }
    }

    private func executeCodeLogin() {
        if let error = AuthValidator.validateEmail(email) {
            presentError(error.localizedDescription)
            return
        }
        if let error = AuthValidator.validateCode(verificationCode) {
            presentError(error.localizedDescription)
            return
        }

        isLoading = true
        Task {
            do {
                _ = try await authService.loginWithCode(email: email, code: verificationCode)
                handleLoginSuccess()
            } catch {
                presentError(error.localizedDescription)
            }
            isLoading = false
        }
    }

    private func executeRegister() {
        if let error = AuthValidator.validateEmail(email) {
            presentError(error.localizedDescription)
            return
        }
        if let error = AuthValidator.validateCode(verificationCode) {
            presentError(error.localizedDescription)
            return
        }
        if let error = AuthValidator.validatePassword(password) {
            presentError(error.localizedDescription)
            return
        }

        isLoading = true
        Task {
            do {
                _ = try await authService.register(email: email, code: verificationCode, password: password)
                handleLoginSuccess()
            } catch {
                presentError(error.localizedDescription)
            }
            isLoading = false
        }
    }

    public func sendVerificationCode() {
        if let error = AuthValidator.validateEmail(email) {
            presentError(error.localizedDescription)
            return
        }
        guard countdownSeconds <= 0 else { return }

        Task {
            do {
                try await authService.sendVerificationCode(
                    email: email, purpose: authMode == .register ? .register : .login)
                startCountdown()
            } catch {
                presentError(error.localizedDescription)
            }
        }
    }

    private func startCountdown() {
        countdownSeconds = 60
        countdownTimer?.cancel()
        countdownTimer = Timer.publish(every: 1.0, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] _ in
                guard let self = self else { return }
                if self.countdownSeconds > 1 {
                    self.countdownSeconds -= 1
                } else {
                    self.countdownSeconds = 0
                    self.countdownTimer?.cancel()
                    self.countdownTimer = nil
                }
            }
    }

    private func handleLoginSuccess() {
        if rememberMe {
            _ = keychain.saveEmail(email)
        } else {
            keychain.clearSavedEmail()
        }
        isAuthenticated = true
    }

    private func presentError(_ message: String) {
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.error)
        self.errorMessage = message
        self.showErrorAlert = true
    }
}

public enum AgreementType: Identifiable, Sendable {
    case privacy
    case userTerms

    public var id: String {
        switch self {
        case .privacy: return "privacy"
        case .userTerms: return "userTerms"
        }
    }

    public var title: LocalizedStringKey {
        switch self {
        case .privacy: return "auth.agreement.privacy_title"
        case .userTerms: return "auth.agreement.terms_title"
        }
    }
}
