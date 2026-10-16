import XCTest

@testable import RecordAgent

final class AuthTests: XCTestCase {
    func testEmailValidation() {
        XCTAssertEqual(AuthValidator.validateEmail(""), .emptyEmail)
        XCTAssertEqual(AuthValidator.validateEmail("invalid-email"), .invalidEmail)
        XCTAssertEqual(AuthValidator.validateEmail("test@"), .invalidEmail)
        XCTAssertNil(AuthValidator.validateEmail("user@example.com"))
        XCTAssertNil(AuthValidator.validateEmail("butvan.agent@domain.org"))
    }

    func testPasswordValidation() {
        XCTAssertEqual(AuthValidator.validatePassword(""), .emptyPassword)
        XCTAssertEqual(AuthValidator.validatePassword("12345"), .passwordTooShort)
        XCTAssertNil(AuthValidator.validatePassword("123456"))
        XCTAssertNil(AuthValidator.validatePassword("strong_password_2026"))
    }

    func testCodeValidation() {
        XCTAssertEqual(AuthValidator.validateCode(""), .emptyVerificationCode)
        XCTAssertEqual(AuthValidator.validateCode("12"), .invalidVerificationCode)
        XCTAssertNil(AuthValidator.validateCode("1234"))
        XCTAssertNil(AuthValidator.validateCode("888888"))
    }

    func testMockAuthLoginSuccess() async throws {
        let authService = MockAuthService()
        let session = try await authService.loginWithPassword(
            email: "test@example.com", password: "password123")
        XCTAssertFalse(session.token.isEmpty)
        XCTAssertEqual(session.user.email, "test@example.com")
        XCTAssertNil(session.user.nickname)
        XCTAssertEqual(authService.getCurrentSession()?.user.email, "test@example.com")
    }

    func testGeneratedNicknameIsStableAndAccountScoped() {
        let first = AuthUser(id: "temporary-1", email: "User@Example.com")
        let sameAccount = AuthUser(id: "temporary-2", email: " user@example.com ")
        let other = AuthUser(id: "temporary-3", email: "other@example.com")
        XCTAssertEqual(first.generatedNicknameNumber, sameAccount.generatedNicknameNumber)
        XCTAssertNotEqual(first.generatedNicknameNumber, other.generatedNicknameNumber)
        XCTAssertEqual(String(first.generatedNicknameNumber).count, 13)
        XCTAssertTrue(
            first.profileDisplayName(override: nil, locale: Locale(identifier: "zh-Hans"))
                .hasPrefix("用户_"))
        XCTAssertTrue(
            first.profileDisplayName(override: nil, locale: Locale(identifier: "en")).hasPrefix(
                "User_"))
        XCTAssertEqual(first.profileDisplayName(override: "自定义", locale: .current), "自定义")
    }

    func testMockAuthLoginFailure() async {
        let authService = MockAuthService()
        do {
            _ = try await authService.loginWithPassword(
                email: "test@example.com", password: "error123")
            XCTFail("Should throw error for error123")
        } catch let error as AuthError {
            XCTAssertEqual(error.errorDescription, "账号或密码不正确，请重新输入")
        } catch {
            XCTFail("Unexpected error type: \(error)")
        }
    }
}
