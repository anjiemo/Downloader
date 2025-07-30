package cn.cqautotest.downloader.downloader

import android.app.Application
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

class DownloadViewModel(private val application: Application) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()
    private var apkUrlToDownload: String? = null

    init {
        viewModelScope.launch {
            DownloadManager.downloadProgressFlow.collect { progress ->
                if (progress.taskId == _uiState.value.currentTaskId || _uiState.value.currentTaskId == null) {
                    // 如果 currentTaskId 为 null (例如，应用刚启动，或者之前的任务已结束)，
                    // 并且接收到的进度是一个新的任务ID (由 enqueueNewDownload 返回后设置到 currentTaskId)，
                    // 或者这个进度是针对当前正在跟踪的任务，则更新UI。
                    // 这种情况也处理了：当 ViewModel 启动并收集流时，DownloadManager 可能已经
                    // 发出了关于一个现有（可能正在进行或已完成）任务的初始状态。
                    // 如果 ViewModel 还没有 currentTaskId，但收到的进度是相关的，就应该显示它。

                    // 一个更精细的控制可能是：如果 progress.taskId 是新的，并且 _uiState.value.currentTaskId
                    // 刚刚被设置为这个 taskId (可能在 enqueueNewDownload 之后)，那么这是一个直接的更新。
                    // 如果 progress.taskId 与 currentTaskId 不同，则通常忽略。
                    // 这里的逻辑简化为：如果 currentTaskId 匹配，或者 ViewModel 还没有跟踪特定任务ID，
                    // 而这个进度可能是为这个 ViewModel 实例准备的第一个任务，则更新。

                    if (_uiState.value.currentTaskId == null && progress.status != DownloadStatus.COMPLETED && progress.status != DownloadStatus.FAILED && progress.status != DownloadStatus.CANCELLED) {
                        // 如果 ViewModel 还没有 currentTaskId，且这个进度不是一个终态，
                        // 这可能意味着是 enqueueNewDownload 之后，currentTaskId 即将被设置，
                        // 我们需要将这个 taskId 设置为 currentTaskId 来开始跟踪。
                        // 或者，这是应用启动时，一个已存在的 PENDING/DOWNLOADING/PAUSED 任务的进度。
                        // 注意：如果多个ViewModel实例可能竞争同一个 taskId，这里需要更复杂的逻辑。
                        // 对于单个下载界面，这个逻辑通常是安全的。
                        // 如果 apkUrlToDownload 已经被设置 (表示用户刚刚意图下载这个url),
                        // 那么这个进度很可能就是针对这个意图的，即使 currentTaskId 还没来得及从 enqueueNewDownload 返回并更新。
                        // 为了避免不必要的 currentTaskId 设置，最好依赖 startNewDownload 中的 _uiState.update { it.copy(currentTaskId = taskId) }
                        // 此处主要处理 currentTaskId 已设置的情况。
                        // 我们将稍微修改条件：
                        // if (progress.taskId == _uiState.value.currentTaskId) { ... }
                        // 然后在 startNewDownload 成功 enqueue 后，如果收到的第一个进度就是这个 taskId，它会被处理。
                    }

                    if (progress.taskId == _uiState.value.currentTaskId) {
                        Timber.v("Received progress for current task ${progress.taskId}: Status ${progress.status}, ${progress.downloadedBytes}/${progress.totalBytes}")
                        updateUiStateFromProgress(progress)
                    } else {
                        // 如果 ViewModel 当前没有跟踪任务 (currentTaskId == null)，
                        // 并且这个进度是一个“活动”状态 (PENDING, DOWNLOADING, PAUSED)，
                        // 这可能是应用重启后恢复的任务，或者是一个由其他地方启动的任务，
                        // 如果 ViewModel 应该显示此信息，则需要更新 currentTaskId。
                        // 但通常，ViewModel 只关心它自己通过 handleDownloadAction 启动的任务。
                        // 为了简化，我们坚持只更新与 _uiState.value.currentTaskId 严格匹配的进度。
                        // 任何 currentTaskId 的设置都应该在 handleDownloadAction/startNewDownload 中明确进行。
                        Timber.v("Received progress for other task ${progress.taskId}, current is ${_uiState.value.currentTaskId}. Ignoring.")
                    }
                }
            }
        }
    }

    private fun updateUiStateFromProgress(progress: DownloadProgress) {
        val newProgressPercent = if (progress.totalBytes > 0) {
            progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat()
        } else {
            0f
        }
        val newStatusText = when (progress.status) {
            DownloadStatus.PENDING -> "等待中..."
            DownloadStatus.DOWNLOADING -> "下载中: ${String.format("%.1f", newProgressPercent * 100)}%"
            DownloadStatus.PAUSED -> "已暂停 - ${String.format("%.1f", newProgressPercent * 100)}%"
            DownloadStatus.COMPLETED -> "下载完成!"
            DownloadStatus.FAILED -> "下载失败: ${progress.error?.localizedMessage ?: "未知错误"}"
            DownloadStatus.CANCELLED -> "已取消"
        }

        _uiState.update { currentState ->
            // 只有当传入的进度与当前UI状态中的任务ID一致，或者当前UI没有任务ID时才更新大部分状态
            // 但 statusText 和 errorMessage 可能需要根据最新的taskId更新，即使旧的currentTaskId还在
            if (currentState.currentTaskId == null || currentState.currentTaskId == progress.taskId) {
                currentState.copy(
                    // currentTaskId 不在这里更新，它由 startNewDownload 设置，由终态清除
                    progressPercent = newProgressPercent,
                    downloadedBytesFormatted = formatBytes(progress.downloadedBytes),
                    totalBytesFormatted = formatBytes(progress.totalBytes),
                    status = progress.status,
                    statusText = newStatusText,
                    isActionInProgress = progress.status == DownloadStatus.DOWNLOADING || progress.status == DownloadStatus.PAUSED,
                    errorMessage = if (progress.status == DownloadStatus.FAILED) progress.error?.localizedMessage else null
                )
            } else {
                // 如果 taskId 不匹配，通常不应该到这里，因为 collect 的 if 条件会过滤。
                // 但如果逻辑改变，这里可以只更新不依赖特定任务ID的全局状态，或者保持不变。
                currentState
            }
        }
        Timber.d("UI state updated (Task ID: ${progress.taskId}): Status = ${progress.status}, Progress = %.1f%%", newProgressPercent * 100)

        // 如果任务完成或失败或取消，并且这个任务是当前 ViewModel 正在跟踪的任务，则清除 currentTaskId
        if (progress.taskId == _uiState.value.currentTaskId &&
            (progress.status == DownloadStatus.COMPLETED ||
                    progress.status == DownloadStatus.FAILED ||
                    progress.status == DownloadStatus.CANCELLED)
        ) {
            Timber.i("Task ${progress.taskId} (current) ended with status ${progress.status}. Clearing currentTaskId.")
            _uiState.update { it.copy(currentTaskId = null) } // 允许重新下载或处理新任务
        }
    }

    fun handleDownloadAction(url: String) {
        apkUrlToDownload = url
        val currentState = _uiState.value
        Timber.d("handleDownloadAction called for URL: $url. Current status: ${currentState.status}, Current TaskId: ${currentState.currentTaskId}")

        // 如果 currentTaskId 为 null，意味着没有活动或暂停的任务，或者之前的任务已结束。
        // 或者，如果 currentTaskId 存在，但状态是 COMPLETED, FAILED, CANCELLED，也应该开始新下载。
        val shouldStartNew = currentState.currentTaskId == null ||
                currentState.status == DownloadStatus.COMPLETED ||
                currentState.status == DownloadStatus.FAILED ||
                currentState.status == DownloadStatus.CANCELLED

        when {
            shouldStartNew -> startNewDownload(url)
            currentState.status == DownloadStatus.DOWNLOADING -> pauseCurrentDownload()
            currentState.status == DownloadStatus.PAUSED -> resumeCurrentDownload()
            else -> {
                // 对于 PENDING 状态，如果用户再次点击，行为可以定义。
                // 例如，可以视为无操作，或者如果 currentTaskId 存在，则可能尝试“聚焦”这个任务。
                // 但通常，如果已经是 PENDING 且由这个 ViewModel 发起，UI 可能已经显示“等待中”。
                // 如果是用户快速连续点击，startNewDownload 的内部逻辑会处理已存在的 PENDING 任务。
                Timber.w("handleDownloadAction called in unexpected state: ${currentState.status} for task ${currentState.currentTaskId}. Considering as new download.")
                startNewDownload(url) // 降级到开始新下载
            }
        }
    }

    private fun startNewDownload(url: String) {
        viewModelScope.launch {
            val downloadDir = application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir == null) {
                Timber.e("Cannot get external download directory")
                _uiState.update {
                    it.copy(
                        statusText = "Error: Cannot get download directory",
                        errorMessage = "Cannot get download directory",
                        isActionInProgress = false // 明确停止进行中的状态
                    )
                }
                return@launch
            }
            if (!downloadDir.exists()) {
                if (downloadDir.mkdirs()) {
                    Timber.d("Download directory created: ${downloadDir.absolutePath}")
                } else {
                    Timber.e("Failed to create download directory: ${downloadDir.absolutePath}")
                    _uiState.update {
                        it.copy(
                            statusText = "Error: Failed to create directory",
                            errorMessage = "Failed to create download directory",
                            isActionInProgress = false
                        )
                    }
                    return@launch
                }
            }
            // 使用更独特的文件名，或者允许 DownloadManager 内部处理命名冲突（如果它支持）
            val fileName = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.apk"

            // 立即更新UI状态以反映“准备下载”或“排队中”
            // currentTaskId 此时还不知道，会在 enqueueNewDownload 成功后更新
            _uiState.update {
                it.copy(
                    statusText = "Preparing to download...", // 或 "Queueing..."
                    isActionInProgress = true, // 指示操作正在进行
                    status = DownloadStatus.PENDING, // 初始状态为 PENDING
                    errorMessage = null,
                    progressPercent = 0f, // 重置进度条
                    downloadedBytesFormatted = formatBytes(0),
                    totalBytesFormatted = formatBytes(0),
                    currentTaskId = null // 清除旧的 taskId，因为我们要开始新的了
                )
            }
            Timber.i("Preparing to start new download for URL: $url, FileName: $fileName")

            try {
                // 调用修改后的 enqueueNewDownload 方法
                val taskId = DownloadManager.enqueueNewDownload(
                    url = url,
                    dirPath = downloadDir.absolutePath,
                    fileName = fileName
                )
                // enqueueNewDownload 成功后，将返回的 taskId 设置为 currentTaskId
                // 这很重要，因为 DownloadManager.downloadProgressFlow 的 collect 块依赖这个 ID 来过滤进度更新
                _uiState.update { it.copy(currentTaskId = taskId) }
                Timber.i("Download task enqueued successfully. Task ID: $taskId. UI currentTaskId set.")

                // enqueueNewDownload 内部现在会处理任务已存在的情况，并可能立即发射一个状态。
                // 例如，如果任务已完成，它会发射 COMPLETED 状态。
                // ViewModel 的 collect 块应该能接收到这个初始状态。

            } catch (e: Exception) {
                Timber.e(e, "Error initiating download")
                _uiState.update {
                    it.copy(
                        statusText = "Error starting download",
                        isActionInProgress = false,
                        errorMessage = e.localizedMessage ?: "Unknown error occurred",
                        status = DownloadStatus.FAILED // 更新为 FAILED 状态
                    )
                }
            }
        }
    }

    private fun pauseCurrentDownload() {
        _uiState.value.currentTaskId?.let { taskId ->
            viewModelScope.launch {
                Timber.d("Attempting to pause task: $taskId")
                DownloadManager.pauseDownload(taskId)
                // UI 状态将通过 collect 从 DownloadManager.downloadProgressFlow 更新
            }
        } ?: run {
            Timber.w("pauseCurrentDownload called but no currentTaskId found.")
            _uiState.update { it.copy(statusText = "No task to pause", isActionInProgress = false) }
        }
    }

    private fun resumeCurrentDownload() {
        _uiState.value.currentTaskId?.let { taskId ->
            viewModelScope.launch {
                Timber.d("Attempting to resume task: $taskId")
                // 可以在这里立即更新UI以显示“尝试恢复...”，
                // 或者完全依赖 DownloadManager 的流来更新状态。
                // 为了更快的反馈，可以先更新一个临时状态：
                _uiState.update { it.copy(status = DownloadStatus.PENDING, statusText = "Resuming...", isActionInProgress = true) }
                DownloadManager.resumeDownload(taskId)
                // 最终状态将通过 collect 从 DownloadManager.downloadProgressFlow 更新
            }
        } ?: run {
            Timber.w("resumeCurrentDownload called but no currentTaskId found.")
            _uiState.update { it.copy(statusText = "No task to resume", isActionInProgress = false) }
        }
    }

    fun cancelCurrentDownload() {
        _uiState.value.currentTaskId?.let { taskId ->
            viewModelScope.launch {
                Timber.d("Attempting to cancel task: $taskId")
                DownloadManager.cancelDownload(taskId)
                // UI 状态将通过 collect 从 DownloadManager.downloadProgressFlow 更新
                // 取消后，currentTaskId 将在 collect 块中被设为 null
            }
        } ?: run {
            Timber.w("cancelCurrentDownload called but no currentTaskId found.")
            _uiState.update { it.copy(statusText = "No task to cancel", isActionInProgress = false) }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "N/A"
        if (bytes == 0L) return "0 B" // 处理0字节的显示
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.2f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("DownloadViewModel cleared")
        // 根据需要，可以在这里取消与此 ViewModel 相关的下载任务
        // 但 DownloadManager 的设计是即使 ViewModel 清理了，下载也可以继续。
        // 如果希望 ViewModel 清理时取消下载，可以调用：
        // _uiState.value.currentTaskId?.let { taskId ->
        //     viewModelScope.launch { //或者 GlobalScope 如果需要在 viewModelScope 销毁后执行
        //         Timber.d("ViewModel cleared, cancelling task: $taskId")
        //         DownloadManager.cancelDownload(taskId)
        //     }
        // }
    }

    fun testRawOkHttpSpeed(url: String) {
        viewModelScope.launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()
                DownloadManager.testRawOkHttpSpeed(url, client)
            } catch (e: Exception) {
                Timber.e(e, "Error testing raw OkHttp speed")
            }
        }
    }
}

