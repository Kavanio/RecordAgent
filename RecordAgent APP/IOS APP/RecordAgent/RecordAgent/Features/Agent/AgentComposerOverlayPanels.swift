import SwiftUI

/// 输入框左侧「+」号点击后向上弹出的一级分类选择浮层（参考图 2）。
struct AgentPlusCategoryMenu: View {
    let imageCount: Int
    let recordCount: Int
    let onTakePhoto: () -> Void
    let onPickPhotos: () -> Void
    let onOpenRecords: () -> Void

    private var imagesFull: Bool {
        imageCount >= AgentImageValidator.maxCount
    }

    private var recordsFull: Bool {
        recordCount >= AgentChatViewModel.maxRecordReferences
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            categoryRow(
                icon: "camera",
                title: "agent.plus_menu.camera",
                trailingText: imagesFull ? "\(imageCount)/\(AgentImageValidator.maxCount)" : nil,
                disabled: imagesFull,
                action: onTakePhoto
            )

            categoryRow(
                icon: "photo.on.rectangle",
                title: "agent.plus_menu.photos",
                trailingText: imageCount > 0 ? "\(imageCount)/\(AgentImageValidator.maxCount)" : nil,
                disabled: imagesFull,
                action: onPickPhotos
            )

            categoryRow(
                icon: "book.pages",
                title: "agent.plus_menu.records",
                trailingText: recordCount > 0 ? "\(recordCount)/\(AgentChatViewModel.maxRecordReferences)" : nil,
                disabled: recordsFull,
                action: onOpenRecords
            )
        }
        .padding(8)
        .frame(width: 216, alignment: .leading)
        .background(
            Color(uiColor: .systemBackground),
            in: RoundedRectangle(cornerRadius: 22, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(Color(uiColor: .separator).opacity(0.24), lineWidth: 0.5)
        }
        .shadow(color: Color.black.opacity(0.12), radius: 18, x: 0, y: 6)
    }

    private func categoryRow(
        icon: String,
        title: LocalizedStringKey,
        trailingText: String?,
        disabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(disabled ? .tertiary : .primary)
                    .frame(width: 34, height: 34)
                    .background(Color(uiColor: .secondarySystemBackground), in: Circle())

                Text(title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(disabled ? .tertiary : .primary)

                Spacer(minLength: 4)

                if let trailingText {
                    Text(verbatim: trailingText)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
    }
}

/// 点击「资料」后在输入框上方向上展开的可搜索资料选择面板（参考图 3）。
struct AgentRecordReferencePanel: View {
    @ObservedObject var viewModel: AgentChatViewModel
    let onClose: () -> Void
    @FocusState private var isSearchFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            headerBar
            Divider().opacity(0.4)
            listBody
        }
        .background(
            Color(uiColor: .systemBackground),
            in: RoundedRectangle(cornerRadius: 22, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(Color(uiColor: .separator).opacity(0.24), lineWidth: 0.5)
        }
        .shadow(color: Color.black.opacity(0.12), radius: 18, x: 0, y: 6)
    }

    private var headerBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            TextField(
                "agent.reference.search_placeholder",
                text: Binding(
                    get: { viewModel.recordSearchQuery },
                    set: { viewModel.scheduleRecordSearch($0) }
                )
            )
            .font(.subheadline)
            .focused($isSearchFocused)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()

            if !viewModel.recordSearchQuery.isEmpty {
                Button {
                    viewModel.scheduleRecordSearch("", immediate: true)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
            }

            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 28, height: 28)
                    .background(Color(uiColor: .secondarySystemBackground), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("workspace.cancel")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    @ViewBuilder
    private var listBody: some View {
        if viewModel.isLoadingRecordReferences && viewModel.recordReferenceOptions.isEmpty {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("agent.reference.loading")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 28)
        } else if let error = viewModel.recordReferenceError, viewModel.recordReferenceOptions.isEmpty {
            VStack(spacing: 8) {
                Text(verbatim: error)
                    .font(.caption)
                    .foregroundStyle(AppColors.overviewExpense)
                    .multilineTextAlignment(.center)
                Button("common.retry") {
                    viewModel.scheduleRecordSearch(viewModel.recordSearchQuery, immediate: true)
                }
                .font(.caption.weight(.medium))
                .foregroundStyle(AppColors.primaryBlue)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 22)
            .padding(.horizontal, 16)
        } else if viewModel.recordReferenceOptions.isEmpty {
            Text("agent.reference.empty")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 28)
        } else {
            ScrollView {
                LazyVStack(spacing: 2) {
                    ForEach(viewModel.recordReferenceOptions) { option in
                        recordRow(option)
                    }

                    if viewModel.recordReferencesHasMore {
                        Button {
                            Task { await viewModel.loadMoreRecordReferences() }
                        } label: {
                            HStack(spacing: 6) {
                                if viewModel.isLoadingMoreRecordReferences {
                                    ProgressView().controlSize(.mini)
                                }
                                Text(viewModel.isLoadingMoreRecordReferences
                                    ? "agent.reference.loading_more"
                                    : "agent.reference.load_more")
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(AppColors.primaryBlue)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                        }
                        .buttonStyle(.plain)
                        .disabled(viewModel.isLoadingMoreRecordReferences)
                    }
                }
                .padding(8)
            }
            .frame(maxHeight: 248)
        }
    }

    private func recordRow(_ option: AgentRecordReferenceOption) -> some View {
        let isSelected = viewModel.selectedRecordReferences.contains(where: { $0.id == option.id })
        let style = Self.presentation(for: option.type)

        return Button {
            viewModel.toggleRecordReference(option)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: style.icon)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(style.color)
                    .frame(width: 30, height: 30)
                    .background(style.color.opacity(0.12), in: RoundedRectangle(cornerRadius: 8, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: option.displayTitle)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)

                    if !option.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text(verbatim: option.summary)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }

                Spacer(minLength: 6)

                VStack(alignment: .trailing, spacing: 2) {
                    Text(LocalizedStringKey(style.labelKey))
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(.secondary)
                    Text(verbatim: option.recordDate)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.tertiary)
                }

                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.subheadline)
                        .foregroundStyle(AppColors.primaryBlue)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(
                isSelected ? AppColors.primaryBlue.opacity(0.08) : Color.clear,
                in: RoundedRectangle(cornerRadius: 12, style: .continuous)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    static func presentation(for type: AgentRecordType) -> (labelKey: String, icon: String, color: Color) {
        switch type {
        case .quick:
            return ("agent.reference.type.quick", "note.text", Color(red: 0.35, green: 0.40, blue: 0.47))
        case .learning:
            return ("agent.reference.type.learning", "graduationcap", Color(red: 0.46, green: 0.34, blue: 0.71))
        case .weeklyReview:
            return ("agent.reference.type.weekly_review", "calendar.badge.clock", Color(red: 0.33, green: 0.39, blue: 0.73))
        case .reading:
            return ("agent.reference.type.reading", "book", AppColors.primaryBlue)
        case .journal:
            return ("agent.reference.type.journal", "doc.text", AppColors.overviewStudy)
        case .unknown:
            return ("agent.reference.type.generic", "doc.text", AppColors.primaryBlue)
        }
    }
}

