import SwiftUI

/// 轻量极简 Markdown 视图：支持标题、列表、引用、分割线、GFM 表格与语法高亮代码块。
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
                case .table(let headers, let alignments, let rows):
                    AgentMarkdownTableView(headers: headers, alignments: alignments, rows: rows)
                case .blockquote(let content):
                    AgentBlockquoteView(content: content)
                case .thematicBreak:
                    Rectangle()
                        .fill(Color(uiColor: .separator).opacity(0.35))
                        .frame(height: 0.5)
                        .padding(.vertical, 6)
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
        let leadingSpaces = raw.prefix(while: { $0 == " " || $0 == "\t" }).count
        let indent = CGFloat(min(leadingSpaces / 2, 4)) * 14
        let trimmed = raw.trimmingCharacters(in: .whitespaces)

        if trimmed.isEmpty {
            Spacer().frame(height: 2)
        } else if trimmed.hasPrefix("#### ") {
            AgentInlineMarkdownText(source: String(trimmed.dropFirst(5)))
                .font(.subheadline.weight(.semibold))
                .padding(.top, 4)
        } else if trimmed.hasPrefix("### ") {
            AgentInlineMarkdownText(source: String(trimmed.dropFirst(4)))
                .font(.headline)
                .padding(.top, 4)
        } else if trimmed.hasPrefix("## ") {
            AgentInlineMarkdownText(source: String(trimmed.dropFirst(3)))
                .font(.title3.weight(.semibold))
                .padding(.top, 6)
        } else if trimmed.hasPrefix("# ") {
            AgentInlineMarkdownText(source: String(trimmed.dropFirst(2)))
                .font(.title2.weight(.bold))
                .padding(.top, 6)
        } else if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") {
            HStack(alignment: .top, spacing: 8) {
                Text(indent > 0 ? "◦" : "•")
                    .foregroundStyle(.secondary)
                AgentInlineMarkdownText(source: String(trimmed.dropFirst(2)))
            }
            .padding(.leading, 4 + indent)
        } else if let ordered = parseOrderedListLine(trimmed) {
            HStack(alignment: .top, spacing: 6) {
                Text(verbatim: "\(ordered.number).")
                    .foregroundStyle(.secondary)
                AgentInlineMarkdownText(source: ordered.body)
            }
            .padding(.leading, 4 + indent)
        } else {
            AgentInlineMarkdownText(source: raw)
        }
    }

    private func parseOrderedListLine(_ line: String) -> (number: String, body: String)? {
        guard let dotIndex = line.firstIndex(of: ".") else { return nil }
        let prefix = line[..<dotIndex]
        guard !prefix.isEmpty, prefix.allSatisfy(\.isNumber) else { return nil }
        let afterDot = line.index(after: dotIndex)
        guard afterDot < line.endIndex, line[afterDot] == " " else { return nil }
        let bodyStart = line.index(after: afterDot)
        return (String(prefix), String(line[bodyStart...]))
    }
}

private struct AgentInlineMarkdownText: View {
    let source: String

    var body: some View {
        Text(Self.styledAttributedString(from: source))
    }

    private static func styledAttributedString(from source: String) -> AttributedString {
        guard var attributed = try? AttributedString(
            markdown: source,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        ) else {
            return AttributedString(source)
        }
        for run in attributed.runs {
            if let intent = run.inlinePresentationIntent, intent.contains(.code) {
                attributed[run.range].font = .system(.subheadline, design: .monospaced)
                attributed[run.range].backgroundColor = Color(uiColor: .tertiarySystemFill)
            }
        }
        return attributed
    }
}

private struct AgentBlockquoteView: View {
    let content: String

    var body: some View {
        AgentInlineMarkdownText(source: content)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .padding(.leading, 12)
            .padding(.vertical, 2)
            .overlay(alignment: .leading) {
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(AppColors.primaryBlue.opacity(0.45))
                    .frame(width: 2.5)
            }
    }
}

private struct AgentMarkdownTableView: View {
    let headers: [String]
    let alignments: [HorizontalAlignment]
    let rows: [[String]]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 0) {
                GridRow {
                    ForEach(Array(headers.enumerated()), id: \.offset) { col, header in
                        cellView(text: header, column: col, isHeader: true)
                    }
                }
                .background(Color(uiColor: .secondarySystemBackground))

                ForEach(Array(rows.enumerated()), id: \.offset) { rowIndex, row in
                    Rectangle()
                        .fill(Color(uiColor: .separator).opacity(0.25))
                        .frame(height: 0.5)
                        .gridCellUnsizedAxes([.horizontal])

                    GridRow {
                        ForEach(0..<headers.count, id: \.self) { col in
                            let value = col < row.count ? row[col] : ""
                            cellView(text: value, column: col, isHeader: false)
                        }
                    }
                    .background(rowIndex.isMultiple(of: 2)
                                ? Color.clear
                                : Color(uiColor: .secondarySystemBackground).opacity(0.35))
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color(uiColor: .separator).opacity(0.25), lineWidth: 0.5)
            }
        }
    }

    @ViewBuilder
    private func cellView(text: String, column: Int, isHeader: Bool) -> some View {
        let alignment = column < alignments.count ? alignments[column] : .leading
        AgentInlineMarkdownText(source: text)
            .font(isHeader ? .subheadline.weight(.semibold) : .subheadline)
            .foregroundStyle(.primary)
            .multilineTextAlignment(textAlignment(for: alignment))
            .frame(maxWidth: .infinity, alignment: Alignment(horizontal: alignment, vertical: .center))
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
    }

    private func textAlignment(for alignment: HorizontalAlignment) -> TextAlignment {
        switch alignment {
        case .center: return .center
        case .trailing: return .trailing
        default: return .leading
        }
    }
}

