package cn.cqautotest.downloader.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

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

    // region 双指针机制字段
    var committedBytes: Long = 0L,             // 副指针：已确认写入文件的字节数
    var lastCommitTime: Long = 0L,             // 最后提交时间
    var fileIntegrityCheck: Boolean = false,   // 文件完整性检查标志
    // endregion

    // region 分片下载字段
    var downloadMode: DownloadMode = DownloadMode.SINGLE,  // 下载模式
    var chunkSize: Long = 1024 * 1024 * 1024,             // 分片大小，默认1GB
    var maxConcurrentChunks: Int = 3,                      // 最大并发分片数
    var supportsRangeRequests: Boolean = false,            // 服务器是否支持Range请求
    var chunkCount: Int = 0                                 // 总分片数
    // endregion
)