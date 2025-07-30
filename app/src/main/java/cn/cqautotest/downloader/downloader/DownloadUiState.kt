package cn.cqautotest.downloader.downloader

// 定义 UI 状态的数据类
data class DownloadUiState(
    val currentTaskId: String? = null,
    val progressPercent: Float = 0f, // 0.0f to 1.0f
    val downloadedBytesFormatted: String = "0 B",
    val totalBytesFormatted: String = "0 B",
    val status: DownloadStatus = DownloadStatus.PENDING,
    val statusText: String = "点击开始下载",
    val isActionInProgress: Boolean = false, // True if downloading, pausing, resuming
    val errorMessage: String? = null
)