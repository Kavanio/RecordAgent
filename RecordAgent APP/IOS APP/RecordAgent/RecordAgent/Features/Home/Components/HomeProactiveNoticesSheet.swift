import SwiftUI

/// 首页右上角铃铛弹出的主动提醒与问候列表，点击条目可直达对应对话或任务工作区。
struct HomeProactiveNoticesSheet: View {
    @ObservedObject var pushCenter: ProactiveNotificationCenter
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale

    var body: some View {
        NavigationStack {
            Group {
                if pushCenter.recentNotices.isEmpty {
                    ContentUnavailableView {
                        Label("home.notifications.empty.title", systemImage: "bell.slash")
                    } description: {
                        Text("home.notifications.empty.description")
                    }
                } else {
                    List {
                        ForEach(pushCenter.recentNotices) { notice in
                            Button {
                                pushCenter.openNoticeRoute(notice)
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    HStack(spacing: 8) {
                                        Text(notice.parsedCategory.titleKey)
                                            .font(.caption2.weight(.medium))
                                            .foregroundStyle(AppColors.primaryBlue)
                                        Spacer()
                                        Text(notice.createdAt.formatted(
                                            .dateTime.month().day().hour().minute().locale(locale)
                                        ))
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                    }
                                    Text(verbatim: notice.title)
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundStyle(.primary)
                                    Text(verbatim: notice.body)
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(3)
                                }
                                .padding(.vertical, 4)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("home.notifications.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.close") { dismiss() }
                }
            }
            .task {
                await pushCenter.refreshNoticesHistory()
            }
        }
    }
}
