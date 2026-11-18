import ImageIO
import SwiftUI
import UniformTypeIdentifiers

/// 通过鉴权读图，点击缩略图后支持放大、导出和分享。
struct AgentReplyImageGallery: View {
    let attachments: [AgentReplyImage]
    let load: (String) async throws -> Data
    @State private var preview: Preview?

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(attachments) { attachment in
                    Thumbnail(attachment: attachment, load: load) { image, data in
                        preview = Preview(attachment: attachment, image: image, data: data)
                    }
                }
            }
        }
        .scrollIndicators(.hidden)
        .sheet(item: $preview) { item in
            PreviewView(item: item)
        }
    }

    private struct Preview: Identifiable {
        var id: String { attachment.id }
        let attachment: AgentReplyImage
        let image: UIImage
        let data: Data

        var contentType: UTType {
            guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                  let identifier = CGImageSourceGetType(source) else { return .image }
            return UTType(identifier as String) ?? .image
        }
    }

    private struct Thumbnail: View {
        let attachment: AgentReplyImage
        let load: (String) async throws -> Data
        let open: (UIImage, Data) -> Void
        @State private var loaded: (image: UIImage, data: Data)?
        @State private var failed = false
        @State private var attempt = 0

        var body: some View {
            Group {
                if let loaded {
                    Button {
                        open(loaded.image, loaded.data)
                    } label: {
                        Image(uiImage: loaded.image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 224, maxHeight: 172)
                            .clipShape(.rect(cornerRadius: 10))
                            .accessibilityLabel(Text(verbatim: attachment.title))
                    }
                    .buttonStyle(.plain)
                } else if failed {
                    Button("agent.image.reply.retry") { attempt += 1 }
                        .font(.caption)
                        .frame(minHeight: 44)
                } else {
                    ProgressView()
                        .frame(width: 88, height: 64)
                        .accessibilityLabel("agent.image.reply.loading")
                }
            }
            .task(id: attempt) {
                guard loaded == nil else { return }
                failed = false
                do {
                    let data = try await load(attachment.fileId)
                    try Task.checkCancellation()
                    guard let image = UIImage(data: data) else { throw AgentReplyImageError.unavailable }
                    loaded = (image, data)
                } catch {
                    if !Task.isCancelled { failed = true }
                }
            }
        }
    }

    private struct PreviewView: View {
        let item: Preview
        @Environment(\.dismiss) private var dismiss
        @State private var zoom = 1.0
        @GestureState private var pinch = 1.0
        @State private var exporting = false
        @State private var exportFailed = false

        var body: some View {
            NavigationStack {
                GeometryReader { geometry in
                    let magnification = min(5, max(1, zoom * pinch))
                    ScrollView([.horizontal, .vertical]) {
                        Image(uiImage: item.image)
                            .resizable()
                            .scaledToFit()
                            .frame(width: geometry.size.width * magnification,
                                   height: geometry.size.height * magnification)
                            .onTapGesture(count: 2) { zoom = zoom > 1 ? 1 : 2 }
                            .gesture(MagnifyGesture()
                                .updating($pinch) { value, state, _ in state = value.magnification }
                                .onEnded { value in zoom = min(5, max(1, zoom * value.magnification)) })
                            .accessibilityLabel(Text(verbatim: item.attachment.title))
                    }
                }
                .navigationTitle(Text(verbatim: item.attachment.title))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("agent.image.reply.close") { dismiss() }
                    }
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Button("agent.image.reply.save", systemImage: "square.and.arrow.down") {
                                exporting = true
                            }
                            ShareLink(item: Image(uiImage: item.image),
                                      preview: SharePreview(item.attachment.title, image: Image(uiImage: item.image)))
                            if let source = item.attachment.source {
                                Link("agent.image.reply.source", destination: source)
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                                .accessibilityLabel("agent.image.reply.actions")
                        }
                    }
                }
                .fileExporter(isPresented: $exporting, document: ImageFile(data: item.data),
                              contentType: item.contentType, defaultFilename: "chat-image") { result in
                    if case .failure = result { exportFailed = true }
                }
                .alert("agent.image.reply.save_error", isPresented: $exportFailed) {
                    Button("agent.image.reply.close", role: .cancel) {}
                }
            }
        }
    }

    private struct ImageFile: FileDocument {
        static let readableContentTypes: [UTType] = [.image]
        let data: Data

        init(data: Data) { self.data = data }
        init(configuration: ReadConfiguration) throws {
            guard let data = configuration.file.regularFileContents else {
                throw AgentReplyImageError.unavailable
            }
            self.data = data
        }

        func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
            FileWrapper(regularFileWithContents: data)
        }
    }
}
