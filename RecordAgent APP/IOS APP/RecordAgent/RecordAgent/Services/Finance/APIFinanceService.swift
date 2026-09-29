import Foundation

/// 财务账户与流水使用服务端权威余额，金额只以整数分传输。
struct FinanceAccount: Decodable, Identifiable, Sendable, Equatable {
    let id: String
    let name: String
    let balanceInCents: Int64
    let version: Int64
}

struct FinanceTransactionDraft: Sendable {
    let accountId: String
    let type: String
    let category: String
    let note: String
    let amountInCents: Int64
    let date: Date
}

protocol FinanceServiceProtocol: Sendable {
    func accounts() async throws -> [FinanceAccount]
    func createAccount(name: String, openingBalanceInCents: Int64) async throws -> FinanceAccount
    func createTransaction(_ draft: FinanceTransactionDraft) async throws
}

/// 财务 API 的请求、响应和自然日映射集中在服务层。
actor APIFinanceService: FinanceServiceProtocol {
    private let client: APIClient

    init(client: APIClient) { self.client = client }

    func accounts() async throws -> [FinanceAccount] {
        let data = try await client.send("/api/v1/finance/accounts")
        return try APIClient.decoder.decode([FinanceAccount].self, from: data)
    }

    func createAccount(name: String, openingBalanceInCents: Int64) async throws -> FinanceAccount {
        let body = try APIClient.encoder.encode(CreateAccountRequest(
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            openingBalanceInCents: openingBalanceInCents))
        let data = try await client.send("/api/v1/finance/accounts", method: "POST", body: body)
        return try APIClient.decoder.decode(FinanceAccount.self, from: data)
    }

    func createTransaction(_ draft: FinanceTransactionDraft) async throws {
        let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: draft.date)
        let date = String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
        let time = String(format: "%02d:%02d:00", parts.hour ?? 0, parts.minute ?? 0)
        let body = try APIClient.encoder.encode(CreateTransactionRequest(
            accountId: draft.accountId, type: draft.type,
            category: draft.category.trimmingCharacters(in: .whitespacesAndNewlines),
            note: draft.note, amountInCents: draft.amountInCents,
            date: date, time: time, timeZone: TimeZone.current.identifier))
        _ = try await client.send("/api/v1/finance/transactions", method: "POST", body: body)
    }
}

private struct CreateAccountRequest: Encodable {
    let name: String
    let openingBalanceInCents: Int64
}

private struct CreateTransactionRequest: Encodable {
    let accountId: String
    let type: String
    let category: String
    let note: String
    let amountInCents: Int64
    let date: String
    let time: String
    let timeZone: String
}
