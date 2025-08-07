package cn.cqautotest.downloader.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import cn.cqautotest.downloader.entity.DownloadStatus
import java.util.UUID

/**
 * 分片任务数据类
 */
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
    var isPausedByNetwork: Boolean = false,   // 是否因网络暂停
    var errorDetails: String? = null,         // 错误详情
    var retryCount: Int = 0,                  // 重试次数
    var lastRetryTime: Long = 0L,             // 最后重试时间
    var createdAt: Long = System.currentTimeMillis()
)