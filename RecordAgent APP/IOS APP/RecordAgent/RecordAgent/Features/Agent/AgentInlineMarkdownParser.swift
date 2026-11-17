import SwiftUI

/// AI 语义着色调色板与内联 Markdown + 颜色标签解析器。
/// 支持 `**粗体**`、`*斜体*`、`~~删除线~~`、`` `行内代码` ``、`[链接](url)`，
/// 以及 `<font color="...">...</font>` / `<span style="color:...">...</span>` 的任意嵌套组合。
enum AgentInlineMarkdownParser {
    /// 解析结果的结构化片段信息（主要用于单元测试验证样式与颜色）。
    struct StyledRun: Equatable {
        let text: String
        let isBold: Bool
        let isItalic: Bool
        let isCode: Bool
        let isStrikethrough: Bool
        let colorToken: String?
        let linkURL: URL?
    }

    /// 供视图层渲染的 `AttributedString`。
    static func parse(
        _ source: String,
        textStyle: Font.TextStyle = .callout,
        defaultWeight: Font.Weight = .regular,
        colorScheme: ColorScheme = .light,
        defaultForeground: Color? = nil
    ) -> AttributedString {
        let runs = parseRuns(source)
        var result = AttributedString()

        for run in runs where !run.text.isEmpty {
            var segment = AttributedString(run.text)

            if run.isCode {
                let codeStyle: Font.TextStyle = (textStyle == .subheadline || textStyle == .footnote)
                    ? .footnote
                    : .subheadline
                segment.font = .system(codeStyle, design: .monospaced)
                segment.backgroundColor = Color(uiColor: .tertiarySystemFill)
                if let token = run.colorToken, let semantic = resolveColor(token, colorScheme: colorScheme) {
                    segment.foregroundColor = semantic
                } else if let defaultForeground {
                    segment.foregroundColor = defaultForeground
                }
            } else {
                let effectiveWeight = resolvedWeight(base: defaultWeight, isBold: run.isBold)
                var font = Font.system(textStyle, weight: effectiveWeight)
                if run.isItalic {
                    font = font.italic()
                }
                segment.font = font

                if run.isStrikethrough {
                    segment.strikethroughStyle = .single
                }

                if let url = run.linkURL {
                    segment.link = url
                }

                if let token = run.colorToken, let semantic = resolveColor(token, colorScheme: colorScheme) {
                    segment.foregroundColor = semantic
                } else if run.linkURL != nil {
                    segment.foregroundColor = AppColors.primaryBlue
                } else if run.isStrikethrough {
                    segment.foregroundColor = .secondary
                } else if let defaultForeground {
                    segment.foregroundColor = defaultForeground
                }
            }

            result.append(segment)
        }

        return result
    }

    /// 剥离颜色标注标签（保留纯文本或可读文本），用于复制整条回复与流式长度计算。
    static func stripColorTags(_ source: String) -> String {
        let cleaned = sanitizeIncompleteTrailingTag(source)
        return parseRuns(cleaned).map(\.text).joined()
    }

    /// 仅移除 `<font ...>` / `</font>` / `<span ...>` / `</span>` 标签本身，保留原有 Markdown 标记。
    static func stripMarkupTagsOnly(_ source: String) -> String {
        let cleaned = sanitizeIncompleteTrailingTag(source)
        var output = ""
        var index = cleaned.startIndex
        while index < cleaned.endIndex {
            if cleaned[index] == "<" {
                if let open = matchColorOpenTag(in: cleaned, at: index) {
                    index = open.nextIndex
                    continue
                }
                if let closeNext = matchColorCloseTag(in: cleaned, at: index) {
                    index = closeNext
                    continue
                }
            }
            output.append(cleaned[index])
            index = cleaned.index(after: index)
        }
        return output
    }

    /// 结构化拆解文本为带内联样式的片段列表。
    static func parseRuns(_ source: String) -> [StyledRun] {
        let cleaned = sanitizeIncompleteTrailingTag(source)
        var collector: [StyledRun] = []
        let initialStyle = StyleState(
            isBold: false,
            isItalic: false,
            isStrikethrough: false,
            colorToken: nil,
            linkURL: nil
        )
        parseSegment(cleaned[...], state: initialStyle, into: &collector)
        return mergeAdjacentRuns(collector)
    }

    // MARK: - 语义色板解析

