import SwiftUI

/// 极简可折叠 ReAct 思考与工具时间线：顶层单行折叠，展开后按步骤紧凑呈现思考摘要与工具组。
struct AgentProcessTimelineView: View {
    let blocks: [AgentMessageBlock]
    let isStreaming: Bool
    let elapsedSeconds: Int?
    @State private var isExpanded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var toolCount: Int {
        blocks.reduce(0) { partial, block in
            partial + (block.kind == .toolGroup ? block.tools.count : 0)
        }
    }

    private var hasThinking: Bool {
        blocks.contains {
            $0.kind == .thinking && !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    private var activeSummaryText: String {
        if let lastTool = blocks.reversed().lazy.compactMap({ $0.tools.last }).first,
           lastTool.status == .running {
            return Self.friendlyToolLabel(lastTool.toolName)
        }
        return String(localized: "agent.thinking.in_progress")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                    isExpanded.toggle()
                }
            } label: {
                HStack(spacing: 6) {
                    if isStreaming {
                        ProgressView()
                            .controlSize(.mini)
                            .tint(AppColors.primaryBlue)
                    }
                    Text(summaryLabel)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(.secondary)
                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(isExpanded ? 90 : 0))
                }
                .padding(.vertical, 4)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isExpanded {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(blocks) { block in
                        blockView(block)
                    }
                }
                .padding(.leading, 12)
                .overlay(alignment: .leading) {
                    Rectangle()
                        .fill(Color(uiColor: .separator).opacity(0.35))
                        .frame(width: 1.5)
                }
                .padding(.top, 2)
                .padding(.bottom, 4)
            }
        }
    }

    private var summaryLabel: String {
        if isStreaming {
            if let elapsedSeconds, elapsedSeconds > 0 {
                return "\(activeSummaryText) · \(elapsedSeconds)s"
            }
            return activeSummaryText
        }
        var parts: [String] = []
        if hasThinking {
            parts.append(String(localized: "agent.trace.thought"))
        }
        if toolCount > 0 {
            parts.append(String(localized: "agent.trace.tools \(toolCount)"))
        }
        if let elapsedSeconds, elapsedSeconds > 0 {
            parts.append("\(elapsedSeconds)s")
        }
        return parts.isEmpty ? String(localized: "agent.trace.thought") : parts.joined(separator: " · ")
    }

    @ViewBuilder
    private func blockView(_ block: AgentMessageBlock) -> some View {
        switch block.kind {
        case .thinking:
            let trimmed = block.content.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                AgentThinkingStepRow(content: trimmed)
            }

        case .text:
            let trimmed = block.content.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                AgentThinkingStepRow(content: trimmed, isIntermediateText: true)
            }

        case .toolGroup:
            if !block.tools.isEmpty {
                AgentToolGroupStepRow(tools: block.tools, isStreaming: isStreaming)
            }
        }
    }

    static func friendlyToolLabel(_ name: String) -> String {
        switch name {
        case "read_file": return String(localized: "agent.tool.read_file")
        case "write_file": return String(localized: "agent.tool.write_file")
        case "edit_file": return String(localized: "agent.tool.edit_file")
        case "list_dir", "list_files": return String(localized: "agent.tool.list_dir")
        case "search_files", "grep_search", "glob": return String(localized: "agent.tool.search_files")
        case "execute", "execute_command", "terminal", "custom_bash": return String(localized: "agent.tool.execute")
        case "web_search", "search_web": return String(localized: "agent.tool.web_search")
        case "web_fetch", "fetch_url": return String(localized: "agent.tool.web_fetch")
        case "query_database": return String(localized: "agent.tool.query_database")
        case "method_search": return String(localized: "agent.tool.method_search")
        case "reset_equipped_tools": return String(localized: "agent.tool.reset_equipped_tools")
        case "acceptance_report": return String(localized: "agent.tool.acceptance_report")
        case "session_recent", "session_list", "session_history", "session_search":
            return String(localized: "agent.tool.session_history")
        default: return name
        }
    }
}

