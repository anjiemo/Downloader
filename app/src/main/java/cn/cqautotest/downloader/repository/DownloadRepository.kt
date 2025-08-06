package cn.cqautotest.downloader.repository

import cn.cqautotest.downloader.entity.DownloadChunk
import cn.cqautotest.downloader.entity.DownloadMode
import cn.cqautotest.downloader.entity.DownloadStatus
import cn.cqautotest.downloader.entity.DownloadTask

interface DownloadRepository {


    // region 任务管理
    suspend fun insertOrUpdateTask(task: DownloadTask)
    suspend fun getTaskById(taskId: String): DownloadTask?
    suspend fun getTaskByFilePath(filePath: String): DownloadTask?
    suspend fun getAllTasks(): List<DownloadTask>
    suspend fun getTasksByStatuses(statuses: List<DownloadStatus>): List<DownloadTask>
    // endregion

    // region 任务状态更新
    suspend fun updateTaskStatus(taskId: String, newStatus: DownloadStatus, isPausedByNetwork: Boolean, errorMsg: String?)
    suspend fun updateProgress(taskId: String, downloaded: Long, total: Long)
    suspend fun updateTotalBytes(taskId: String, totalBytes: Long)
    suspend fun updateDownloadedBytes(taskId: String, downloadedBytes: Long)
    suspend fun updateETagAndLastModified(taskId: String, eTag: String?, lastModified: String?)
    suspend fun updateMd5FromServer(taskId: String, md5: String?)
    // endregion

    // region 双指针机制
    suspend fun updateBothPointers(taskId: String, downloadedBytes: Long, committedBytes: Long, commitTime: Long = System.currentTimeMillis())
    suspend fun updateCommittedBytes(taskId: String, committedBytes: Long, commitTime: Long = System.currentTimeMillis())
    suspend fun resetPointers(taskId: String, position: Long, commitTime: Long = System.currentTimeMillis())
    suspend fun setFileIntegrityCheck(taskId: String, isValid: Boolean)
    // endregion

    // region 分片下载
    suspend fun updateChunkedConfig(taskId: String, mode: DownloadMode, chunkSize: Long, maxChunks: Int, supportsRange: Boolean, chunkCount: Int)
    suspend fun updateRangeSupport(taskId: String, supports: Boolean)
    // endregion

    // region 分片管理
    suspend fun insertOrUpdateChunk(chunk: DownloadChunk)
    suspend fun getChunkById(chunkId: String): DownloadChunk?
    suspend fun getChunksByTaskId(taskId: String): List<DownloadChunk>
    suspend fun getChunksByTaskIdAndStatus(taskId: String, status: DownloadStatus): List<DownloadChunk>
    suspend fun updateChunkProgress(chunkId: String, downloadedBytes: Long, status: DownloadStatus)
    suspend fun updateChunkStatus(chunkId: String, status: DownloadStatus, error: String?)
    suspend fun incrementRetryCount(chunkId: String, retryTime: Long = System.currentTimeMillis())
    suspend fun deleteChunksByTaskId(taskId: String)
    suspend fun getChunkCountByStatus(taskId: String, status: DownloadStatus): Int
    suspend fun getTotalDownloadedBytesForTask(taskId: String): Long?
    // endregion
}