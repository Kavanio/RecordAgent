import Foundation

/// 首页按日快照与待办写入的唯一服务边界，真实 API 接入时替换实现。
protocol HomeDataServiceProtocol: Sendable {
    func snapshot(for date: Date, locale: Locale) async throws -> HomeDaySnapshot
    func setTodoCompleted(id: String, completed: Bool, date: Date) async throws
}

enum HomeDataError: Error { case unavailable, missingTodo }

/// Release 不展示开发样例，真实业务服务就绪前明确返回不可用状态。
struct UnavailableHomeDataService: HomeDataServiceProtocol {
    func snapshot(for date: Date, locale: Locale) async throws -> HomeDaySnapshot {
        throw HomeDataError.unavailable
    }
    func setTodoCompleted(id: String, completed: Bool, date: Date) async throws {
        throw HomeDataError.unavailable
    }
}
