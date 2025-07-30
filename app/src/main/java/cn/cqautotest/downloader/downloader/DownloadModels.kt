package cn.cqautotest.downloader.downloader

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class DownloadStatus {
    PENDING,       // 等待下载
    DOWNLOADING,   // 正在下载
    PAUSED,        // 已暂停 (可能由用户操作或网络断开导致)
    COMPLETED,     // 下载完成
    FAILED,        // 下载失败
    CANCELLED      // 已取消
}

@Entity(tableName = "download_tasks")
data class DownloadTask(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val filePath: String,
    val fileName: String,
    var downloadedBytes: Long = 0L,
    var totalBytes: Long = 0L,
    var status: DownloadStatus = DownloadStatus.PENDING,
    var eTag: String? = null,
    var lastModified: String? = null,
    var isPausedByNetwork: Boolean = false,
    var errorDetails: String? = null,
    var createdAt: Long = System.currentTimeMillis() // 新增字段，并提供默认值
)

data class DownloadProgress(
    val taskId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: DownloadStatus,
    val error: Throwable? = null // 可选的错误信息
)