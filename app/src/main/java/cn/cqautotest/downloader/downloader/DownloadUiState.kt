package cn.cqautotest.downloader.downloader

data class DownloadTaskUiState(
    val taskId: String,
    val fileName: String,
    val progressPercent: Float = 0f,
    val downloadedBytesFormatted: String = "0 B",
    val totalBytesFormatted: String = "0 B",
    val status: DownloadStatus = DownloadStatus.PENDING,
    val statusText: String = "等待中...",
    val errorMessage: String? = null
)

data class DownloadUiState(
    val tasks: List<DownloadTaskUiState> = emptyList()
)