/// 输入框顶部已选图片缩略图与引用资料标签条。
struct AgentComposerAttachmentsBar: View {
    let images: [AgentChatImageAttachment]
    let records: [AgentRecordReferenceOption]
    let isProcessingImages: Bool
    let onRemoveImage: (UUID) -> Void
    let onRemoveRecord: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if isProcessingImages {
                    ProgressView()
                        .controlSize(.small)
                        .frame(width: 44, height: 44)
                        .background(Color(uiColor: .tertiarySystemFill), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                ForEach(images) { item in
                    imageThumb(item)
                }

                ForEach(records) { record in
                    recordChip(record)
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 10)
            .padding(.bottom, 2)
        }
    }

    private func imageThumb(_ item: AgentChatImageAttachment) -> some View {
        ZStack(alignment: .topTrailing) {
            Group {
                if let uiImage = UIImage(data: item.data) {
                    Image(uiImage: uiImage)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: "photo")
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Color(uiColor: .tertiarySystemFill))
                }
            }
            .frame(width: 54, height: 54)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

            Button {
                onRemoveImage(item.id)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.caption)
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, Color.black.opacity(0.65))
            }
            .buttonStyle(.plain)
            .offset(x: 4, y: -4)
            .accessibilityLabel("agent.image.remove")
        }
    }

    private func recordChip(_ record: AgentRecordReferenceOption) -> some View {
        let style = AgentRecordReferencePanel.presentation(for: record.type)
        return HStack(spacing: 6) {
            Image(systemName: style.icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(style.color)
            Text(verbatim: record.displayTitle)
                .font(.caption.weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .frame(maxWidth: 140, alignment: .leading)
            Button {
                onRemoveRecord(record.id)
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 18, height: 18)
                    .background(Color(uiColor: .tertiarySystemFill), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("agent.reference.remove")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(
            style.color.opacity(0.10),
            in: Capsule()
        )
        .overlay {
            Capsule()
                .strokeBorder(style.color.opacity(0.25), lineWidth: 0.5)
        }
    }
}

/// 回合结束时展示在助手回复底部的引用资料卡片条（参考主流 Agent 来源卡片交互）。
struct AgentCitedReferencesStrip: View {
    let references: [AgentCitedReference]
    let onSelect: (AgentCitedReference) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 5) {
                Image(systemName: "book.pages")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(String(localized: "agent.reference.sources_count \(references.count)"))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(references.enumerated()), id: \.element.id) { index, item in
                        sourceCard(item, fallbackIndex: index + 1)
                    }
                }
                .padding(.vertical, 1)
            }
        }
        .padding(.top, 4)
    }

    private func sourceCard(_ item: AgentCitedReference, fallbackIndex: Int) -> some View {
        let style = AgentRecordReferencePanel.presentation(for: item.type)
        let badgeNumber = item.citationNumber ?? fallbackIndex

        return Button {
            onSelect(item)
        } label: {
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 5) {
                    Text(verbatim: "\(badgeNumber)")
                        .font(.system(size: 10, weight: .bold).monospacedDigit())
                        .foregroundStyle(style.color)
                        .frame(width: 16, height: 16)
                        .background(style.color.opacity(0.14), in: Circle())

                    Image(systemName: style.icon)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(style.color)

                    Text(LocalizedStringKey(style.labelKey))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)

                    Spacer(minLength: 4)

                    if let date = item.recordDate, !date.isEmpty {
                        Text(verbatim: date)
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.tertiary)
                    }
                }

                Text(verbatim: item.title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                if let snippet = item.snippet, !snippet.isEmpty {
                    Text(verbatim: snippet)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 9)
            .frame(width: 184, alignment: .leading)
            .background(
                Color(uiColor: .secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(Color(uiColor: .separator).opacity(0.22), lineWidth: 0.5)
            }
            .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

/// 点击引用资料卡片后展示的资料正文/摘录预览面板。
struct AgentReferencedRecordSheet: View {
    let citation: AgentCitedReference
    let loadDetail: (AgentCitedReference) async -> Result<AgentRecordDetail, Error>
    let onClose: () -> Void

    @State private var isLoading = true
    @State private var detail: AgentRecordDetail?
    @State private var errorMessage: String?

    var body: some View {
        let resolvedType = detail?.type ?? citation.type
        let style = AgentRecordReferencePanel.presentation(for: resolvedType)

        VStack(spacing: 0) {
            WorkspaceSheetHeader(
                title: "agent.reference.detail_title",
                onClose: onClose
            )
            .padding(.horizontal, 24)
            .padding(.top, 26)
            .padding(.bottom, 16)

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 6) {
                            Image(systemName: style.icon)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(style.color)
                            Text(LocalizedStringKey(style.labelKey))
                                .font(.caption.weight(.medium))
                                .foregroundStyle(style.color)
                            if let date = detail?.recordDate ?? citation.recordDate, !date.isEmpty {
                                Text(verbatim: "· \(date)")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }

                        Text(verbatim: detail?.displayTitle ?? citation.title)
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(.primary)

                        if let tags = detail?.tags, !tags.isEmpty {
                            HStack(spacing: 6) {
                                ForEach(tags, id: \.self) { tag in
                                    Text(verbatim: "#\(tag)")
                                        .font(.caption2.weight(.medium))
                                        .foregroundStyle(.secondary)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 3)
                                        .background(Color(uiColor: .tertiarySystemGroupedBackground), in: Capsule())
                                }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(18)
                    .background(
                        Color(uiColor: .secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 20, style: .continuous)
                    )

                    if let snippet = citation.snippet, !snippet.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("agent.reference.detail_snippet")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            Text(verbatim: snippet)
                                .font(.subheadline)
                                .foregroundStyle(.primary)
                                .textSelection(.enabled)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(18)
                        .background(
                            Color(uiColor: .secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 20, style: .continuous)
                        )
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("agent.reference.detail_body")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)

                        if isLoading {
                            HStack(spacing: 8) {
                                ProgressView().controlSize(.small)
                                Text("agent.reference.loading")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 12)
                        } else if let content = detail?.contentText?.trimmingCharacters(in: .whitespacesAndNewlines),
                                  !content.isEmpty {
                            Text(verbatim: content)
                                .font(.body)
                                .lineSpacing(5)
                                .foregroundStyle(.primary)
                                .textSelection(.enabled)
                        } else if let errorMessage {
                            Text(verbatim: errorMessage)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        } else {
                            Text("agent.reference.detail_empty")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(18)
                    .background(
                        Color(uiColor: .secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 20, style: .continuous)
                    )
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 28)
            }
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .tint(AppColors.primaryBlue)
        .workspaceSheet(compact: false)
        .task(id: citation.id) {
            isLoading = true
            let result = await loadDetail(citation)
            isLoading = false
            switch result {
            case .success(let loaded):
                detail = loaded
                errorMessage = nil
            case .failure(let error):
                errorMessage = error.localizedDescription
            }
        }
    }
}

/// 点击已发送聊天图片缩略图时的全屏预览视图。
struct AgentSentImagePreviewSheet: View {
    let attachment: AgentChatImageAttachment
    let onClose: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()

            if let uiImage = UIImage(data: attachment.data) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(12)
            }

            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(Color.white.opacity(0.18), in: Circle())
            }
            .buttonStyle(.plain)
            .padding(.top, 16)
            .padding(.trailing, 18)
            .accessibilityLabel("workspace.cancel")
        }
    }
}