    static func resolveColor(_ rawToken: String, colorScheme: ColorScheme) -> Color? {
        let token = rawToken
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        guard !token.isEmpty else { return nil }

        let isDark = colorScheme == .dark

        switch token {
        case "red", "crimson", "rose", "coral", "expense", "negative", "danger", "error", "outflow":
            return isDark
                ? Color(red: 1.0, green: 0.44, blue: 0.40)
                : Color(red: 0.84, green: 0.21, blue: 0.19)
        case "green", "emerald", "mint", "income", "positive", "success", "profit", "inflow":
            return isDark
                ? Color(red: 0.29, green: 0.83, blue: 0.53)
                : Color(red: 0.08, green: 0.56, blue: 0.34)
        case "blue", "azure", "sky", "info", "accent", "primary":
            return isDark
                ? Color(red: 0.42, green: 0.68, blue: 1.0)
                : Color(red: 0.14, green: 0.43, blue: 0.86)
        case "orange", "amber", "yellow", "gold", "warning", "warn":
            return isDark
                ? Color(red: 0.99, green: 0.68, blue: 0.28)
                : Color(red: 0.82, green: 0.46, blue: 0.04)
        case "purple", "violet", "indigo":
            return isDark
                ? Color(red: 0.72, green: 0.55, blue: 0.98)
                : Color(red: 0.49, green: 0.25, blue: 0.82)
        case "teal", "cyan":
            return isDark
                ? Color(red: 0.28, green: 0.78, blue: 0.82)
                : Color(red: 0.07, green: 0.52, blue: 0.56)
        case "gray", "grey", "muted", "secondary":
            return Color(uiColor: .secondaryLabel)
        default:
            return parseHexColor(token, isDark: isDark)
        }
    }

    private static func parseHexColor(_ token: String, isDark: Bool) -> Color? {
        guard token.hasPrefix("#") else { return nil }
        let hex = String(token.dropFirst())
        let expanded: String
        if hex.count == 3, hex.allSatisfy(\.isHexDigit) {
            expanded = hex.map { "\($0)\($0)" }.joined()
        } else if hex.count == 6, hex.allSatisfy(\.isHexDigit) {
            expanded = hex
        } else {
            return nil
        }

        guard let value = UInt64(expanded, radix: 16) else { return nil }
        var r = Double((value >> 16) & 0xFF) / 255.0
        var g = Double((value >> 8) & 0xFF) / 255.0
        var b = Double(value & 0xFF) / 255.0

        // 防止模型输出纯黑/纯白或过浅黄色导致在当前模式下不可读
        let luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        if isDark && luminance < 0.28 {
            let boost = 0.35
            r = r + (1.0 - r) * boost
            g = g + (1.0 - g) * boost
            b = b + (1.0 - b) * boost
        } else if !isDark && luminance > 0.78 {
            let factor = 0.65
            r *= factor
            g *= factor
            b *= factor
        }

        return Color(red: r, green: g, blue: b)
    }

    private static func resolvedWeight(base: Font.Weight, isBold: Bool) -> Font.Weight {
        guard isBold else { return base }
        switch base {
        case .bold, .heavy, .black:
            return .bold
        default:
            return .semibold
        }
    }

    // MARK: - 递归下降内联解析

    private struct StyleState {
        var isBold: Bool
        var isItalic: Bool
        var isStrikethrough: Bool
        var colorToken: String?
        var linkURL: URL?
    }

