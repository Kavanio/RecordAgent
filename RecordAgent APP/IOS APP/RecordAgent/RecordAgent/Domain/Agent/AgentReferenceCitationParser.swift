import Foundation

/// 一条轮次中关联或引用的资料卡片展示模型。
struct AgentCitedReference: Identifiable, Equatable, Sendable {
    var id: String { recordId ?? "title::\(title)" }
    let recordId: String?
    let title: String
    let type: AgentRecordType
    let recordDate: String?
    let snippet: String?
    let citationNumber: Int?
}

/// 解析用户消息中的 `[资料：…]` 前缀与助手末尾 `引用资料` 段落，生成可点击的资料卡片。
enum AgentReferenceCitationParser {
    struct ParsedUserContent: Equatable, Sendable {
        let prompt: String
        let referenceTitles: [String]
    }

    struct ParsedAssistantCitation: Equatable, Sendable {
        let number: Int?
        let title: String
        let snippet: String?
    }

    static func parseUserPromptAndReferences(_ content: String) -> ParsedUserContent {
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("[资料："),
              let closingBracket = trimmed.firstIndex(of: "]") else {
            return ParsedUserContent(prompt: content, referenceTitles: [])
        }

        let start = trimmed.index(trimmed.startIndex, offsetBy: 4) // "[资料：".count
        let rawTitles = String(trimmed[start..<closingBracket])
        let afterBracket = trimmed.index(after: closingBracket)
        let remainingPrompt = String(trimmed[afterBracket...]).trimmingCharacters(in: .whitespacesAndNewlines)

        let titles = rawTitles
            .replacingOccurrences(of: "；资料：", with: "、")
            .split(separator: "、")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        guard !titles.isEmpty else {
            return ParsedUserContent(prompt: content, referenceTitles: [])
        }
        return ParsedUserContent(prompt: remainingPrompt, referenceTitles: titles)
    }

    static func extractAssistantCitations(from text: String) -> [ParsedAssistantCitation] {
        let lines = text.components(separatedBy: .newlines)
        guard let headerIndex = lines.lastIndex(where: { isCitationHeaderLine($0) }) else {
            return []
        }

        let candidateLines = lines[(headerIndex + 1)...]
        var citations: [ParsedAssistantCitation] = []
        for rawLine in candidateLines {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            if line.hasPrefix("#") { break }
            if let parsed = parseCitationItemLine(line) {
                citations.append(parsed)
            }
        }
        return citations
    }

    static func citedReferences(
        forAssistantIndex index: Int,
        in messages: [AgentChatMessage],
        finalText: String,
        cachedMetadata: [String: AgentRecordReferenceOption]
    ) -> [AgentCitedReference] {
        guard index >= 0, index < messages.count else { return [] }
        let assistant = messages[index]
        guard assistant.role == .assistant else { return [] }

        let matchingUser = findMatchingUserMessage(forAssistantAt: index, in: messages)
        let userTitles = matchingUser.map { parseUserPromptAndReferences($0.content).referenceTitles } ?? []
        let assistantCitations = extractAssistantCitations(from: finalText)

        var orderedTitles: [String] = []
        var byNormalizedTitle: [String: AgentCitedReference] = [:]

        func upsert(
            title: String,
            number: Int?,
            snippet: String?
        ) {
            let cleanTitle = cleanTitleText(title)
            guard !cleanTitle.isEmpty else { return }
            let key = cleanTitle.lowercased()
            let cached = cachedMetadata[key]
            let existing = byNormalizedTitle[key]
            if existing == nil {
                orderedTitles.append(key)
            }
            byNormalizedTitle[key] = AgentCitedReference(
                recordId: existing?.recordId ?? cached?.id,
                title: existing?.title ?? cached?.displayTitle ?? cleanTitle,
                type: existing?.type ?? cached?.type ?? .unknown,
                recordDate: existing?.recordDate ?? cached?.recordDate,
                snippet: nonEmpty(snippet) ?? existing?.snippet ?? nonEmpty(cached?.summary),
                citationNumber: number ?? existing?.citationNumber
            )
        }

        for (idx, title) in userTitles.enumerated() {
            upsert(title: title, number: idx + 1, snippet: nil)
        }
        for citation in assistantCitations {
            upsert(title: citation.title, number: citation.number, snippet: citation.snippet)
        }

        return orderedTitles.compactMap { byNormalizedTitle[$0] }
    }

