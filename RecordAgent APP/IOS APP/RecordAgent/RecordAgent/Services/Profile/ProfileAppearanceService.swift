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

/// 个人资料以服务端为权威；首次迁移时读取旧设备副本供用户重新保存。
actor APIProfileAppearanceService: ProfileAppearanceServiceProtocol {
    private let client: APIClient
    private let legacy = LocalProfileAppearanceService()
    private var cachedAccount: String?
    private var cached: AppearanceResponse?
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
        let data = try await client.send("/api/v1/me/appearance")
        let response = try APIClient.decoder.decode(AppearanceResponse.self, from: data)
        let avatar: Data?
        if let id = response.avatarFileId { avatar = try await fileData(id) } else { avatar = nil }
        let cover: Data?
        if let id = response.coverFileId { cover = try await fileData(id) } else { cover = nil }
        let previous: ProfileAppearance?
        if response.version == 0 { previous = try? await legacy.load(account: account) }
        else { previous = nil }
        cached = response
        cachedAvatar = avatar ?? previous?.avatarData
        cachedCover = cover ?? previous?.coverData
        return ProfileAppearance(nickname: previous?.nickname ?? response.nickname,
            avatarData: cachedAvatar, coverData: cachedCover)
    }

    func save(_ appearance: ProfileAppearance, account: String) async throws {
        if cachedAccount != account || cached == nil { _ = try await load(account: account) }
        guard let current = cached else { throw APIClientError.invalidResponse }
        let avatarID = try await fileID(for: appearance.avatarData,
            previousData: cachedAvatar, previousID: current.avatarFileId)
        let coverID = try await fileID(for: appearance.coverData,
            previousData: cachedCover, previousID: current.coverFileId)
        let body = try APIClient.encoder.encode(UpdateAppearanceRequest(
            nickname: appearance.nickname, avatarFileId: avatarID,
            coverFileId: coverID, expectedVersion: current.version))
        let result = try await client.send("/api/v1/me/appearance", method: "PATCH", body: body)
        cached = try APIClient.decoder.decode(AppearanceResponse.self, from: result)
        cachedAvatar = appearance.avatarData
        cachedCover = appearance.coverData
    }

    private func fileData(_ id: String) async throws -> Data {
        try await client.send("/api/v1/files/\(id)/content")
    }

    private func fileID(for data: Data?, previousData: Data?, previousID: String?) async throws -> String? {
        guard let data else { return nil }
        if data == previousData, let previousID { return previousID }
        guard data.count <= 2_000_000, data.starts(with: [0xFF, 0xD8]) else {
            throw ProfileAppearanceError.invalidImage
        }
        let body = try APIClient.encoder.encode(UploadImageRequest(jpegBase64: data.base64EncodedString()))
        let response = try await client.send("/api/v1/files", method: "POST", body: body)
        return try APIClient.decoder.decode(UploadedImageResponse.self, from: response).id
    }
}

private struct AppearanceResponse: Decodable {
    let nickname: String
    let avatarFileId: String?
    let coverFileId: String?
    let version: Int64
}
private struct UpdateAppearanceRequest: Encodable {
    let nickname: String?
    let avatarFileId: String?
    let coverFileId: String?
    let expectedVersion: Int64
}
private struct UploadImageRequest: Encodable { let jpegBase64: String }
private struct UploadedImageResponse: Decodable { let id: String }
