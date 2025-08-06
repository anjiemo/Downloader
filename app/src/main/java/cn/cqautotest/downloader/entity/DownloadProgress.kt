package cn.cqautotest.downloader.entity

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