    private static func parseSegment(
        _ slice: Substring,
        state: StyleState,
        into collector: inout [StyledRun]
    ) {
        var index = slice.startIndex
        var plainBuffer = ""

        func flushPlain() {
            if !plainBuffer.isEmpty {
                collector.append(StyledRun(
                    text: plainBuffer,
                    isBold: state.isBold,
                    isItalic: state.isItalic,
                    isCode: false,
                    isStrikethrough: state.isStrikethrough,
                    colorToken: state.colorToken,
                    linkURL: state.linkURL
                ))
                plainBuffer.removeAll(keepingCapacity: true)
            }
        }

        while index < slice.endIndex {
            let ch = slice[index]

            // 1. 反斜杠转义（如 \*、\`、\<）
            if ch == "\\" {
                let next = slice.index(after: index)
                if next < slice.endIndex, #"\`*_{}[]()#+-.!<>"#.contains(slice[next]) {
                    plainBuffer.append(slice[next])
                    index = slice.index(after: next)
                    continue
                }
            }

            // 2. 行内代码 `code`
            if ch == "`" {
                let afterTick = slice.index(after: index)
                if let closeTick = slice[afterTick...].firstIndex(of: "`") {
                    flushPlain()
                    let codeText = String(slice[afterTick..<closeTick])
                    if !codeText.isEmpty {
                        collector.append(StyledRun(
                            text: codeText,
                            isBold: state.isBold,
                            isItalic: state.isItalic,
                            isCode: true,
                            isStrikethrough: state.isStrikethrough,
                            colorToken: state.colorToken,
                            linkURL: state.linkURL
                        ))
                    }
                    index = slice.index(after: closeTick)
                    continue
                }
            }

            // 3. 语义颜色标签 <font ...> / <span ...> 及孤立闭合标签
            if ch == "<" {
                if let openTag = matchColorOpenTag(in: slice, at: index) {
                    flushPlain()
                    let contentStart = openTag.nextIndex
                    var innerState = state
                    if let token = openTag.colorToken {
                        innerState.colorToken = token
                    }
                    if let match = findMatchingCloseTag(tagName: openTag.tagName, in: slice, from: contentStart) {
                        parseSegment(slice[contentStart..<match.closeStart], state: innerState, into: &collector)
                        index = match.afterClose
                    } else {
                        // 流式过程中标签尚未闭合：先按着色样式渲染到当前行末，避免界面闪烁标签源码
                        parseSegment(slice[contentStart..<slice.endIndex], state: innerState, into: &collector)
                        return
                    }
                    continue
                }

                if let closeNext = matchColorCloseTag(in: slice, at: index) {
                    index = closeNext
                    continue
                }
            }

            // 4. 加粗 **...** 或 __...__
            if (ch == "*" || ch == "_"),
               let next = slice.index(index, offsetBy: 1, limitedBy: slice.endIndex),
               next < slice.endIndex,
               slice[next] == ch {
                let delimiter = ch == "*" ? "**" : "__"
                let canOpen = ch == "*" || isValidUnderscoreBoundary(in: slice, delimiterStart: index, isOpening: true)
                if canOpen {
                    let contentStart = slice.index(after: next)
                    if let closeRange = findClosingDelimiter(delimiter, in: slice, from: contentStart, checkUnderscore: ch == "_") {
                        let inner = slice[contentStart..<closeRange.lowerBound]
                        if !inner.isEmpty {
                            flushPlain()
                            var nextState = state
                            nextState.isBold = true
                            parseSegment(inner, state: nextState, into: &collector)
                            index = closeRange.upperBound
                            continue
                        }
                    }
                }
            }

            // 5. 删除线 ~~...~~
            if ch == "~",
               let next = slice.index(index, offsetBy: 1, limitedBy: slice.endIndex),
               next < slice.endIndex,
               slice[next] == "~" {
                let contentStart = slice.index(after: next)
                if let closeRange = slice.range(of: "~~", range: contentStart..<slice.endIndex) {
                    let inner = slice[contentStart..<closeRange.lowerBound]
                    if !inner.isEmpty {
                        flushPlain()
                        var nextState = state
                        nextState.isStrikethrough = true
                        parseSegment(inner, state: nextState, into: &collector)
                        index = closeRange.upperBound
                        continue
                    }
                }
            }

            // 6. 斜体 *...*
            if ch == "*" {
                let contentStart = slice.index(after: index)
                if contentStart < slice.endIndex, !slice[contentStart].isWhitespace {
                    if let closeIndex = findClosingSingleAsterisk(in: slice, from: contentStart) {
                        let inner = slice[contentStart..<closeIndex]
                        if !inner.isEmpty {
                            flushPlain()
                            var nextState = state
                            nextState.isItalic = true
                            parseSegment(inner, state: nextState, into: &collector)
                            index = slice.index(after: closeIndex)
                            continue
                        }
                    }
                }
            }

            // 7. Markdown 链接 [text](url)
            if ch == "[" {
                if let linkMatch = matchMarkdownLink(in: slice, at: index) {
                    flushPlain()
                    var nextState = state
                    nextState.linkURL = linkMatch.url
                    parseSegment(linkMatch.label, state: nextState, into: &collector)
                    index = linkMatch.afterClose
                    continue
                }
            }

            plainBuffer.append(ch)
            index = slice.index(after: index)
        }

