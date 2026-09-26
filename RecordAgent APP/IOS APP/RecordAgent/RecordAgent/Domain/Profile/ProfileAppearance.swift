import Foundation

/// 个人资料的设备端展示副本；服务端账户资料接通后由服务实现负责同步。
struct ProfileAppearance: Equatable, Sendable {
    var nickname: String?
    var avatarData: Data?
    var coverData: Data?

    static let empty = ProfileAppearance()
}
