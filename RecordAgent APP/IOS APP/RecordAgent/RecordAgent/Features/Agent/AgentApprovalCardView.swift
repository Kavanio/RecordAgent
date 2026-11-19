import SwiftUI

/// 单批待确认工具调用的极简审批交互区：支持逐项选择或整批批准，单次原子提交后恢复对话流。
struct AgentApprovalCardView: View {
    let approval: AgentPendingApproval
    @Binding var selections: [String: AgentPermissionDecisionInput]
    let isSubmitting: Bool
    var onViewPlan: (() -> Void)? = nil
    let onSubmit: (Bool?) -> Void

    private var hasPlanExitTool: Bool {
        approval.tools.contains { $0.toolName == "plan_exit" }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: "hand.raised")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.primaryBlue)
                Text("agent.approval.title")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Spacer()
                if approval.tools.count > 1 {
                    Text("agent.approval.count \(approval.tools.count)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            VStack(alignment: .leading, spacing: 12) {
                ForEach(approval.tools) { tool in
                    toolDecisionRow(tool)
                }
            }

            if hasPlanExitTool, let onViewPlan {
                Button(action: onViewPlan) {
                    HStack(spacing: 8) {
                        Image(systemName: "doc.text")
                            .font(.subheadline)
                            .foregroundStyle(AppColors.primaryBlue)
                        Text("agent.approval.view_plan")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.primary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(Color(uiColor: .systemBackground).opacity(0.75),
                                in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
            }

            HStack(spacing: 10) {
                Button {
                    onSubmit(false)
                } label: {
                    Text("agent.approval.reject_all")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .background(
                            Color(uiColor: .tertiarySystemFill),
                            in: Capsule()
                        )
                }
                .buttonStyle(.plain)
                .disabled(isSubmitting)

                Button {
                    onSubmit(approval.tools.count > 1 ? nil : true)
                } label: {
                    HStack(spacing: 6) {
                        if isSubmitting {
                            ProgressView()
                                .controlSize(.mini)
                                .tint(.white)
                        }
                        Text(approval.tools.count > 1
                             ? LocalizedStringKey("agent.approval.submit_decisions")
                             : LocalizedStringKey("agent.approval.allow"))
                            .font(.subheadline.weight(.semibold))
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, minHeight: 40)
                    .background(AppColors.primaryBlue, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(isSubmitting)
            }
        }
        .padding(16)
        .background(
            Color(uiColor: .secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: 20, style: .continuous)
        )
    }

    @ViewBuilder
    private func toolDecisionRow(_ tool: AgentPermissionTool) -> some View {
        let current = selections[tool.toolCallId] ?? AgentPermissionDecisionInput(
            toolCallId: tool.toolCallId,
            approved: true,
            rememberForSession: false
        )

        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 8) {
                Text(verbatim: AgentProcessTimelineView.friendlyToolLabel(tool.toolName))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.primary)
                Spacer(minLength: 0)
                if approval.tools.count > 1 {
                    Button {
                        selections[tool.toolCallId] = AgentPermissionDecisionInput(
                            toolCallId: tool.toolCallId,
                            approved: !current.approved,
                            rememberForSession: current.rememberForSession
                        )
                    } label: {
                        Text(current.approved
                             ? LocalizedStringKey("agent.approval.decision_allow")
                             : LocalizedStringKey("agent.approval.decision_reject"))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(current.approved ? AppColors.primaryBlue : AppColors.overviewExpense)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(
                                (current.approved ? AppColors.primaryBlue : AppColors.overviewExpense)
                                    .opacity(0.12),
                                in: Capsule()
                            )
                    }
                    .buttonStyle(.plain)
                }
            }

            if !tool.riskDescription.isEmpty {
                Text(verbatim: tool.riskDescription)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if !tool.orderedInputPairs.isEmpty {
                VStack(alignment: .leading, spacing: 3) {
                    ForEach(tool.orderedInputPairs.prefix(4), id: \.key) { pair in
                        Text(verbatim: "\(pair.key): \(pair.value)")
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }
                .padding(.top, 2)
            }

            if tool.toolName != "capture_screen" {
                Toggle(isOn: Binding(
                    get: { current.rememberForSession },
                    set: { remember in
                        selections[tool.toolCallId] = AgentPermissionDecisionInput(
                            toolCallId: tool.toolCallId,
                            approved: current.approved,
                            rememberForSession: remember
                        )
                    }
                )) {
                    Text("agent.approval.remember_session")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .tint(AppColors.primaryBlue)
            }
        }
    }
}
