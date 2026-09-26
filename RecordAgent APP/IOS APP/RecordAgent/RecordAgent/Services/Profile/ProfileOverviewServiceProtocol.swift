import Foundation

/// 账户范围内的个人总览读取边界；真实服务应一次返回同一统计时点的全部数据。
protocol ProfileOverviewServiceProtocol: Sendable {
    func overview(for userID: String, month: Date) async throws -> ProfileOverview
}

enum ProfileOverviewError: Error { case unavailable }

/// Release 在统计接口接通之前呈现不可用状态。
struct UnavailableProfileOverviewService: ProfileOverviewServiceProtocol {
    func overview(for userID: String, month: Date) async throws -> ProfileOverview {
        throw ProfileOverviewError.unavailable
    }
}
