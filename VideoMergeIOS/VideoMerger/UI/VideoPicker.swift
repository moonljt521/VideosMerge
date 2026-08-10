//
//  VideoPicker.swift
//  VideoMerger
//
//  用 PHPickerViewController（UIKit delegate）替代 SwiftUI PhotosPicker
//  目的：拿到 assetIdentifier，配合 PHAssetResourceManager 流式写盘
//  （避免 SwiftUI PhotosPicker 的 loadTransferable 把视频读入内存撑爆）
//

import SwiftUI
import PhotosUI
import Photos

struct VideoPicker: UIViewControllerRepresentable {
    let maxSelection: Int
    let onPicked: ([String]) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration(photoLibrary: PHPhotoLibrary.shared())
        config.filter = .videos
        config.selectionLimit = maxSelection
        // 关键：让每个 result 都带 assetIdentifier（用于查 PHAsset）
        config.preferredAssetRepresentationMode = .current
        let controller = PHPickerViewController(configuration: config)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPicked: onPicked)
    }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPicked: ([String]) -> Void

        init(onPicked: @escaping ([String]) -> Void) {
            self.onPicked = onPicked
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            let ids = results.compactMap { $0.assetIdentifier }
            if !ids.isEmpty {
                onPicked(ids)
            }
        }
    }
}
