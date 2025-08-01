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

    private val taskStates = mutableMapOf<String, DownloadTaskUiState>()

    init {
        viewModelScope.launch {
            DownloadManager.downloadProgressFlow.collect { progress ->
                updateTaskState(progress)
            }
        }
    }

    private fun updateTaskState(progress: DownloadProgress) {
        val taskId = progress.taskId
        val newState = DownloadTaskUiState(
            taskId = taskId,
            fileName = progress.fileName ?: "未知文件",
            progressPercent = if (progress.totalBytes > 0) progress.downloadedBytes.toFloat() / progress.totalBytes else 0f,
            downloadedBytesFormatted = formatBytes(progress.downloadedBytes),
            totalBytesFormatted = formatBytes(progress.totalBytes),
            status = progress.status,
            statusText = when (progress.status) {
                DownloadStatus.PENDING -> "等待中..."
                DownloadStatus.DOWNLOADING -> "下载中"
                DownloadStatus.PAUSED -> "已暂停"
                DownloadStatus.COMPLETED -> "已完成"
                DownloadStatus.FAILED -> "失败"
                DownloadStatus.CANCELLED -> "已取消"
            },
            errorMessage = if (progress.status == DownloadStatus.FAILED) progress.error?.localizedMessage else null
        )
        taskStates[taskId] = newState
        _uiState.value = DownloadUiState(taskStates.values.toList())
    }

    fun handleDownloadAction(urls: List<String>) {
        viewModelScope.launch {
            val downloadDir = application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@launch
            urls.forEach { url ->
                val fileName = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.apk"
                DownloadManager.enqueueNewDownload(url.trim(), downloadDir.absolutePath, fileName)
            }
        }
    }

    fun cancelTask(taskId: String) = viewModelScope.launch { DownloadManager.cancelDownload(taskId) }
    fun pauseTask(taskId: String) = viewModelScope.launch { DownloadManager.pauseDownload(taskId) }
    fun resumeTask(taskId: String) = viewModelScope.launch { DownloadManager.resumeDownload(taskId) }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtLeast(0)
        val size = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return "%.2f %s".format(size, units[digitGroups.coerceAtMost(units.size - 1)])
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

