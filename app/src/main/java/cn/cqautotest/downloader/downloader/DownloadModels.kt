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

enum class DownloadMode {
    SINGLE,        // 单线程下载
    CHUNKED        // 分片下载
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
    var fileIntegrityCheck: Boolean = false,   // 文件完整性检查标志
    
    // 分片下载字段
    var downloadMode: DownloadMode = DownloadMode.SINGLE,  // 下载模式
    var chunkSize: Long = 1024 * 1024 * 1024,             // 分片大小，默认1GB
    var maxConcurrentChunks: Int = 3,                      // 最大并发分片数
    var supportsRangeRequests: Boolean = false,            // 服务器是否支持Range请求
    var chunkCount: Int = 0                                 // 总分片数
)

// 分片任务数据类
@Entity(tableName = "download_chunks")
data class DownloadChunk(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,                       // 关联的主任务ID
    val chunkIndex: Int,                      // 分片索引
    val startByte: Long,                      // 起始字节位置
    val endByte: Long,                        // 结束字节位置
    var downloadedBytes: Long = 0L,           // 已下载字节数
    var status: DownloadStatus = DownloadStatus.PENDING,  // 分片状态
    var errorDetails: String? = null,         // 错误详情
    var retryCount: Int = 0,                  // 重试次数
    var lastRetryTime: Long = 0L,             // 最后重试时间
    var createdAt: Long = System.currentTimeMillis()
)

data class DownloadProgress(
    val taskId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: DownloadStatus,
    val error: Throwable? = null, // 可选的错误信息
    val fileName: String? = null,
    val downloadMode: DownloadMode = DownloadMode.SINGLE,
    val chunkProgress: List<ChunkProgress>? = null  // 分片进度信息
)

// 分片进度信息
data class ChunkProgress(
    val chunkIndex: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val progress: Float  // 0.0 - 1.0
)

// 文件完整性检查结果
data class FileIntegrityResult(
    val isValid: Boolean,
    val actualSize: Long,
    val expectedSize: Long,
    val corruptionPoint: Long? = null, // 如果文件损坏，记录损坏位置
    val errorMessage: String? = null
)

// 分片下载配置
data class ChunkedDownloadConfig(
    val enabled: Boolean = false,              // 是否启用分片下载
    val chunkSize: Long = 10 * 1024 * 1024, // 分片大小，默认10MB
    val maxConcurrentChunks: Int = 3,         // 最大并发分片数
    val minFileSizeForChunking: Long = 3 * 1024 * 1024  // 启用分片的最小文件大小，默认3MB
)