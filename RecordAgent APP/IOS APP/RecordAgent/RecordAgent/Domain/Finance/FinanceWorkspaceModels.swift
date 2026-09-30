import Foundation

enum FinanceTransactionFilter: String, CaseIterable, Identifiable, Sendable {
    case all = ""
    case expense, income
    var id: String { rawValue }
}

struct FinanceMonthOverview: Decodable, Equatable, Sendable {
    let totalAssetsInCents: Int64
    let expenseInCents: Int64
    let incomeInCents: Int64
    let transactionCount: Int64
}

struct FinanceTransaction: Decodable, Identifiable, Equatable, Sendable {
    let id: String
    let accountId: String
    let type: String
    let category: String
    let note: String
    let amountInCents: Int64
    let date: String
    let time: String
    /// 列表查询时的账户余额；不代表这笔历史交易完成后的余额。
    let balanceInCents: Int64

    /// 提交时余额快照；旧流水或旧服务无快照时保持 nil。
    let balanceAfterInCents: Int64?

    private enum CodingKeys: String, CodingKey {
        case id, accountId, type, category, note, amountInCents, date, time, balanceInCents, balanceAfterInCents
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        accountId = try values.decode(String.self, forKey: .accountId)
        type = try values.decode(String.self, forKey: .type)
        // 未分类流水的 LEFT JOIN 分类列为空，不能令整页解码失败。
        category = try values.decodeIfPresent(String.self, forKey: .category) ?? ""
        note = try values.decodeIfPresent(String.self, forKey: .note) ?? ""
        amountInCents = try values.decode(Int64.self, forKey: .amountInCents)
        date = try values.decode(String.self, forKey: .date)
        time = try values.decode(String.self, forKey: .time)
        balanceInCents = try values.decode(Int64.self, forKey: .balanceInCents)
        balanceAfterInCents = try values.decodeIfPresent(Int64.self, forKey: .balanceAfterInCents)
    }
}

struct FinanceWorkspacePage<Item: Decodable & Sendable>: Decodable, Sendable {
    let items: [Item]
    let nextOffset: Int?
    let hasMore: Bool
}

protocol FinanceWorkspaceServiceProtocol: Sendable {
    func overview(month: String) async throws -> FinanceMonthOverview
    func accounts(offset: Int) async throws -> FinanceWorkspacePage<FinanceAccount>
    func transactions(
        month: String, type: FinanceTransactionFilter, accountID: String?,
        query: String, offset: Int
    ) async throws -> FinanceWorkspacePage<FinanceTransaction>
}

/// 输入始终转换为整数分，限制两位小数并拒绝溢出，避免浮点金额误差。
enum FinanceAmountInput {
    static func cents(_ input: String, allowsZero: Bool = false) -> Int64? {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: ".")
        guard !value.isEmpty else { return nil }
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count <= 2,
            parts.allSatisfy({ $0.allSatisfy({ $0.isASCII && $0.isNumber }) }),
            !parts[0].isEmpty || (parts.count == 2 && !parts[1].isEmpty),
            parts.count == 1 || parts[1].count <= 2,
            let whole = Int64(parts[0].isEmpty ? "0" : String(parts[0]))
        else { return nil }
        let fraction =
            parts.count == 2
            ? String(parts[1]).padding(toLength: 2, withPad: "0", startingAt: 0) : "00"
        guard let decimal = Int64(fraction) else { return nil }
        let (scaled, overflow) = whole.multipliedReportingOverflow(by: 100)
        let (total, sumOverflow) = scaled.addingReportingOverflow(decimal)
        guard !overflow, !sumOverflow, allowsZero || total > 0 else { return nil }
        return total
    }
}
