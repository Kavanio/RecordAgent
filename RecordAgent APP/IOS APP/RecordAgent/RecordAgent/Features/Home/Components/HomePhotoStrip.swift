import SwiftUI

/// 只在真实条目带有开发图片资源引用时呈现封面，不用装饰图冒充用户附件。
struct HomePhotoStrip: View {
    let names: [String]
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                ForEach(Array(names.prefix(3).enumerated()), id: \.offset) { _, name in
                    GeometryReader { geometry in
                        Image(name).resizable().scaledToFill()
                            .frame(width: geometry.size.width, height: 52).clipped()
                    }
                    .frame(maxWidth: 90)
                    .frame(height: 52)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .accessibilityHidden(true)
                }
            }
            Text("calendar.photos \(names.count)")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}
