import SwiftUI

/// 根据模型名称或厂商标识解析对应的品牌图标，保持与桌面端一致的辨识度。
struct AgentVendorIconView: View {
    let vendor: String?
    let modelName: String?
    var size: CGFloat = 18

    private var assetName: String? {
        let model = (modelName ?? "").lowercased()
        if model.contains("deepseek") { return "VendorDeepseek" }
        if model.contains("qwen") || model.contains("qwq") { return "VendorDashscope" }
        if model.contains("claude") { return "VendorAnthropic" }
        if model.contains("gemini") { return "VendorGemini" }
        if model.contains("gpt") || model.hasPrefix("o1") || model.hasPrefix("o3") || model.hasPrefix("o4") {
            return "VendorOpenai"
        }
        if model.contains("llama") || model.contains("ollama") { return "VendorOllama" }

        switch (vendor ?? "").lowercased() {
        case "deepseek": return "VendorDeepseek"
        case "dashscope": return "VendorDashscope"
        case "anthropic": return "VendorAnthropic"
        case "gemini": return "VendorGemini"
        case "openai": return "VendorOpenai"
        case "ollama": return "VendorOllama"
        default: return nil
        }
    }

    private var isGLM: Bool {
        let model = (modelName ?? "").lowercased()
        let v = (vendor ?? "").lowercased()
        return model.contains("glm") || v.contains("zhipu") || v.contains("glm")
    }

    var body: some View {
        if let assetName {
            Image(assetName)
                .resizable()
                .interpolation(.high)
                .scaledToFill()
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: size * 0.25, style: .continuous))
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: size * 0.25, style: .continuous)
                    .fill(isGLM ? Color(red: 0.22, green: 0.45, blue: 0.96) : Color(uiColor: .tertiarySystemFill))
                Image(systemName: isGLM ? "sparkles" : "cpu")
                    .font(.system(size: size * 0.54, weight: .semibold))
                    .foregroundStyle(isGLM ? .white : .secondary)
            }
            .frame(width: size, height: size)
        }
    }
}