    // MARK: - 内部解析辅助

    private static func findMatchingUserMessage(
        forAssistantAt index: Int,
        in messages: [AgentChatMessage]
    ) -> AgentChatMessage? {
        let assistant = messages[index]
        if let turnId = assistant.turnId, !turnId.isEmpty {
            if let match = messages[..<index].last(where: { $0.role == .user && $0.turnId == turnId }) {
                return match
            }
        }
        guard index > 0 else { return nil }
        for idx in stride(from: index - 1, through: 0, by: -1) {
            if messages[idx].role == .user {
                return messages[idx]
            }
            if messages[idx].role == .assistant {
                break
            }
        }
        return nil
    }

    private static func isCitationHeaderLine(_ rawLine: String) -> Bool {
        let stripped = rawLine
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "#", with: "")
            .replacingOccurrences(of: "*", with: "")
            .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "：:")))
        return stripped == "引用资料" || stripped.caseInsensitiveCompare("References") == .orderedSame
            || stripped.caseInsensitiveCompare("Sources") == .orderedSame
    }

    private static func parseCitationItemLine(_ line: String) -> ParsedAssistantCitation? {
        var working = line
        if working.hasPrefix("- ") || working.hasPrefix("* ") {
            working = String(working.dropFirst(2)).trimmingCharacters(in: .whitespaces)
        }

        var number: Int?
        if working.hasPrefix("["),
           let closeBracket = working.firstIndex(of: "]"),
           let parsedNum = Int(working[working.index(after: working.startIndex)..<closeBracket]) {
            number = parsedNum
            working = String(working[working.index(after: closeBracket)...])
                .trimmingCharacters(in: .whitespaces)
        } else if let dotRange = working.range(of: #"^\d+[\.\、]\s*"#, options: .regularExpression) {
            let digits = working[working.startIndex..<dotRange.upperBound]
                .filter(\.isNumber)
            number = Int(digits)
            working = String(working[dotRange.upperBound...]).trimmingCharacters(in: .whitespaces)
        }

        guard number != nil || line.hasPrefix("- ") || line.hasPrefix("* ") else {
            return nil
        }

        let separators = ["：", ": ", " - ", " — ", "｜", "|"]
        for sep in separators {
            if let range = working.range(of: sep) {
                let rawTitle = String(working[..<range.lowerBound])
                let rawSnippet = String(working[range.upperBound...])
                let title = cleanTitleText(rawTitle)
                let snippet = cleanSnippetText(rawSnippet)
                if !title.isEmpty {
                    return ParsedAssistantCitation(number: number, title: title, snippet: snippet)
                }
            }
        }

        let title = cleanTitleText(working)
        guard !title.isEmpty else { return nil }
        return ParsedAssistantCitation(number: number, title: title, snippet: nil)
    }

    private static func cleanTitleText(_ raw: String) -> String {
        var result = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        result = result.replacingOccurrences(of: "**", with: "")
        result = result.replacingOccurrences(of: "`", with: "")
        if result.hasPrefix("《"), result.hasSuffix("》"), result.count >= 2 {
            result = String(result.dropFirst().dropLast())
        }
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func cleanSnippetText(_ raw: String) -> String? {
        var result = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if (result.hasPrefix("“") && result.hasSuffix("”"))
            || (result.hasPrefix("\"") && result.hasSuffix("\"")) {
            if result.count >= 2 {
                result = String(result.dropFirst().dropLast())
            }
        }
        return nonEmpty(result)
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }
}
