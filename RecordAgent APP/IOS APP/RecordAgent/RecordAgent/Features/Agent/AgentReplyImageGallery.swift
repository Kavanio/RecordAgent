import ImageIO
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 通过鉴权读图，点击缩略图后以居中模态框展示并支持缩放、导出和分享。
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
        .background {
            CenteredModalPresenter(item: $preview) { item in
                PreviewModalView(item: item) {
                    preview = nil
                }
            }
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

    /// 通过透明全屏淡入承载居中模态框，避免系统 sheet 自下而上的抽屉弹出动画。
    private struct CenteredModalPresenter<Item: Identifiable, ModalContent: View>: UIViewControllerRepresentable {
        @Binding var item: Item?
        @Environment(\.accessibilityReduceMotion) private var reduceMotion
        let content: (Item) -> ModalContent

        func makeCoordinator() -> Coordinator {
            Coordinator(parent: self)
        }

        func makeUIViewController(context: Context) -> UIViewController {
            let host = UIViewController()
            host.view.backgroundColor = .clear
            host.view.isUserInteractionEnabled = false
            return host
        }

        func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
            context.coordinator.parent = self
            context.coordinator.syncPresentation(from: uiViewController, item: item, animated: !reduceMotion)
        }

        static func dismantleUIViewController(_ uiViewController: UIViewController, coordinator: Coordinator) {
            coordinator.dismissIfNeeded(animated: false)
        }

        final class Coordinator {
            var parent: CenteredModalPresenter
            private var presentedController: UIHostingController<ModalContent>?
            private var presentedId: Item.ID?

            init(parent: CenteredModalPresenter) {
                self.parent = parent
            }

            func syncPresentation(from host: UIViewController, item: Item?, animated: Bool) {
                if let item {
                    if let presentedController, presentedId == item.id {
                        presentedController.rootView = parent.content(item)
                        return
                    }
                    dismissIfNeeded(animated: false)
                    guard let presenter = topViewController(from: host) else { return }
                    let controller = UIHostingController(rootView: parent.content(item))
                    controller.modalPresentationStyle = .overFullScreen
                    controller.modalTransitionStyle = .crossDissolve
                    controller.view.backgroundColor = .clear
                    presentedController = controller
                    presentedId = item.id
                    presenter.present(controller, animated: animated)
                } else {
                    dismissIfNeeded(animated: animated)
                }
            }

            func dismissIfNeeded(animated: Bool) {
                guard let controller = presentedController else { return }
                presentedController = nil
                presentedId = nil
                if controller.presentingViewController != nil {
                    controller.dismiss(animated: animated)
                }
            }

            private func topViewController(from host: UIViewController) -> UIViewController? {
                var current = host.view.window?.rootViewController ?? host
                while let next = current.presentedViewController, !next.isBeingDismissed {
                    current = next
                }
                return current
            }
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

    private struct PreviewModalView: View {
        let item: Preview
        let onClose: () -> Void
        @State private var zoom = 1.0
        @GestureState private var pinch = 1.0
        @State private var exporting = false
        @State private var exportFailed = false

        var body: some View {
            GeometryReader { outer in
                let maxModalWidth = min(outer.size.width - 32, 520.0)
                let maxImageHeight = max(240.0, outer.size.height * 0.62)
                let imageRatio = item.image.size.height > 0 ? (item.image.size.width / item.image.size.height) : 1.0
                let fittedHeight = min(maxImageHeight, max(180.0, maxModalWidth / max(imageRatio, 0.2)))

                ZStack {
                    Color.black.opacity(0.62)
                        .ignoresSafeArea()
                        .onTapGesture(perform: onClose)

                    VStack(spacing: 0) {
                        HStack(spacing: 12) {
                            Button(action: onClose) {
                                Image(systemName: "xmark")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                    .frame(width: 32, height: 32)
                                    .background(Color(uiColor: .secondarySystemBackground), in: Circle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("agent.image.reply.close")

                            Text(verbatim: item.attachment.title)
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                                .truncationMode(.tail)
                                .frame(maxWidth: .infinity, alignment: .leading)

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
                                    .font(.title3)
                                    .foregroundStyle(.primary)
                                    .frame(width: 32, height: 32)
                            }
                            .accessibilityLabel("agent.image.reply.actions")
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)

                        Divider()
                            .opacity(0.45)

                        let magnification = min(5, max(1, zoom * pinch))
                        ScrollView([.horizontal, .vertical]) {
                            Image(uiImage: item.image)
                                .resizable()
                                .scaledToFit()
                                .frame(width: maxModalWidth * magnification,
                                       height: fittedHeight * magnification)
                                .onTapGesture(count: 2) { zoom = zoom > 1 ? 1 : 2 }
                                .gesture(MagnifyGesture()
                                    .updating($pinch) { value, state, _ in state = value.magnification }
                                    .onEnded { value in zoom = min(5, max(1, zoom * value.magnification)) })
                                .accessibilityLabel(Text(verbatim: item.attachment.title))
                        }
                        .scrollIndicators(.hidden)
                        .frame(width: maxModalWidth, height: fittedHeight)
                        .background(Color(uiColor: .secondarySystemGroupedBackground).opacity(0.4))
                    }
                    .frame(width: maxModalWidth)
                    .background(Color(uiColor: .systemBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .strokeBorder(Color(uiColor: .separator).opacity(0.25), lineWidth: 0.5)
                    }
                    .shadow(color: .black.opacity(0.22), radius: 24, y: 10)
                    .padding(.horizontal, 16)
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
