import SwiftUI

/// 与桌面端 `VendorIcon.tsx` 保持一致的厂商图标展示（匹配 `provider.type`）。
struct AgentVendorIconView: View {
    let vendor: String?
    let modelName: String?
    var size: CGFloat = 16

    private var vendorKey: String {
        let v = (vendor ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if !v.isEmpty { return v }
        let m = (modelName ?? "").lowercased()
        if m.contains("deepseek") { return "deepseek" }
        if m.contains("qwen") || m.contains("qwq") { return "dashscope" }
        if m.contains("claude") { return "anthropic" }
        if m.contains("gemini") { return "gemini" }
        if m.contains("gpt") || m.hasPrefix("o1") || m.hasPrefix("o3") || m.hasPrefix("o4") {
            return "openai"
        }
        if m.contains("llama") || m.contains("ollama") { return "ollama" }
        return ""
    }

    private var assetSpec: (name: String, isTemplate: Bool)? {
        switch vendorKey {
        case "deepseek": return ("VendorDeepseek", false)
        case "dashscope", "qwen": return ("VendorDashscope", false)
        case "gemini": return ("VendorGemini", false)
        case "custom": return ("VendorCustom", false)
        case "anthropic": return ("VendorAnthropic", true)
        case "openai": return ("VendorOpenai", true)
        case "ollama": return ("VendorOllama", true)
        default: return nil
        }
    }

    var body: some View {
        if let spec = assetSpec {
            if spec.isTemplate {
                Image(spec.name)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
                    .foregroundStyle(.primary)
                    .frame(width: size, height: size)
            } else {
                Image(spec.name)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
                    .frame(width: size, height: size)
            }
        } else {
            Image(systemName: "square")
                .font(.system(size: size * 0.85, weight: .regular))
                .foregroundStyle(.secondary)
                .frame(width: size, height: size)
        }
    }
}
