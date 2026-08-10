//
//  HistoryView.swift
//  VideoMerger
//
//  历史记录页面
//

import SwiftUI
import AVKit

struct HistoryView: View {
    let history: [HistoryEntry]
    var onItemClick: (HistoryEntry) -> Void
    var onItemDelete: (HistoryEntry) -> Void
    var onBack: () -> Void

    @State private var entryToDelete: HistoryEntry?

    var body: some View {
        VStack(spacing: 0) {
            // 顶栏
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                }
                Text("历史记录 (\(history.count))")
                    .font(.headline)
                    .fontWeight(.bold)
                Spacer()
            }
            .padding()
            .background(Color(.secondarySystemBackground))

            if history.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 60))
                        .foregroundColor(.gray)
                    Text("暂无历史记录")
                        .foregroundColor(.gray)
                        .font(.system(size: 16))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVGrid(
                        columns: [
                            GridItem(.flexible(), spacing: 12),
                            GridItem(.flexible(), spacing: 12)
                        ],
                        spacing: 12
                    ) {
                        ForEach(history) { entry in
                            HistoryItemView(entry: entry,
                                            onClick: { onItemClick(entry) },
                                            onLongPress: { entryToDelete = entry })
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
            }
        }
        .alert("删除记录", isPresented: Binding(
            get: { entryToDelete != nil },
            set: { if !$0 { entryToDelete = nil } }
        )) {
            Button("取消", role: .cancel) { entryToDelete = nil }
            Button("删除", role: .destructive) {
                if let e = entryToDelete {
                    onItemDelete(e)
                    entryToDelete = nil
                }
            }
        } message: {
            Text("确定删除这条历史记录？\n视频文件也会被删除。")
        }
    }
}

private struct HistoryItemView: View {
    let entry: HistoryEntry
    var onClick: () -> Void
    var onLongPress: () -> Void

    @State private var thumbnail: UIImage?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .bottom) {
                if let thumb = thumbnail {
                    Image(uiImage: thumb)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                } else {
                    Color.black
                        .overlay(
                            Image(systemName: "video.fill")
                                .foregroundColor(.gray)
                        )
                }
                // 底部信息
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.mergeTypeDisplay)
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.white)
                    Text(entry.dateString)
                        .font(.system(size: 9))
                        .foregroundColor(.white.opacity(0.8))
                    Text(String(format: "%.1fs", entry.duration))
                        .font(.system(size: 9))
                        .foregroundColor(.white.opacity(0.8))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(6)
                .background(Color.black.opacity(0.6))
            }
            .aspectRatio(0.6, contentMode: .fit)
            .clipped()
            .cornerRadius(10)
            .contentShape(Rectangle())
            .onTapGesture(perform: onClick)
            .onLongPressGesture(minimumDuration: 0.6, perform: onLongPress)
        }
        .onAppear {
            loadThumbnail()
        }
    }

    private func loadThumbnail() {
        if let thumbURL = entry.thumbnailURL,
           FileManager.default.fileExists(atPath: thumbURL.path),
           let img = UIImage(contentsOfFile: thumbURL.path) {
            thumbnail = img
            return
        }
        // 没有缩略图则从视频取首帧
        DispatchQueue.global(qos: .userInitiated).async {
            if let img = MediaUtils.loadThumbnailFromFile(path: entry.fileURL.path) {
                DispatchQueue.main.async { thumbnail = img }
            }
        }
    }
}
