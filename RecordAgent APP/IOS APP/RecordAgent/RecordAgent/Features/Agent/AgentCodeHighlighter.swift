import SwiftUI

/// 轻量代码语法高亮器：为常见语言提供关键字、字符串、数字、注释与类型着色，无第三方依赖。
enum AgentCodeHighlighter {
    static func highlight(_ code: String, language: String, colorScheme: ColorScheme) -> AttributedString {
        var attributed = AttributedString(code)
        let palette = Palette(colorScheme: colorScheme)
        let nsSource = code as NSString
        let fullRange = NSRange(location: 0, length: nsSource.length)

        var occupiedRanges: [NSRange] = []

        func apply(_ regex: NSRegularExpression?, color: Color, bold: Bool = false) {
            guard let regex else { return }
            for match in regex.matches(in: code, options: [], range: fullRange) {
                let nsRange = match.range
                guard nsRange.length > 0, !overlaps(nsRange, with: occupiedRanges) else { continue }
                guard let stringRange = Range(nsRange, in: code),
                      let attrRange = Range(stringRange, in: attributed) else { continue }
                attributed[attrRange].foregroundColor = color
                if bold {
                    attributed[attrRange].font = .system(.footnote, design: .monospaced).weight(.semibold)
                }
                occupiedRanges.append(nsRange)
            }
        }

        let normalizedLang = language.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        // 1. 注释优先占用，避免注释内部触发字符串/关键字高亮
        if ["python", "py", "bash", "sh", "zsh", "yaml", "yml", "ruby", "rb"].contains(normalizedLang) {
            apply(Self.hashCommentRegex, color: palette.comment)
        } else if ["sql"].contains(normalizedLang) {
            apply(Self.sqlCommentRegex, color: palette.comment)
            apply(Self.blockCommentRegex, color: palette.comment)
        } else {
            apply(Self.slashCommentRegex, color: palette.comment)
            apply(Self.blockCommentRegex, color: palette.comment)
            if normalizedLang.isEmpty {
                apply(Self.hashCommentRegex, color: palette.comment)
            }
        }

        // 2. 字符串字面量
        apply(Self.doubleStringRegex, color: palette.string)
        apply(Self.singleStringRegex, color: palette.string)
        apply(Self.backtickStringRegex, color: palette.string)

        // 3. 装饰器 / 注解
        apply(Self.decoratorRegex, color: palette.type)

        // 4. 关键字
        apply(Self.keywordRegex, color: palette.keyword, bold: true)

        // 5. 数字与首字母大写类型名
        apply(Self.numberRegex, color: palette.number)
        apply(Self.typeRegex, color: palette.type)

        return attributed
    }

    private static func overlaps(_ range: NSRange, with occupied: [NSRange]) -> Bool {
        for existing in occupied {
            if NSIntersectionRange(range, existing).length > 0 {
                return true
            }
        }
        return false
    }

    private struct Palette {
        let keyword: Color
        let string: Color
        let comment: Color
        let number: Color
        let type: Color

        init(colorScheme: ColorScheme) {
            if colorScheme == .dark {
                keyword = Color(red: 0.78, green: 0.57, blue: 0.96)
                string = Color(red: 0.96, green: 0.58, blue: 0.48)
                comment = Color(red: 0.54, green: 0.58, blue: 0.65)
                number = Color(red: 0.85, green: 0.75, blue: 0.48)
                type = Color(red: 0.45, green: 0.79, blue: 0.88)
            } else {
                keyword = Color(red: 0.61, green: 0.15, blue: 0.69)
                string = Color(red: 0.77, green: 0.22, blue: 0.14)
                comment = Color(red: 0.45, green: 0.50, blue: 0.56)
                number = Color(red: 0.11, green: 0.43, blue: 0.72)
                type = Color(red: 0.08, green: 0.49, blue: 0.52)
            }
        }
    }

    private static let slashCommentRegex = try? NSRegularExpression(pattern: #"//[^\n]*"#)
    private static let hashCommentRegex = try? NSRegularExpression(pattern: #"#[^\n]*"#)
    private static let sqlCommentRegex = try? NSRegularExpression(pattern: #"--[^\n]*"#)
    private static let blockCommentRegex = try? NSRegularExpression(
        pattern: #"/\*[\s\S]*?\*/"#,
        options: [.dotMatchesLineSeparators]
    )
    private static let doubleStringRegex = try? NSRegularExpression(pattern: #""(?:\\.|[^"\\])*""#)
    private static let singleStringRegex = try? NSRegularExpression(pattern: #"'(?:\\.|[^'\\])*'"#)
    private static let backtickStringRegex = try? NSRegularExpression(pattern: #"`(?:\\.|[^`\\])*`"#)
    private static let decoratorRegex = try? NSRegularExpression(pattern: #"@[A-Za-z_][A-Za-z0-9_]*"#)
    private static let numberRegex = try? NSRegularExpression(pattern: #"\b\d+(?:\.\d+)?\b"#)
    private static let typeRegex = try? NSRegularExpression(pattern: #"\b[A-Z][A-Za-z0-9_]*\b"#)

    private static let keywords = [
        "def", "return", "import", "from", "as", "class", "struct", "enum", "protocol",
        "extension", "func", "let", "var", "const", "if", "else", "elif", "switch",
        "case", "default", "for", "while", "in", "break", "continue", "try", "catch",
        "except", "finally", "throw", "throws", "async", "await", "yield", "with",
        "public", "private", "internal", "fileprivate", "open", "static", "final",
        "override", "mutating", "init", "deinit", "self", "Self", "super", "true",
        "false", "nil", "null", "None", "True", "False", "lambda", "pass", "raise",
        "function", "export", "type", "interface", "implements", "extends", "new",
        "void", "boolean", "int", "long", "double", "float", "guard", "where",
        "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "JOIN", "ON",
        "GROUP", "BY", "ORDER", "LIMIT", "AS", "AND", "OR", "NOT", "IN"
    ]

    private static let keywordRegex: NSRegularExpression? = {
        let joined = keywords.joined(separator: "|")
        return try? NSRegularExpression(pattern: "\\b(?:\(joined))\\b")
    }()
}
