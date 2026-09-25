import SwiftUI

/// 统一设计规范的表单输入框
public struct AppTextField: View {
    let iconName: String
    private let placeholderKey: LocalizedStringKey?
    private let placeholderString: String?
    @Binding var text: String
    var isSecure: Bool = false
    var keyboardType: UIKeyboardType = .default
    var trailingAction: AnyView? = nil

    @State private var isPasswordVisible: Bool = false
    @FocusState private var isFocused: Bool

    /// 支持本地化 Key 的构造方法（推荐）
    public init(
        iconName: String,
        placeholderKey: LocalizedStringKey,
        text: Binding<String>,
        isSecure: Bool = false,
        keyboardType: UIKeyboardType = .default,
        trailingAction: AnyView? = nil
    ) {
        self.iconName = iconName
        self.placeholderKey = placeholderKey
        self.placeholderString = nil
        self._text = text
        self.isSecure = isSecure
        self.keyboardType = keyboardType
        self.trailingAction = trailingAction
    }

    /// 兼容直接传入 String 的构造方法
    public init(
        iconName: String,
        placeholder: String,
        text: Binding<String>,
        isSecure: Bool = false,
        keyboardType: UIKeyboardType = .default,
        trailingAction: AnyView? = nil
    ) {
        self.iconName = iconName
        self.placeholderKey = nil
        self.placeholderString = placeholder
        self._text = text
        self.isSecure = isSecure
        self.keyboardType = keyboardType
        self.trailingAction = trailingAction
    }

    public var body: some View {
        HStack(spacing: 12) {
            // 前置 SF Symbol 图标
            Image(systemName: iconName)
                .font(.system(size: 16, weight: .regular))
                .foregroundColor(AppColors.iconTint)
                .frame(width: 22, height: 22)

            // 输入区域
            ZStack(alignment: .leading) {
                if text.isEmpty {
                    if let placeholderKey = placeholderKey {
                        Text(placeholderKey)
                            .font(.system(size: 15))
                            .foregroundColor(AppColors.placeholderText)
                    } else if let placeholderString = placeholderString {
                        Text(placeholderString)
                            .font(.system(size: 15))
                            .foregroundColor(AppColors.placeholderText)
                    }
                }

                if isSecure && !isPasswordVisible {
                    SecureField("", text: $text)
                        .font(.system(size: 15))
                        .foregroundColor(AppColors.textPrimary)
                        .focused($isFocused)
                        .textContentType(.password)
                        .autocapitalization(.none)
                } else {
                    TextField("", text: $text)
                        .font(.system(size: 15))
                        .foregroundColor(AppColors.textPrimary)
                        .focused($isFocused)
                        .keyboardType(keyboardType)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                }
            }

            // 尾部操作（密码明暗切换图标或自定义右侧按钮如获取验证码）
            if let trailingAction = trailingAction {
                trailingAction
            } else if isSecure {
                Button {
                    isPasswordVisible.toggle()
                } label: {
                    Image(systemName: isPasswordVisible ? "eye" : "eye.slash")
                        .font(.system(size: 16))
                        .foregroundColor(AppColors.placeholderText)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 52)
        .background(AppColors.inputBackground)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(isFocused ? AppColors.inputBorderFocused : AppColors.inputBorder, lineWidth: 1.0)
        )
        .animation(.easeInOut(duration: 0.15), value: isFocused)
    }
}
