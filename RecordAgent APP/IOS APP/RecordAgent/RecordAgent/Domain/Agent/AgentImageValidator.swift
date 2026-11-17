import Foundation

enum AgentImageValidationError: LocalizedError, Equatable, Sendable {
    case empty
    case unsupportedFormat
    case singleTooLarge
    case tooManyImages
    case totalTooLarge

    var errorDescription: String? {
        switch self {
        case .empty:
            return String(localized: "agent.image.error.empty")
        case .unsupportedFormat:
            return String(localized: "agent.image.error.unsupported_format")
        case .singleTooLarge:
            return String(localized: "agent.image.error.single_too_large")
        case .tooManyImages:
            return String(localized: "agent.image.error.too_many")
        case .totalTooLarge:
            return String(localized: "agent.image.error.total_too_large")
        }
    }
}

/// 严格对齐服务端 `AgentImages` 契约：按文件头判断图片格式并限制单张/每轮大小与数量。
enum AgentImageValidator {
    static let maxCount = 4
    static let maxSingleBytes = 10 * 1024 * 1024
    static let maxTotalBytes = 12 * 1024 * 1024

    static func detectMediaType(in data: Data) -> String? {
        guard data.count >= 12 else { return nil }
        let bytes = [UInt8](data.prefix(12))

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if bytes.starts(with: [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) {
            return "image/png"
        }
        // JPEG: FF D8 FF
        if bytes.starts(with: [0xFF, 0xD8, 0xFF]) {
            return "image/jpeg"
        }
        // GIF: GIF87a / GIF89a
        if bytes[0] == 0x47, bytes[1] == 0x49, bytes[2] == 0x46, bytes[3] == 0x38,
           (bytes[4] == 0x37 || bytes[4] == 0x39), bytes[5] == 0x61 {
            return "image/gif"
        }
        // WebP: RIFF....WEBP
        if bytes[0] == 0x52, bytes[1] == 0x49, bytes[2] == 0x46, bytes[3] == 0x46,
           bytes[8] == 0x57, bytes[9] == 0x45, bytes[10] == 0x42, bytes[11] == 0x50 {
            return "image/webp"
        }
        return nil
    }

    static func makeAttachment(
        from data: Data,
        existing: [AgentChatImageAttachment]
    ) throws -> AgentChatImageAttachment {
        guard !data.isEmpty else {
            throw AgentImageValidationError.empty
        }
        guard existing.count < maxCount else {
            throw AgentImageValidationError.tooManyImages
        }
        guard data.count <= maxSingleBytes else {
            throw AgentImageValidationError.singleTooLarge
        }
        guard let mediaType = detectMediaType(in: data) else {
            throw AgentImageValidationError.unsupportedFormat
        }
        let existingBytes = existing.reduce(0) { $0 + $1.byteCount }
        guard existingBytes + data.count <= maxTotalBytes else {
            throw AgentImageValidationError.totalTooLarge
        }
        return AgentChatImageAttachment(
            id: UUID(),
            mediaType: mediaType,
            data: data,
            dataBase64: data.base64EncodedString()
        )
    }
}
