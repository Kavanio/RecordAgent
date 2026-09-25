import SwiftUI

/// 统一设计规范的主操作胶囊按钮
public struct AppButton: View {
    let title: String
    let iconName: String?
    let isLoading: Bool
    let isEnabled: Bool
    let action: () -> Void

    public init(
        title: String,
        iconName: String? = "arrow.right",
        isLoading: Bool = false,
        isEnabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.title = title
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
                        Text(title)
                            .font(.system(size: 17, weight: .medium))
                            .foregroundColor(.white)
                        if let iconName = iconName {
                            Image(systemName: iconName)
                                .font(.system(size: 16, weight: .semibold))
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
            .shadow(color: AppColors.primaryBlue.opacity(isEnabled ? 0.25 : 0.0), radius: 8, x: 0, y: 4)
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