/// 单步思考/过程说明折叠行：默认仅显示一行简明预览，点击后再展开完整推理内容。
private struct AgentThinkingStepRow: View {
    let content: String
    var isIntermediateText = false
    @State private var showFull = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var preview: String {
        AgentTimelineBuilder.thinkingPreview(content)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.18)) {
                    showFull.toggle()
                }
            } label: {
                HStack(alignment: .center, spacing: 6) {
                    Image(systemName: isIntermediateText ? "text.bubble" : "sparkles")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                    Text(verbatim: preview)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(showFull ? nil : 1)
                        .multilineTextAlignment(.leading)
                    Spacer(minLength: 4)
                    Image(systemName: "chevron.right")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(showFull ? 90 : 0))
                }
                .padding(.vertical, 2)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if showFull {
                Text(verbatim: content)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineSpacing(4.5)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        Color(uiColor: .secondarySystemBackground).opacity(0.65),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
            }
        }
    }
}

/// 单步工具组折叠行：将同一步连续工具合并为“已网页搜索（2 次）”等单行摘要，点击展开可读参数详情。
private struct AgentToolGroupStepRow: View {
    let tools: [AgentToolExecution]
    let isStreaming: Bool
    @State private var isExpanded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var hasRunning: Bool {
        isStreaming && tools.contains { $0.status == .running }
    }

    private var failedCount: Int {
        tools.filter { $0.status == .failed }.count
    }

    private var summaryText: String {
        var names: [String] = []
        for tool in tools {
            let label = AgentProcessTimelineView.friendlyToolLabel(tool.toolName)
            if !names.contains(label) { names.append(label) }
        }
        let head = names.prefix(2).joined(separator: "、")
        var text = tools.count > 1 ? "\(head)（\(tools.count)）" : head
        if tools.count == 1,
           let first = tools.first,
           !first.command.isEmpty {
            let arg = AgentTimelineBuilder.formatToolCommandSummary(first.command)
            if !arg.isEmpty {
                text += " · \(arg)"
            }
        }
        if !hasRunning && failedCount > 0 {
            text += " · \(String(localized: "agent.trace.failed_count \(failedCount)"))"
        }
        return text
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.18)) {
                    isExpanded.toggle()
                }
            } label: {
                HStack(spacing: 6) {
                    groupStatusIcon
                    Text(verbatim: summaryText)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Spacer(minLength: 4)
                    Image(systemName: "chevron.right")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(isExpanded ? 90 : 0))
                }
                .padding(.vertical, 2)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isExpanded {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(tools) { tool in
                        AgentToolTraceRow(tool: tool)
                    }
                }
                .padding(.leading, 10)
            }
        }
    }

    @ViewBuilder
    private var groupStatusIcon: some View {
        if hasRunning {
            ProgressView()
                .controlSize(.mini)
                .tint(AppColors.primaryBlue)
        } else if failedCount > 0 {
            Image(systemName: "exclamationmark.circle")
                .font(.caption)
                .foregroundStyle(AppColors.overviewExpense)
        } else {
            Image(systemName: "checkmark.circle")
                .font(.caption)
                .foregroundStyle(AppColors.overviewStudy)
        }
    }
}

private struct AgentToolTraceRow: View {
    let tool: AgentToolExecution
    @State private var showOutput = false

    private var formattedArg: String {
        AgentTimelineBuilder.formatToolCommandSummary(tool.command)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button {
                if let output = tool.output, !output.isEmpty {
                    showOutput.toggle()
                }
            } label: {
                HStack(spacing: 6) {
                    statusIcon
                    Text(verbatim: AgentProcessTimelineView.friendlyToolLabel(tool.toolName))
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.primary)
                    if !formattedArg.isEmpty {
                        Text(verbatim: formattedArg)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer(minLength: 0)
                    if let output = tool.output, !output.isEmpty {
                        Image(systemName: showOutput ? "chevron.down" : "chevron.right")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if showOutput, let output = tool.output, !output.isEmpty {
                Text(verbatim: String(output.prefix(1200)))
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        Color(uiColor: .secondarySystemBackground),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
            }
        }
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch tool.status {
        case .running:
            ProgressView()
                .controlSize(.mini)
                .tint(AppColors.primaryBlue)
        case .completed:
            Image(systemName: "checkmark")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AppColors.overviewStudy)
        case .failed:
            Image(systemName: "xmark")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AppColors.overviewExpense)
        case .cancelled:
            Image(systemName: "minus")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }
}
