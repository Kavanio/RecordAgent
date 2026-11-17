import SwiftUI

/// 极简可折叠 ReAct 思考与工具时间线：无卡片外框，仅用单行摘要与细引导线组织过程。
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
        blocks.contains { $0.kind == .thinking && !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
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
                        .fill(Color(uiColor: .separator).opacity(0.45))
                        .frame(width: 1.5)
                }
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
            if !block.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(verbatim: block.content.trimmingCharacters(in: .whitespacesAndNewlines))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

        case .text:
            if !block.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                AgentMarkdownView(text: block.content)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

        case .toolGroup:
            VStack(alignment: .leading, spacing: 6) {
                ForEach(block.tools) { tool in
                    AgentToolTraceRow(tool: tool)
                }
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
        default: return name
        }
    }
}

private struct AgentToolTraceRow: View {
    let tool: AgentToolExecution
    @State private var showOutput = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button {
                if let output = tool.output, !output.isEmpty {
                    showOutput.toggle()
                }
            } label: {
                HStack(spacing: 6) {
                    statusIcon
                    Text(AgentProcessTimelineView.friendlyToolLabel(tool.toolName))
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(.primary)
                    if !tool.command.isEmpty {
                        Text(verbatim: tool.command)
                            .font(.caption.monospaced())
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
            Image(systemName: "checkmark.circle.fill")
                .font(.caption)
                .foregroundStyle(AppColors.overviewStudy)
        case .failed:
            Image(systemName: "xmark.circle.fill")
                .font(.caption)
                .foregroundStyle(AppColors.overviewExpense)
        case .cancelled:
            Image(systemName: "slash.circle")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}
