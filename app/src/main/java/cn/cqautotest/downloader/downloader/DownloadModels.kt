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
    var downloadedBytes: Long = 0L,           // 主指针：数据库记录的下载进度
    var totalBytes: Long = 0L,
    var status: DownloadStatus = DownloadStatus.PENDING,
    var eTag: String? = null,
    var lastModified: String? = null,
    var isPausedByNetwork: Boolean = false,
    var errorDetails: String? = null,
    var createdAt: Long = System.currentTimeMillis(),
    val md5Expected: String? = null, // 外部传入
    var md5FromServer: String? = null, // 服务器返回
    
    // 双指针机制字段
    var committedBytes: Long = 0L,             // 副指针：已确认写入文件的字节数
    var lastCommitTime: Long = 0L,             // 最后提交时间
    var fileIntegrityCheck: Boolean = false    // 文件完整性检查标志
)

data class DownloadProgress(
    val taskId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: DownloadStatus,
    val error: Throwable? = null, // 可选的错误信息
    val fileName: String? = null
)

// 文件完整性检查结果
data class FileIntegrityResult(
    val isValid: Boolean,
    val actualSize: Long,
    val expectedSize: Long,
    val corruptionPoint: Long? = null, // 如果文件损坏，记录损坏位置
    val errorMessage: String? = null
)