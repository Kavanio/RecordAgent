import SwiftUI

/// 极简高可读性 Markdown 视图：支持标题层级、自然段落、有序/无序列表、引用、分割线、
/// 全宽自适应 GFM 表格、语法高亮代码块，以及粗体与 AI 语义彩色标注。
struct AgentMarkdownView: View {
    let text: String
    @State private var containerWidth: CGFloat = 0

    private var segments: [MarkdownSegment] {
        MarkdownSegment.parse(text)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            ForEach(segments) { segment in
                switch segment.kind {
                case .heading(let level, let content):
                    headingView(level: level, content: content, isFirst: segment.id == 0)
                case .paragraph(let content):
                    AgentInlineMarkdownText(
                        source: content,
                        textStyle: .callout,
                        defaultForeground: Color(uiColor: .label)
                    )
                    .lineSpacing(6)
                    .fixedSize(horizontal: false, vertical: true)
                case .list(let items):
                    AgentMarkdownListView(items: items)
                case .code(let language, let code):
                    AgentCodeBlockView(language: language, code: code)
                case .table(let headers, let alignments, let rows):
                    AgentMarkdownTableView(
                        headers: headers,
                        alignments: alignments,
                        rows: rows,
                        availableWidth: containerWidth
                    )
                case .blockquote(let content):
                    AgentBlockquoteView(content: content)
                case .thematicBreak:
                    Rectangle()
                        .fill(Color(uiColor: .separator).opacity(0.32))
                        .frame(height: 0.5)
                        .padding(.vertical, 6)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            GeometryReader { proxy in
                Color.clear
                    .onAppear {
                        updateContainerWidth(proxy.size.width)
                    }
                    .onChange(of: proxy.size.width) { _, newWidth in
                        updateContainerWidth(newWidth)
                    }
            }
        }
        .textSelection(.enabled)
    }

    private func updateContainerWidth(_ width: CGFloat) {
        guard width > 0, abs(containerWidth - width) > 0.5 else { return }
        containerWidth = width
    }

    @ViewBuilder
    private func headingView(level: Int, content: String, isFirst: Bool) -> some View {
        let topPadding: CGFloat = isFirst ? 0 : (level <= 2 ? 10 : 6)
        switch level {
        case 1:
            AgentInlineMarkdownText(source: content, textStyle: .title2, defaultWeight: .bold)
                .lineSpacing(4)
                .padding(.top, topPadding)
        case 2:
            AgentInlineMarkdownText(source: content, textStyle: .title3, defaultWeight: .semibold)
                .lineSpacing(4)
                .padding(.top, topPadding)
        case 3:
            AgentInlineMarkdownText(source: content, textStyle: .headline, defaultWeight: .semibold)
                .lineSpacing(4)
                .padding(.top, topPadding)
        default:
            AgentInlineMarkdownText(source: content, textStyle: .callout, defaultWeight: .semibold)
                .lineSpacing(4)
                .padding(.top, topPadding)
        }
    }
}

/// 内联 Markdown 渲染包装：注入当前颜色模式以自适应语义色板。
private struct AgentInlineMarkdownText: View {
    let source: String
    var textStyle: Font.TextStyle = .callout
    var defaultWeight: Font.Weight = .regular
    var defaultForeground: Color? = nil
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Text(
            AgentInlineMarkdownParser.parse(
                source,
                textStyle: textStyle,
                defaultWeight: defaultWeight,
                colorScheme: colorScheme,
                defaultForeground: defaultForeground
            )
        )
    }
}

/// 列表块视图：统一对齐编号/圆点与多行折行缩进，保持舒适条目间距。
private struct AgentMarkdownListView: View {
    let items: [MarkdownListItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(items) { item in
                let indent = CGFloat(min(item.indentLevel, 4)) * 16
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    if let number = item.orderedNumber {
                        Text(verbatim: "\(number).")
                            .font(.callout.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .frame(minWidth: 18, alignment: .trailing)
                    } else {
                        Text(item.indentLevel > 0 ? "◦" : "•")
                            .font(.callout.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .frame(width: 14, alignment: .center)
                    }

                    AgentInlineMarkdownText(
                        source: item.text,
                        textStyle: .callout,
                        defaultForeground: Color(uiColor: .label)
                    )
                    .lineSpacing(5.5)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.leading, 2 + indent)
            }
        }
    }
}

private struct AgentBlockquoteView: View {
    let content: String