private struct AgentCodeBlockView: View {
    let language: String
    let code: String
    @State private var copied = false
    @Environment(\.colorScheme) private var colorScheme

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
                Text(AgentCodeHighlighter.highlight(code, language: language, colorScheme: colorScheme))
                    .font(.system(.footnote, design: .monospaced))
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

struct MarkdownSegment: Identifiable, Equatable {
    enum Kind: Equatable {
        case prose(String)
        case code(language: String, code: String)
        case table(headers: [String], alignments: [HorizontalAlignment], rows: [[String]])
        case blockquote(String)
        case thematicBreak
    }

    let id: Int
    let kind: Kind

    static func parse(_ source: String) -> [MarkdownSegment] {
        let lines = source.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        var segments: [MarkdownSegment] = []
        var proseLines: [String] = []
        var index = 0

        func flushProse() {
            let joined = proseLines.joined(separator: "\n")
            if !joined.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                segments.append(MarkdownSegment(id: segments.count, kind: .prose(joined)))
            }
            proseLines.removeAll(keepingCapacity: true)
        }

        while index < lines.count {
            let line = lines[index]
            let trimmed = line.trimmingCharacters(in: .whitespaces)

            // 1. 围栏代码块
            if trimmed.hasPrefix("```") {
                flushProse()
                let language = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                var codeLines: [String] = []
                index += 1
                while index < lines.count {
                    if lines[index].trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                        index += 1
                        break
                    }
                    codeLines.append(lines[index])
                    index += 1
                }
                segments.append(MarkdownSegment(
                    id: segments.count,
                    kind: .code(language: language, code: codeLines.joined(separator: "\n"))
                ))
                continue
            }

            // 2. GFM 表格（表头 + 分隔行）
            if index + 1 < lines.count,
               let headers = parseTableRow(lines[index]),
               let alignments = parseTableSeparator(lines[index + 1], expectedColumns: headers.count) {
                flushProse()
                var rows: [[String]] = []
                index += 2
                while index < lines.count {
                    let candidate = lines[index].trimmingCharacters(in: .whitespaces)
                    if candidate.isEmpty || !candidate.contains("|") {
                        break
                    }
                    if let row = parseTableRow(lines[index]) {
                        rows.append(row)
                        index += 1
                    } else {
                        break
                    }
                }
                segments.append(MarkdownSegment(
                    id: segments.count,
                    kind: .table(headers: headers, alignments: alignments, rows: rows)
                ))
                continue
            }

            // 3. 引用块
            if trimmed.hasPrefix(">") {
                flushProse()
                var quoteLines: [String] = []
                while index < lines.count {
                    let quoteTrimmed = lines[index].trimmingCharacters(in: .whitespaces)
                    guard quoteTrimmed.hasPrefix(">") else { break }
                    var content = String(quoteTrimmed.dropFirst())
                    if content.hasPrefix(" ") {
                        content = String(content.dropFirst())
                    }
                    quoteLines.append(content)
                    index += 1
                }
                let joined = quoteLines.joined(separator: "\n")
                if !joined.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    segments.append(MarkdownSegment(id: segments.count, kind: .blockquote(joined)))
                }
                continue
            }

            // 4. 分割线
            if isThematicBreak(trimmed) {
                flushProse()
                segments.append(MarkdownSegment(id: segments.count, kind: .thematicBreak))
                index += 1
                continue
            }

            proseLines.append(line)
            index += 1
        }

        flushProse()
        return segments
    }

    private static func isThematicBreak(_ trimmed: String) -> Bool {
        guard trimmed.count >= 3 else { return false }
        let stripped = trimmed.filter { !$0.isWhitespace }
        guard stripped.count >= 3, let first = stripped.first, "-*_".contains(first) else {
            return false
        }
        return stripped.allSatisfy { $0 == first }
    }

    private static func parseTableRow(_ raw: String) -> [String]? {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        guard trimmed.contains("|") else { return nil }
        var inner = trimmed
        if inner.hasPrefix("|") { inner.removeFirst() }
        if inner.hasSuffix("|") { inner.removeLast() }
        let cells = inner.split(separator: "|", omittingEmptySubsequences: false).map {
            $0.trimmingCharacters(in: .whitespaces)
        }
        return cells.isEmpty ? nil : cells
    }

    private static func parseTableSeparator(_ raw: String, expectedColumns: Int) -> [HorizontalAlignment]? {
        guard let cells = parseTableRow(raw), cells.count == expectedColumns else { return nil }
        var alignments: [HorizontalAlignment] = []
        for cell in cells {
            guard !cell.isEmpty else { return nil }
            let leftColon = cell.hasPrefix(":")
            let rightColon = cell.hasSuffix(":")
            let middle = cell.dropFirst(leftColon ? 1 : 0).dropLast(rightColon ? 1 : 0)
            guard !middle.isEmpty, middle.allSatisfy({ $0 == "-" }) else { return nil }
            if leftColon && rightColon {
                alignments.append(.center)
            } else if rightColon {
                alignments.append(.trailing)
            } else {
                alignments.append(.leading)
            }
        }
        return alignments
    }
}
