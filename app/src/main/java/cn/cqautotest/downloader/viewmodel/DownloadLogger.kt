package cn.cqautotest.downloader.viewmodel

import cn.cqautotest.downloader.db.entity.DownloadTask
import cn.cqautotest.downloader.util.format.DownloadFormatter
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale

object DownloadLogger {

    private val mDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE)

    /**
     * 记录下载任务开始
     */
    fun logDownloadStart(task: DownloadTask) {
        val message = buildString {
            append("开始下载任务：任务id -> ${task.id}")
            append("URL：${task.url}")
            append("文件名：${task.fileName}")
            append("文件大小：${DownloadFormatter.formatBytes(task.totalBytes)}")
            append("下载模式：${task.downloadMode}")
            append("时间：${mDateFormat.format(System.currentTimeMillis())}")
        }
        Timber.i(message)
    }

    /**
     * 记录下载完成
     */
    fun loadDownloadComplete(task: DownloadTask, durationMs: Long) {
        val message = buildString {
            append("下载完成：任务id ${task.id}")
            append("文件名：${task.fileName}")
            append("文件大小：${DownloadFormatter.formatBytes(task.totalBytes)}")
            append("耗时：${durationMs}ms")
            append("完成时间：${mDateFormat.format(System.currentTimeMillis())}")
        }
    }

    /**
     * 记录下载失败
     */
    fun loadDownloadFiled(task: DownloadTask, error: Throwable) {
        val message = buildString {
            append("下载失败：任务id ${task.id}")
            append("文件名：${task.fileName}")
            append("文件大小：${DownloadFormatter.formatBytes(task.totalBytes)}")
            append("错误类型：${error.javaClass.simpleName}")
            append("错误信息：${error.message}")
            append("失败时间：${mDateFormat.format(System.currentTimeMillis())}")
        }
        Timber.e(error, message)
    }

    /**
     * 记录下载暂停
     */
    fun logDownloadPaused(task: DownloadTask, reason: String) {
        val message = buildString {
            append("下载暂停：任务id ${task.id}")
            append("文件名：${task.fileName}")
            append("暂停原因：$reason")
            append("已下载：${DownloadFormatter.formatBytes(task.downloadedBytes)}")
            append("暂停时间：${mDateFormat.format(System.currentTimeMillis())}")
        }
        Timber.i(message)
    }

    fun logDownloadResumed(task: DownloadTask) {
        val message = buildString {
            append("下载恢复：任务id ${task.id}")
            append("文件名：${task.fileName}")
            append("已下载：${DownloadFormatter.formatBytes(task.downloadedBytes)}")
            append("恢复时间：${mDateFormat.format(System.currentTimeMillis())}")
        }
        Timber.i(message)
    }

    /**
     * 记录分片下载信息
     */
    fun logChunkDownload(taskId: String, chunkIndex: Int, startByte: Long, endByte: Long, status: String) {
        val chunkSize = endByte - startByte + 1
        val message = "分片下载 [${taskId}] 分片${chunkIndex}: ${DownloadFormatter.formatBytes(chunkSize)}"
        Timber.d(message)
    }

    /**
     * 记录网络状态变化
     */
    fun logNetworkStatusChange(isConnected: Boolean) {
        val status = if (isConnected) "已连接" else "已断开"
        val message = "网络状态变化：$status - ${mDateFormat.format(System.currentTimeMillis())}"
        Timber.i(message)
    }
}