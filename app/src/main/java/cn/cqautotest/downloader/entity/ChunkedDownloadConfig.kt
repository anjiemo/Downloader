package cn.cqautotest.downloader.entity

/**
 * 分片下载配置
 */
data class ChunkedDownloadConfig(
    val enabled: Boolean = false,              // 是否启用分片下载
    val chunkSize: Long = 10 * 1024 * 1024, // 分片大小，默认10MB
    val maxConcurrentChunks: Int = 3,         // 最大并发分片数
    val minFileSizeForChunking: Long = 3 * 1024 * 1024  // 启用分片的最小文件大小，默认3MB
)