import Foundation

/// 财务账户与流水使用服务端权威余额，金额只以整数分传输。
struct FinanceAccount: Decodable, Identifiable, Sendable, Equatable {
    let id: String
    let name: String
    let balanceInCents: Int64
    let version: Int64
}

struct FinanceTransactionDraft: Sendable {
    /// 同一草稿重试使用相同请求 ID；编辑后建立新草稿以生成新 ID。
    let requestId = UUID()
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

/// 财务 API 对接 `agent-backend` 的 `/agent/finance`。
actor APIFinanceService: FinanceServiceProtocol {
    private let client: APIClient
    private var submission = FinanceSubmissionState()

    init(client: APIClient) { self.client = client }

    func accounts() async throws -> [FinanceAccount] {
        let overview: BackendFinanceOverviewResponse = try await client.sendResult("/agent/finance/overview")
        return overview.accounts.map { $0.toDomain() }
    }

    func createAccount(name: String, openingBalanceInCents: Int64) async throws -> FinanceAccount {
        let body = try APIClient.encoder.encode(
            CreateAccountRequest(
                name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                accountType: "SAVINGS",
                currency: "CNY",
                initialBalance: Double(openingBalanceInCents) / 100.0,
                interestEnabled: false,
                annualRatePercent: nil
            )
        )
        let created: BackendFinanceAccountResponse = try await client.sendResult(
            "/agent/finance/accounts", method: "POST", body: body)
        return created.toDomain()
    }

    func createTransaction(_ draft: FinanceTransactionDraft) async throws {
        let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: draft.date)
        let date = String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
        let time = String(format: "%02d:%02d:00", parts.hour ?? 0, parts.minute ?? 0)
        let encoder = APIClient.encoder
        encoder.outputFormatting = [.sortedKeys]
        let body = try encoder.encode(
            CreateTransactionRequest(
                accountId: draft.accountId,
                transactionType: draft.type.uppercased(),
                category: draft.category.trimmingCharacters(in: .whitespacesAndNewlines),
                note: draft.note,
                amount: Double(draft.amountInCents) / 100.0,
                date: date,
                time: time,
                categoryColor: nil
            )
        )
        let key = submission.key(for: body, preferred: draft.requestId)
        let _: BackendFinanceTransactionResponse = try await client.sendResult(
            "/agent/finance/transactions", method: "POST", body: body, idempotencyKey: key)
        submission.completed(key)
    }
}

struct BackendFinanceAccountResponse: Decodable {
    let id: String
    let name: String
    let balance: Double
    let version: Int

    func toDomain() -> FinanceAccount {
        FinanceAccount(
            id: id,
            name: name,
            balanceInCents: Int64((balance * 100).rounded()),
            version: Int64(version)
        )
    }
}

struct BackendFinanceTransactionResponse: Decodable {
    let id: String
    let accountId: String
    let date: String
    let time: String?
    let transactionType: String
    let category: String?
    let note: String?
    let amount: Double

    func toDomain() -> FinanceTransaction {
        FinanceTransaction(
            id: id,
            accountId: accountId,
            type: transactionType.lowercased(),
            category: category ?? "",
            note: note ?? "",
            amountInCents: Int64((amount * 100).rounded()),
            date: date,
            time: time ?? "00:00",
            balanceInCents: 0,
            balanceAfterInCents: nil
        )
    }
}

struct BackendFinanceOverviewResponse: Decodable {
    let totalAssets: Double
    let monthIncome: Double
    let monthExpense: Double
    let accounts: [BackendFinanceAccountResponse]
    let transactions: [BackendFinanceTransactionResponse]
}

private struct CreateAccountRequest: Encodable {
    let name: String
    let accountType: String
    let currency: String
    let initialBalance: Double
    let interestEnabled: Bool
    let annualRatePercent: Double?
}

private struct CreateTransactionRequest: Encodable {
    let accountId: String
    let transactionType: String
    let category: String
    let note: String
    let amount: Double
    let date: String
    let time: String
    let categoryColor: String?
}

/// 保存尚未确认成功的单个请求 ID；同内容重试复用键，成功后新的提交生成新键。
/// 状态仅在当前进程内保留，不承诺重启或离线写入恢复。
struct FinanceSubmissionState {
    private var pending: (body: Data, key: UUID)?

    mutating func key(for body: Data, preferred: UUID) -> UUID {
        if let pending, pending.body == body { return pending.key }
        pending = (body, preferred)
        return preferred
    }

    mutating func completed(_ key: UUID) {
        if pending?.key == key { pending = nil }
    }
}
