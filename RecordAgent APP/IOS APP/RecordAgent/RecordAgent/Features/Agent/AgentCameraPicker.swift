import SwiftUI
import UIKit

/// 包装系统相机拍照能力，仅在设备支持相机时展示。
struct AgentCameraPicker: UIViewControllerRepresentable {
    let onImageCaptured: (Data?) -> Void
    let onCancel: () -> Void

    static var isCameraAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onImageCaptured: onImageCaptured, onCancel: onCancel)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.allowsEditing = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let onImageCaptured: (Data?) -> Void
        let onCancel: () -> Void

        init(onImageCaptured: @escaping (Data?) -> Void, onCancel: @escaping () -> Void) {
            self.onImageCaptured = onImageCaptured
            self.onCancel = onCancel
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            let image = info[.originalImage] as? UIImage
            let jpegData = image.flatMap { AgentImageDataNormalizer.jpegData(from: $0) }
            onImageCaptured(jpegData)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onCancel()
        }
    }
}

/// 将相册或相机图片转为满足后端文件头契约（PNG/JPEG/WebP/GIF 且 ≤ 10 MiB）的字节流。
enum AgentImageDataNormalizer {
    static func normalize(rawData: Data) -> Data? {
        if let _ = AgentImageValidator.detectMediaType(in: rawData),
           rawData.count <= AgentImageValidator.maxSingleBytes {
            return rawData
        }
        guard let image = UIImage(data: rawData) else {
            return rawData
        }
        return jpegData(from: image) ?? rawData
    }

    static func jpegData(from image: UIImage) -> Data? {
        let qualities: [CGFloat] = [0.88, 0.75, 0.6, 0.45]
        for quality in qualities {
            if let data = image.jpegData(compressionQuality: quality),
               data.count <= AgentImageValidator.maxSingleBytes {
                return data
            }
        }
        return image.jpegData(compressionQuality: 0.35)
    }
}
