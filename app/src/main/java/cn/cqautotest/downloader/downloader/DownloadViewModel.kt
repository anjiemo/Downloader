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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadViewModel(private val application: Application) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    // 使用 ConcurrentHashMap 确保线程安全，尽管在这里主要从 viewModelScope 更新
    private val taskStates = ConcurrentHashMap<String, DownloadTaskUiState>()

    init {
        viewModelScope.launch {
            DownloadManager.downloadProgressFlow.collect { progress ->
                updateTaskStateWithProgress(progress)
            }
        }
    }

    private fun updateTaskStateWithProgress(progress: DownloadProgress) {
        val taskId = progress.taskId
        val newState = DownloadTaskUiState(
            taskId = taskId,
            fileName = progress.fileName ?: "未知文件",
            progressPercent = if (progress.totalBytes > 0) progress.downloadedBytes.toFloat() / progress.totalBytes else 0f,
            downloadedBytesFormatted = formatBytes(progress.downloadedBytes),
            totalBytesFormatted = formatBytes(progress.totalBytes),
            status = progress.status,
            statusText = mapStatusToText(progress.status),
            errorMessage = if (progress.status == DownloadStatus.FAILED) progress.error?.localizedMessage else null
        )
        taskStates[taskId] = newState
        //  按文件名排序，如果文件名相同，则按 taskId 排序 (通常是时间戳)
        _uiState.value = DownloadUiState(taskStates.values.toList().sortedWith(compareBy({ it.fileName }, { it.taskId })))
    }

    private fun updateTaskStateWithDbTask(task: DownloadTask) {
        val uiTask = DownloadTaskUiState(
            taskId = task.id,
            fileName = task.fileName,
            progressPercent = if (task.totalBytes > 0) task.downloadedBytes.toFloat() / task.totalBytes else 0f,
            downloadedBytesFormatted = formatBytes(task.downloadedBytes),
            totalBytesFormatted = formatBytes(task.totalBytes),
            status = task.status,
            statusText = mapStatusToText(task.status),
            errorMessage = if (task.status == DownloadStatus.FAILED) task.errorDetails else null
        )
        taskStates[task.id] = uiTask
    }

    fun loadSavedTasks() {
        viewModelScope.launch {
            val savedTasks = DownloadManager.getAllTasks(application) // 确保 DownloadManager 中有此函数
            savedTasks.forEach { dbTask ->
                // 如果任务不在当前状态中，或者它处于最终状态（完成、失败、取消），则从数据库加载/更新
                // 这可以防止覆盖正在进行的下载的实时进度
                if (!taskStates.containsKey(dbTask.id) || taskStates[dbTask.id]?.status?.isFinalState() == true) {
                    updateTaskStateWithDbTask(dbTask)
                }
            }
            _uiState.value = DownloadUiState(taskStates.values.toList().sortedWith(compareBy({ it.fileName }, { it.taskId })))
        }
    }

    fun handleDownloadAction(urls: List<String>) {
        viewModelScope.launch {
            val downloadDir = application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@launch
            urls.forEach { url ->
                // 确保文件名对每个URL都是唯一的，即使它们在同一毫秒内被添加
                val fileName = "${System.currentTimeMillis()}_${url.hashCode().toString(36)}_${UUID.randomUUID().toString().take(4)}.apk"
                val chunkedConfig = ChunkedDownloadConfig(enabled = true)
                DownloadManager.enqueueNewDownload(url.trim(), downloadDir.absolutePath, fileName, chunkedConfig = chunkedConfig)
            }
        }
    }

    fun cancelTask(taskId: String) = viewModelScope.launch { DownloadManager.cancelDownload(taskId) }
    fun pauseTask(taskId: String) = viewModelScope.launch { DownloadManager.pauseDownload(taskId) }
    fun resumeTask(taskId: String) = viewModelScope.launch { DownloadManager.resumeDownload(taskId) }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return "%.2f %s".format(bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun mapStatusToText(status: DownloadStatus): String {
        return when (status) {
            DownloadStatus.PENDING -> "等待中..."
            DownloadStatus.DOWNLOADING -> "下载中"
            DownloadStatus.PAUSED -> "已暂停"
            DownloadStatus.COMPLETED -> "已完成"
            DownloadStatus.FAILED -> "失败"
            DownloadStatus.CANCELLED -> "已取消"
        }
    }

    private fun DownloadStatus.isFinalState(): Boolean {
        return this == DownloadStatus.COMPLETED || this == DownloadStatus.FAILED || this == DownloadStatus.CANCELLED
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
