package cn.cqautotest.downloader.domain.usecase

import cn.cqautotest.downloader.db.entity.DownloadTask
import cn.cqautotest.downloader.domain.DownloadManager
import cn.cqautotest.downloader.entity.ChunkedDownloadConfig
import cn.cqautotest.downloader.entity.DownloadProgress
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient

class DownloadUseCaseImpl(private val downloadManager: DownloadManager) : DownloadUseCase {

    override suspend fun enqueueNewDownload(
        url: String,
        dirPath: String,
        fileName: String,
        useCustomFileName: Boolean,
        md5Expected: String?,
        chunkedConfig: ChunkedDownloadConfig?
    ): String {
        return downloadManager.enqueueNewDownload(
            url = url,
            dirPath = dirPath,
            fileName = fileName,
            useCustomFileName = useCustomFileName,
            md5Expected = md5Expected,
            chunkedConfig = chunkedConfig
        )
    }

    override suspend fun pauseDownload(taskId: String, byUser: Boolean) {
        downloadManager.pauseDownload(taskId, byUser)
    }

    override suspend fun resumeDownload(taskId: String) {
        downloadManager.resumeDownload(taskId)
    }

    override suspend fun cancelDownload(taskId: String) {
        downloadManager.cancelDownload(taskId)
    }

    override suspend fun retryDownload(taskId: String) {
        downloadManager.retryDownload(taskId)
    }

    override suspend fun getAllTasks(): List<DownloadTask> {
        return downloadManager.getAllTasks()
    }

    override suspend fun getTaskById(taskId: String): DownloadTask? {
        return downloadManager.getTaskById(taskId)
    }

    override fun getDownloadProgressFlow(): SharedFlow<DownloadProgress> {
        return downloadManager.downloadProgressFlow
    }

    override suspend fun enqueueChunkedDownload(url: String, dirPath: String, fileName: String, config: ChunkedDownloadConfig): String {
        return downloadManager.enqueueChunkedDownload(url, dirPath, fileName, config)
    }

    override suspend fun testRawOkHttpSpeed(url: String, client: OkHttpClient) {
        downloadManager.testRawOkHttpSpeed(url, client)
    }
}