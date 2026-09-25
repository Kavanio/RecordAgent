import SwiftUI

/// 隐私政策与用户协议弹窗展示视图
public struct AgreementSheetView: View {
    let type: AgreementType
    @Environment(\.dismiss) private var dismiss

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if type == .privacy {
                        Text("RecordAgent 隐私政策")
                            .font(.title2.bold())
                            .foregroundColor(AppColors.textPrimary)

                        Text("生效日期：2026年9月\n\n我们非常重视您的隐私。RecordAgent 是面向个人的自主数据记录与管理工具，我们坚持以下核心隐私承诺：\n\n1. 数据所有权：您的待办、日程、财务流水、学习记录与资料手记完全归属于您个人，除您主动配置的服务端同步外，不会向任何第三方商业实体共享或出售。\n\n2. 敏感信息安全：所有访问令牌（Token）与敏感认证凭据均通过 Apple Keychain 进行硬件级加密保护。\n\n3. 权限最小化：系统通知、相册选择与本地存储仅在您主动触发相关功能时按需申请。")
                            .font(.system(size: 15))
                            .lineSpacing(6)
                            .foregroundColor(AppColors.textSecondary)
                    } else {
                        Text("RecordAgent 用户服务协议")
                            .font(.title2.bold())
                            .foregroundColor(AppColors.textPrimary)

                        Text("欢迎使用 RecordAgent！\n\n请在注册或使用前仔细阅读本协议：\n\n1. 账号使用规范：您应妥善保管自己的邮箱与验证码凭据，一个账号可跨 iPhone 与桌面端保持同步。\n\n2. 服务持续性：本应用提供端侧离线优先与多端云同步功能，请定期备份您的重要资料与数据。\n\n3. 免责声明：RecordAgent 提供的自动提醒与数据统计仅供您日常习惯培养与个人规划参考。")
                            .font(.system(size: 15))
                            .lineSpacing(6)
                            .foregroundColor(AppColors.textSecondary)
                    }
                }
                .padding(24)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") {
                        dismiss()
                    }
                    .foregroundColor(AppColors.primaryBlue)
                }
            }
        }
    }
}
