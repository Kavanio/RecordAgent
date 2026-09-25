import SwiftUI

/// 统一设计规范的主操作胶囊按钮
public struct AppButton: View {
    private let titleKey: LocalizedStringKey?
    private let titleString: String?
    let iconName: String?
    let isLoading: Bool
    let isEnabled: Bool
    let action: () -> Void

    /// 支持本地化 Key 的构造方法（推荐）
    public init(
        _ titleKey: LocalizedStringKey,
        iconName: String? = "arrow.right",
        isLoading: Bool = false,
        isEnabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.titleKey = titleKey
        self.titleString = nil
        self.iconName = iconName
        self.isLoading = isLoading
        self.isEnabled = isEnabled
        self.action = action
    }

    /// 兼容直接传入 String 字符串的构造方法
    public init(
        title: String,
        iconName: String? = "arrow.right",
        isLoading: Bool = false,
        isEnabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.titleKey = nil
        self.titleString = title
        self.iconName = iconName
        self.isLoading = isLoading
        self.isEnabled = isEnabled
        self.action = action
    }

    public var body: some View {
        Button(action: {
            guard isEnabled && !isLoading else { return }
            let generator = UIImpactFeedbackGenerator(style: .medium)
            generator.impactOccurred()
            action()
        }) {
            ZStack {
                // 加载中菊花状态
                if isLoading {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        .scaleEffect(1.1)
                } else {
                    HStack(spacing: 8) {
                        Spacer()
                        if let titleKey = titleKey {
                            Text(titleKey)
                                .font(.system(size: 16.5, weight: .medium))
                                .foregroundColor(.white)
                                .lineLimit(1)
                                .minimumScaleFactor(0.85)
                        } else if let titleString = titleString {
                            Text(titleString)
                                .font(.system(size: 16.5, weight: .medium))
                                .foregroundColor(.white)
                                .lineLimit(1)
                                .minimumScaleFactor(0.85)
                        }
                        if let iconName = iconName {
                            Image(systemName: iconName)
                                .font(.system(size: 15, weight: .medium))
                                .foregroundColor(.white)
                        }
                        Spacer()
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(
                isEnabled ? AppColors.primaryBlue : AppColors.primaryBlue.opacity(0.4)
            )
            .clipShape(Capsule())
            .shadow(color: AppColors.primaryBlue.opacity(isEnabled ? 0.22 : 0.0), radius: 8, x: 0, y: 4)
        }
        .disabled(!isEnabled || isLoading)
        .buttonStyle(ScaleButtonStyle())
    }
}

/// 点击缩放触感按钮样式
private struct ScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1.0)
            .animation(.easeInOut(duration: 0.12), value: configuration.isPressed)
    }
}