        flushPlain()
    }

    private static func findClosingDelimiter(
        _ delimiter: String,
        in slice: Substring,
        from start: String.Index,
        checkUnderscore: Bool
    ) -> Range<String.Index>? {
        var searchStart = start
        while searchStart < slice.endIndex {
            guard let found = slice.range(of: delimiter, range: searchStart..<slice.endIndex) else {
                return nil
            }
            if !checkUnderscore || isValidUnderscoreBoundary(in: slice, delimiterStart: found.lowerBound, isOpening: false) {
                return found
            }
            searchStart = found.upperBound
        }
        return nil
    }

    private static func isValidUnderscoreBoundary(
        in slice: Substring,
        delimiterStart: String.Index,
        isOpening: Bool
    ) -> Bool {
        if isOpening {
            guard delimiterStart > slice.startIndex else { return true }
            let prev = slice[slice.index(before: delimiterStart)]
            return !(prev.isASCII && (prev.isLetter || prev.isNumber))
        } else {
            guard let after = slice.index(delimiterStart, offsetBy: 2, limitedBy: slice.endIndex),
                  after < slice.endIndex else { return true }
            let next = slice[after]
            return !(next.isASCII && (next.isLetter || next.isNumber))
        }
    }

    private static func findClosingSingleAsterisk(in slice: Substring, from start: String.Index) -> String.Index? {
        var cursor = start
        while cursor < slice.endIndex {
            if slice[cursor] == "\\" {
                let next = slice.index(after: cursor)
                if next < slice.endIndex {
                    cursor = slice.index(after: next)
                    continue
                }
            }
            if slice[cursor] == "`" {
                let afterTick = slice.index(after: cursor)
                if let closeTick = slice[afterTick...].firstIndex(of: "`") {
                    cursor = slice.index(after: closeTick)
                    continue
                }
            }
            if slice[cursor] == "*" {
                let next = slice.index(after: cursor)
                if next < slice.endIndex, slice[next] == "*" {
                    // 跳过 ** 加粗标记，不把它误拆成单星斜体闭合
                    cursor = slice.index(after: next)
                    continue
                }
                let prev = slice[slice.index(before: cursor)]
                if !prev.isWhitespace {
                    return cursor
                }
            }
            cursor = slice.index(after: cursor)
        }
        return nil
    }

    private struct MarkdownLinkMatch {
        let label: Substring
        let url: URL
        let afterClose: String.Index
    }

    private static func matchMarkdownLink(in slice: Substring, at start: String.Index) -> MarkdownLinkMatch? {
        let labelStart = slice.index(after: start)
        guard let closeBracket = slice[labelStart...].firstIndex(of: "]") else { return nil }
        let afterBracket = slice.index(after: closeBracket)
        guard afterBracket < slice.endIndex, slice[afterBracket] == "(" else { return nil }
        let urlStart = slice.index(after: afterBracket)
        guard let closeParen = slice[urlStart...].firstIndex(of: ")") else { return nil }
        let rawURL = slice[urlStart..<closeParen].trimmingCharacters(in: .whitespaces)
        guard let url = URL(string: rawURL),
              let scheme = url.scheme?.lowercased(),
              ["http", "https", "mailto", "tel"].contains(scheme) else {
            return nil
        }
        return MarkdownLinkMatch(
            label: slice[labelStart..<closeBracket],
            url: url,
            afterClose: slice.index(after: closeParen)
        )
    }

    // MARK: - 颜色标签解析辅助

    private struct ColorOpenTagMatch {
        let tagName: String
        let colorToken: String?
        let nextIndex: String.Index
    }

    private static func matchColorOpenTag<S: StringProtocol>(in text: S, at start: String.Index) -> ColorOpenTagMatch?
    where S.SubSequence == Substring {
        guard let closeAngle = text[start...].firstIndex(of: ">") else { return nil }
        let rawTag = String(text[text.index(after: start)..<closeAngle])
        let trimmed = rawTag.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()

        let tagName: String
        if lower == "font" || lower.hasPrefix("font ") || lower.hasPrefix("font\t") {
            tagName = "font"
        } else if lower == "span" || lower.hasPrefix("span ") || lower.hasPrefix("span\t") {
            tagName = "span"
        } else {
            return nil
        }

        let attrString = String(trimmed.dropFirst(tagName.count))
        let colorToken = extractColorAttribute(from: attrString, tagName: tagName)
        return ColorOpenTagMatch(
            tagName: tagName,
            colorToken: colorToken,
            nextIndex: text.index(after: closeAngle)
        )
    }

    private static func matchColorCloseTag<S: StringProtocol>(in text: S, at start: String.Index) -> String.Index?
    where S.SubSequence == Substring {
        guard let closeAngle = text[start...].firstIndex(of: ">") else { return nil }
        let raw = text[text.index(after: start)..<closeAngle]
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        guard raw == "/font" || raw == "/span" else { return nil }
        return text.index(after: closeAngle)
    }

    private static func findMatchingCloseTag(
        tagName: String,
        in slice: Substring,
        from start: String.Index
    ) -> (closeStart: String.Index, afterClose: String.Index)? {
        var cursor = start
        var depth = 1

        while cursor < slice.endIndex {
            if slice[cursor] == "`" {
                let afterTick = slice.index(after: cursor)
                if let closeTick = slice[afterTick...].firstIndex(of: "`") {
                    cursor = slice.index(after: closeTick)
                    continue
                }
            }
            if slice[cursor] == "<" {
                if let nestedOpen = matchColorOpenTag(in: slice, at: cursor),
                   nestedOpen.tagName == tagName {
                    depth += 1
                    cursor = nestedOpen.nextIndex
                    continue
                }
                if let closeAngle = slice[cursor...].firstIndex(of: ">") {
                    let raw = slice[slice.index(after: cursor)..<closeAngle]
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                        .lowercased()
                    if raw == "/\(tagName)" {
                        depth -= 1
                        let afterClose = slice.index(after: closeAngle)
                        if depth == 0 {
                            return (cursor, afterClose)
                        }
                        cursor = afterClose
                        continue
                    }
                }
            }
            cursor = slice.index(after: cursor)
        }
        return nil
    }

    private static func extractColorAttribute(from attributes: String, tagName: String) -> String? {
        if tagName == "font", let colorRegex = Self.fontColorRegex {
            let ns = attributes as NSString
            let range = NSRange(location: 0, length: ns.length)
            if let match = colorRegex.firstMatch(in: attributes, range: range) {
                for group in 1...3 where match.range(at: group).location != NSNotFound {
                    return ns.substring(with: match.range(at: group))
                }
            }
        }

        if let styleRegex = Self.styleColorRegex {
            let ns = attributes as NSString
            let range = NSRange(location: 0, length: ns.length)
            if let match = styleRegex.firstMatch(in: attributes, range: range),
               match.range(at: 1).location != NSNotFound {
                return ns.substring(with: match.range(at: 1))
                    .trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }

        return nil
    }

    /// 流式输出时若行尾截断在 `<font` / `<span` / `</font` 半截标签上，暂隐残缺后缀避免屏幕上闪现源码。
    private static func sanitizeIncompleteTrailingTag(_ source: String) -> String {
        guard let lastOpen = source.lastIndex(of: "<") else { return source }
        let suffix = source[lastOpen...]
        guard !suffix.contains(">") else { return source }
        let lower = suffix.lowercased()
        let prefixes = ["<font", "<span", "</font", "</span"]
        if prefixes.contains(where: { lower.hasPrefix($0) || $0.hasPrefix(lower) }) {
            return String(source[..<lastOpen])
        }
        return source
    }

    private static func mergeAdjacentRuns(_ runs: [StyledRun]) -> [StyledRun] {
        var merged: [StyledRun] = []
        for run in runs {
            if let last = merged.last,
               last.isBold == run.isBold,
               last.isItalic == run.isItalic,
               last.isCode == run.isCode,
               last.isStrikethrough == run.isStrikethrough,
               last.colorToken == run.colorToken,
               last.linkURL == run.linkURL {
                merged[merged.count - 1] = StyledRun(
                    text: last.text + run.text,
                    isBold: last.isBold,
                    isItalic: last.isItalic,
                    isCode: last.isCode,
                    isStrikethrough: last.isStrikethrough,
                    colorToken: last.colorToken,
                    linkURL: last.linkURL
                )
            } else {
                merged.append(run)
            }
        }
        return merged
    }

    private static let fontColorRegex = try? NSRegularExpression(
        pattern: #"(?:^|\s)color\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))"#,
        options: [.caseInsensitive]
    )

    private static let styleColorRegex = try? NSRegularExpression(
        pattern: #"(?:^|\s|;|"|')color\s*:\s*([^;"']+)"#,
        options: [.caseInsensitive]
    )
}