    var body: some View {
        AgentInlineMarkdownText(
            source: content,
            textStyle: .subheadline,
            defaultForeground: .secondary
        )
        .lineSpacing(5)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            Color(uiColor: .secondarySystemBackground).opacity(0.45),
            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
        )
        .overlay(alignment: .leading) {
            RoundedRectangle(cornerRadius: 1.5)
                .fill(AppColors.primaryBlue.opacity(0.55))
                .frame(width: 3)
                .padding(.vertical, 4)
        }
    }
}

/// 全宽自适应表格：列少时撑满可用宽度，列多时支持横向顺滑滚动；数值采用等宽数字并支持语义色彩。
private struct AgentMarkdownTableView: View {
    let headers: [String]
    let alignments: [HorizontalAlignment]
    let rows: [[String]]
    let availableWidth: CGFloat

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 0) {
                GridRow {
                    ForEach(Array(headers.enumerated()), id: \.offset) { col, header in
                        cellView(text: header, column: col, isHeader: true)
                    }
                }
                .background(Color(uiColor: .secondarySystemBackground).opacity(0.75))

                ForEach(Array(rows.enumerated()), id: \.offset) { rowIndex, row in
                    Rectangle()
                        .fill(Color(uiColor: .separator).opacity(0.22))
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
                                : Color(uiColor: .secondarySystemBackground).opacity(0.22))
                }
            }
            .frame(minWidth: availableWidth > 0 ? availableWidth : nil, alignment: .leading)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color(uiColor: .separator).opacity(0.26), lineWidth: 0.5)
            }
        }
    }

    @ViewBuilder
    private func cellView(text: String, column: Int, isHeader: Bool) -> some View {
        let alignment = column < alignments.count ? alignments[column] : .leading
        AgentInlineMarkdownText(
            source: text,
            textStyle: .subheadline,
            defaultWeight: isHeader ? .semibold : .regular,
            defaultForeground: isHeader ? Color(uiColor: .label) : Color(uiColor: .label)
        )
        .monospacedDigit()
        .lineSpacing(3.5)
        .multilineTextAlignment(textAlignment(for: alignment))
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: Alignment(horizontal: alignment, vertical: .center))
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
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
            .padding(.horizontal, 14)
            .padding(.top, 8)
            .padding(.bottom, 4)

            ScrollView(.horizontal, showsIndicators: false) {
                Text(AgentCodeHighlighter.highlight(code, language: language, colorScheme: colorScheme))
                    .font(.system(.footnote, design: .monospaced))
                    .lineSpacing(4)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 14)
            }
        }
        .background(
            Color(uiColor: .secondarySystemBackground).opacity(0.85),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color(uiColor: .separator).opacity(0.2), lineWidth: 0.5)
        }
    }
}

struct MarkdownListItem: Identifiable, Equatable {
    let id: Int
    let orderedNumber: String?
    let indentLevel: Int
    let text: String
}

struct MarkdownSegment: Identifiable, Equatable {
    enum Kind: Equatable {
        case heading(level: Int, text: String)
        case paragraph(String)
        case list([MarkdownListItem])
        case code(language: String, code: String)
        case table(headers: [String], alignments: [HorizontalAlignment], rows: [[String]])
        case blockquote(String)
        case thematicBreak
    }

    let id: Int
    let kind: Kind

