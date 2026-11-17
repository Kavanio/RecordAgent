import SwiftUI

/// 轻量极简 Markdown 视图：直排正文与标题列表，代码块使用柔和无边框底色与复制按钮。
struct AgentMarkdownView: View {
    let text: String

    private var segments: [MarkdownSegment] {
        MarkdownSegment.parse(text)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(segments) { segment in
                switch segment.kind {
                case .prose(let content):
                    AgentProseBlockView(content: content)
                case .code(let language, let code):
                    AgentCodeBlockView(language: language, code: code)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
    }
}

private struct AgentProseBlockView: View {
    let content: String

    var body: some View {
        let lines = content.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                proseLine(line)
            }
        }
    }

    @ViewBuilder
    private func proseLine(_ raw: String) -> some View {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty {
            Spacer().frame(height: 2)
        } else if trimmed.hasPrefix("### ") {
            inlineText(String(trimmed.dropFirst(4)))
                .font(.headline)
                .padding(.top, 4)
        } else if trimmed.hasPrefix("## ") {
            inlineText(String(trimmed.dropFirst(3)))
                .font(.title3.weight(.semibold))
                .padding(.top, 6)
        } else if trimmed.hasPrefix("# ") {
            inlineText(String(trimmed.dropFirst(2)))
                .font(.title2.weight(.bold))
                .padding(.top, 6)
        } else if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") {
            HStack(alignment: .top, spacing: 8) {
                Text("•")
                    .foregroundStyle(.secondary)
                inlineText(String(trimmed.dropFirst(2)))
            }
            .padding(.leading, 4)
        } else {
            inlineText(raw)
        }
    }

    private func inlineText(_ source: String) -> Text {
        if let attributed = try? AttributedString(
            markdown: source,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        ) {
            return Text(attributed)
        }
        return Text(verbatim: source)
    }
}

private struct AgentCodeBlockView: View {
    let language: String
    let code: String
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(language.isEmpty ? "code" : language)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    UIPasteboard.general.string = code
                    copied = true
                    Task {
                        try? await Task.sleep(for: .seconds(1.5))
                        copied = false
                    }
                } label: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                        .font(.caption)
                        .foregroundStyle(copied ? AppColors.primaryBlue : .secondary)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("agent.copy_code")
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
            .padding(.bottom, 4)

            ScrollView(.horizontal, showsIndicators: false) {
                Text(verbatim: code)
                    .font(.system(.footnote, design: .monospaced))
                    .foregroundStyle(.primary)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
            }
        }
        .background(
            Color(uiColor: .secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
    }
}

private struct MarkdownSegment: Identifiable {
    enum Kind {
        case prose(String)
        case code(language: String, code: String)
    }

    let id: Int
    let kind: Kind

    static func parse(_ source: String) -> [MarkdownSegment] {
        let lines = source.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        var segments: [MarkdownSegment] = []
        var proseLines: [String] = []
        var codeLines: [String] = []
        var codeLanguage = ""
        var inCodeBlock = false

        func flushProse() {
            let joined = proseLines.joined(separator: "\n")
            if !joined.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                segments.append(MarkdownSegment(id: segments.count, kind: .prose(joined)))
            }
            proseLines.removeAll(keepingCapacity: true)
        }

        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("```") {
                if inCodeBlock {
                    segments.append(MarkdownSegment(
                        id: segments.count,
                        kind: .code(language: codeLanguage, code: codeLines.joined(separator: "\n"))
                    ))
                    codeLines.removeAll(keepingCapacity: true)
                    codeLanguage = ""
                    inCodeBlock = false
                } else {
                    flushProse()
                    codeLanguage = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                    inCodeBlock = true
                }
            } else if inCodeBlock {
                codeLines.append(line)
            } else {
                proseLines.append(line)
            }
        }

        if inCodeBlock {
            segments.append(MarkdownSegment(
                id: segments.count,
                kind: .code(language: codeLanguage, code: codeLines.joined(separator: "\n"))
            ))
        } else {
            flushProse()
        }
        return segments
    }
}
