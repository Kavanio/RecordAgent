import CryptoKit
import Foundation

protocol ProfileAppearanceServiceProtocol: Sendable {
    func load(account: String) async throws -> ProfileAppearance
    func save(_ appearance: ProfileAppearance, account: String) async throws
}

enum ProfileAppearanceError: LocalizedError {
    case invalidImage
    case imageTooLarge

    var errorDescription: String? {
        switch self {
        case .invalidImage: String(localized: "profile.edit.invalidImage")
        case .imageTooLarge: String(localized: "profile.edit.imageTooLarge")
        }
    }
}

/// 当前无账户资料 API，设备副本按账户隔离存放；切换账户时不会读取另一账户的图片。
actor LocalProfileAppearanceService: ProfileAppearanceServiceProtocol {
    private let root: URL

    init(root: URL? = nil) {
        self.root =
            root
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ProfileAppearance", isDirectory: true)
    }

    func load(account: String) async throws -> ProfileAppearance {
        let directory = directory(for: account)
        let nameURL = directory.appendingPathComponent("nickname.txt")
        let avatarURL = directory.appendingPathComponent("avatar.jpg")
        let coverURL = directory.appendingPathComponent("cover.jpg")
        let name =
            FileManager.default.fileExists(atPath: nameURL.path)
            ? try String(contentsOf: nameURL, encoding: .utf8) : nil
        let avatar =
            FileManager.default.fileExists(atPath: avatarURL.path)
            ? try Data(contentsOf: avatarURL) : nil
        let cover =
            FileManager.default.fileExists(atPath: coverURL.path)
            ? try Data(contentsOf: coverURL) : nil
        return ProfileAppearance(
            nickname: name?.isEmpty == false ? name : nil,
            avatarData: avatar,
            coverData: cover
        )
    }

    func save(_ appearance: ProfileAppearance, account: String) async throws {
        try Task.checkCancellation()
        for data in [appearance.avatarData, appearance.coverData] {
            if let data {
                guard data.count <= 2_000_000 else { throw ProfileAppearanceError.imageTooLarge }
                guard data.starts(with: [0xFF, 0xD8]) else {
                    throw ProfileAppearanceError.invalidImage
                }
            }
        }
        let directory = directory(for: account)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try write(
            appearance.nickname?.data(using: .utf8),
            to: directory.appendingPathComponent("nickname.txt"))
        try write(appearance.avatarData, to: directory.appendingPathComponent("avatar.jpg"))
        try write(appearance.coverData, to: directory.appendingPathComponent("cover.jpg"))
    }

    private func directory(for account: String) -> URL {
        let normalized = account.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let digest = SHA256.hash(data: Data(normalized.utf8))
        let key = digest.map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent(key, isDirectory: true)
    }

    private func write(_ data: Data?, to url: URL) throws {
        if let data {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
        } else if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }
}