    static func parse(_ source: String) -> [MarkdownSegment] {
        let normalized = normalizeInlineTables(in: source)
        let lines = normalized
            .replacingOccurrences(of: "\r\n", with: "\n")
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map(String.init)
        var segments: [MarkdownSegment] = []
        var paragraphLines: [String] = []
        var index = 0

        func flushParagraph() {
            let trimmedLines = paragraphLines
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
            if !trimmedLines.isEmpty {
                let joined = trimmedLines.joined(separator: "\n")
                segments.append(MarkdownSegment(id: segments.count, kind: .paragraph(joined)))
            }
            paragraphLines.removeAll(keepingCapacity: true)
        }

        while index < lines.count {
            let line = lines[index]
            let trimmed = line.trimmingCharacters(in: .whitespaces)

            // 0. 空行分隔段落
            if trimmed.isEmpty {
                flushParagraph()
                index += 1
                continue
            }

            // 1. 围栏代码块
            if trimmed.hasPrefix("```") {
                flushParagraph()
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
                flushParagraph()
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

            // 3. 标题（# ~ ####）
            if let heading = parseHeading(trimmed) {
                flushParagraph()
                segments.append(MarkdownSegment(
                    id: segments.count,
                    kind: .heading(level: heading.level, text: heading.text)
                ))
                index += 1
                continue
            }

            // 4. 分割线
            if isThematicBreak(trimmed) {
                flushParagraph()
                segments.append(MarkdownSegment(id: segments.count, kind: .thematicBreak))
                index += 1
                continue
            }

            // 5. 引用块
            if trimmed.hasPrefix(">") {
                flushParagraph()
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

            // 6. 有序 / 无序列表（连续列表项合并为单个列表块）
            if let firstItem = parseListItem(line: line, itemId: 0) {
                flushParagraph()
                var listItems: [MarkdownListItem] = [firstItem]
                index += 1
                while index < lines.count {
                    let nextLine = lines[index]
                    let nextTrimmed = nextLine.trimmingCharacters(in: .whitespaces)
                    if nextTrimmed.isEmpty {
                        // 允许列表项之间隔一个空行继续，但遇到非列表则停止
                        if index + 1 < lines.count,
                           parseListItem(line: lines[index + 1], itemId: 0) != nil {
                            index += 1
                            continue
                        }
                        break
                    }
                    if let item = parseListItem(line: nextLine, itemId: listItems.count) {
                        listItems.append(item)
                        index += 1
                    } else if !startsBlockElement(nextLine), !listItems.isEmpty {
                        // 列表项的延续折行
                        let last = listItems[listItems.count - 1]
                        listItems[listItems.count - 1] = MarkdownListItem(
                            id: last.id,
                            orderedNumber: last.orderedNumber,
                            indentLevel: last.indentLevel,
                            text: last.text + "\n" + nextTrimmed
                        )
                        index += 1
                    } else {
                        break
                    }
                }
                segments.append(MarkdownSegment(id: segments.count, kind: .list(listItems)))
                continue
            }

            paragraphLines.append(line)
            index += 1
        }

        flushParagraph()
        return segments
    }

    private static func startsBlockElement(_ line: String) -> Bool {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        return trimmed.hasPrefix("```")
            || trimmed.hasPrefix(">")
            || parseHeading(trimmed) != nil
            || isThematicBreak(trimmed)
            || parseListItem(line: line, itemId: 0) != nil
    }

    private static func parseHeading(_ trimmed: String) -> (level: Int, text: String)? {
        for level in (1...4).reversed() {
            let prefix = String(repeating: "#", count: level) + " "
            if trimmed.hasPrefix(prefix) {
                let text = String(trimmed.dropFirst(prefix.count)).trimmingCharacters(in: .whitespaces)
                if !text.isEmpty {
                    return (level, text)
                }
            }
        }
        return nil
    }

    private static func parseListItem(line: String, itemId: Int) -> MarkdownListItem? {
        let leadingSpaces = line.prefix(while: { $0 == " " || $0 == "\t" }).count
        let indentLevel = leadingSpaces / 2
        let trimmed = line.trimmingCharacters(in: .whitespaces)

        if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") || trimmed.hasPrefix("+ ") {
            let body = String(trimmed.dropFirst(2)).trimmingCharacters(in: .whitespaces)
            return MarkdownListItem(id: itemId, orderedNumber: nil, indentLevel: indentLevel, text: body)
        }
        if let dotIndex = trimmed.firstIndex(of: ".") {
            let prefix = trimmed[..<dotIndex]
            if !prefix.isEmpty, prefix.allSatisfy(\.isNumber) {
                let afterDot = trimmed.index(after: dotIndex)
                if afterDot < trimmed.endIndex, trimmed[afterDot] == " " {
                    let body = String(trimmed[trimmed.index(after: afterDot)...])
                        .trimmingCharacters(in: .whitespaces)
                    return MarkdownListItem(
                        id: itemId,
                        orderedNumber: String(prefix),
                        indentLevel: indentLevel,
                        text: body
                    )
                }
            }
        }
        return nil
    }

    /// 容错清洗：修复模型输出中偶尔被压缩在同一行的表格（`| a | b | | --- | --- |`）。
    private static func normalizeInlineTables(in raw: String) -> String {
        guard raw.contains("|") else { return raw }
        let parts = raw.components(separatedBy: "```")
        return parts.enumerated().map { index, part in
            if index % 2 == 1 { return part }
            return part.replacingOccurrences(
                of: #"\|\s*\|\s*(?=[^\n]*\|)"#,
                with: "|\n|",
                options: .regularExpression
            )
        }.joined(separator: "```")
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
