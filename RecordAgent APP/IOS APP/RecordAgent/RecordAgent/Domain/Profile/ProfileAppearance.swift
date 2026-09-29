import Foundation

/// 昵称和个人图片的展示模型；服务端资料接口是权威事实。
struct ProfileAppearance: Equatable, Sendable {
    var nickname: String?
    var avatarData: Data?
    var coverData: Data?

    static let empty = ProfileAppearance()
}
