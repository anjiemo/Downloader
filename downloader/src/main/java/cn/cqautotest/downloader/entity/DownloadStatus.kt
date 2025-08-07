package cn.cqautotest.downloader.entity

enum class DownloadStatus {
    PENDING,       // 等待下载
    DOWNLOADING,   // 正在下载
    PAUSED,        // 已暂停 (可能由用户操作或网络断开导致)
    COMPLETED,     // 下载完成
    FAILED,        // 下载失败
    CANCELLED      // 已取消
}