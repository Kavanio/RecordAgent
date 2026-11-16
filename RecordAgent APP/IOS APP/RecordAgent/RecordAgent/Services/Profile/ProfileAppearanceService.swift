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

/// 旧设备副本仅用于首次迁移，账户键防止读取其他账户的图片。
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

/// 个人资料外观对接 `agent-backend` 的 `/agent/account/appearance`、`/avatar` 与 `/cover`。
actor APIProfileAppearanceService: ProfileAppearanceServiceProtocol {
    private let client: APIClient
    private let legacy = LocalProfileAppearanceService()
    private var cachedAccount: String?
    private var cached: BackendAppearanceResponse?
    private var cachedAvatar: Data?
    private var cachedCover: Data?

    init(client: APIClient) { self.client = client }

    func load(account: String) async throws -> ProfileAppearance {
        if cachedAccount != account {
            cachedAccount = account
            cached = nil
            cachedAvatar = nil
            cachedCover = nil
        }
        let response: BackendAppearanceResponse = try await client.sendResult("/agent/account/appearance")
        let avatar: Data?
        if let version = response.avatarVersion, !version.isEmpty {
            if cached?.avatarVersion == version, let cachedAvatar {
                avatar = cachedAvatar
            } else {
                avatar = try? await client.send("/agent/account/avatar/content")
            }
        } else {
            avatar = nil
        }
        let cover: Data?
        if let version = response.coverVersion, !version.isEmpty {
            if cached?.coverVersion == version, let cachedCover {
                cover = cachedCover
            } else {
                cover = try? await client.send("/agent/account/cover/content")
            }
        } else {
            cover = nil
        }
        let hasRemote = response.version > 0 || response.nickname != nil
            || response.avatarVersion != nil || response.coverVersion != nil
        let previous = hasRemote ? nil : (try? await legacy.load(account: account))
        cached = response
        cachedAvatar = avatar ?? previous?.avatarData
        cachedCover = cover ?? previous?.coverData
        return ProfileAppearance(
            nickname: response.nickname ?? previous?.nickname,
            avatarData: cachedAvatar,
            coverData: cachedCover
        )
    }

    func save(_ appearance: ProfileAppearance, account: String) async throws {
        if cachedAccount != account || cached == nil { _ = try await load(account: account) }
        guard var current = cached else { throw APIClientError.invalidResponse }

        let clearAvatar = appearance.avatarData == nil && current.avatarVersion != nil
        let clearCover = appearance.coverData == nil && current.coverVersion != nil
        let body = try APIClient.encoder.encode(
            UpdateAppearanceRequest(
                nickname: appearance.nickname,
                clearAvatar: clearAvatar,
                clearCover: clearCover,
                expectedVersion: current.version
            )
        )
        current = try await client.sendResult("/agent/account/appearance", method: "PATCH", body: body)

        if let avatar = appearance.avatarData, avatar != cachedAvatar || current.avatarVersion == nil {
            try validateJPEG(avatar)
            current = try await client.sendMultipartResult(
                "/agent/account/avatar",
                filename: "avatar.jpg",
                mimeType: "image/jpeg",
                fileData: avatar
            )
        }
        if let cover = appearance.coverData, cover != cachedCover || current.coverVersion == nil {
            try validateJPEG(cover)
            current = try await client.sendMultipartResult(
                "/agent/account/cover",
                filename: "cover.jpg",
                mimeType: "image/jpeg",
                fileData: cover
            )
        }

        cached = current
        cachedAvatar = appearance.avatarData
        cachedCover = appearance.coverData
    }

    private func validateJPEG(_ data: Data) throws {
        guard data.count <= 2_000_000, data.starts(with: [0xFF, 0xD8]) else {
            throw ProfileAppearanceError.invalidImage
        }
    }
}

private struct BackendAppearanceResponse: Decodable {
    let nickname: String?
    let avatarVersion: String?
    let coverVersion: String?
    let version: Int64
}

private struct UpdateAppearanceRequest: Encodable {
    let nickname: String?
    let clearAvatar: Bool
    let clearCover: Bool
    let expectedVersion: Int64
}
