package cn.cqautotest.downloader.domain.usecase

import cn.cqautotest.downloader.entity.ChunkedDownloadConfig
import cn.cqautotest.downloader.entity.DownloadProgress
import cn.cqautotest.downloader.entity.DownloadTask
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient

interface DownloadUseCase {

    // region 任务管理
    suspend fun enqueueNewDownload(
        url: String,
        dirPath: String,
        fileName: String,
        useCustomFileName: Boolean = false,
        md5Expected: String? = null,
        chunkedConfig: ChunkedDownloadConfig? = null
    ): String

    suspend fun pauseDownload(taskId: String, byUser: Boolean = true)
    suspend fun resumeDownload(taskId: String)
    suspend fun cancelDownload(taskId: String)
    suspend fun retryDownload(taskId: String)
    // endregion

    // region 任务查询
    suspend fun getAllTasks(): List<DownloadTask>
    suspend fun getTaskById(taskId: String): DownloadTask?
    // endregion

    // region 进度流
    fun getDownloadProgressFlow(): SharedFlow<DownloadProgress>
    // endregion

    // region 分片下载
    suspend fun enqueueChunkedDownload(
        url: String,
        dirPath: String,
        fileName: String,
        config: ChunkedDownloadConfig = ChunkedDownloadConfig()
    ): String
    // endregion

    suspend fun testRawOkHttpSpeed(url: String, client: OkHttpClient)
}