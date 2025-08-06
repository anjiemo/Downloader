package cn.cqautotest.downloader.repository

import cn.cqautotest.downloader.db.dao.ChunkDao
import cn.cqautotest.downloader.db.dao.DownloadDao
import cn.cqautotest.downloader.entity.DownloadChunk
import cn.cqautotest.downloader.entity.DownloadMode
import cn.cqautotest.downloader.entity.DownloadStatus
import cn.cqautotest.downloader.entity.DownloadTask

class DownloadRepositoryImpl(
    private val downloadDao: DownloadDao,
    private val chunkDao: ChunkDao
) : DownloadRepository {

    override suspend fun insertOrUpdateTask(task: DownloadTask) {
        downloadDao.insertOrUpdateTask(task)
    }

    override suspend fun getTaskById(taskId: String): DownloadTask? {
        return downloadDao.getTaskById(taskId)
    }

    override suspend fun getTaskByFilePath(filePath: String): DownloadTask? {
        return downloadDao.getTaskByFilePath(filePath)
    }

    override suspend fun getAllTasks(): List<DownloadTask> {
        return downloadDao.getAllTasks()
    }

    override suspend fun getTasksByStatuses(statuses: List<DownloadStatus>): List<DownloadTask> {
        return downloadDao.getTasksByStatuses(statuses)
    }

    override suspend fun updateTaskStatus(taskId: String, newStatus: DownloadStatus, isPausedByNetwork: Boolean, errorMsg: String?) {
        return downloadDao.updateStatus(taskId, newStatus, isPausedByNetwork, errorMsg)
    }

    override suspend fun updateProgress(taskId: String, downloaded: Long, total: Long) {
        return downloadDao.updateProgress(taskId, downloaded, total)
    }

    override suspend fun updateTotalBytes(taskId: String, totalBytes: Long) {
        return downloadDao.updateTotalBytes(taskId, totalBytes)
    }

    override suspend fun updateDownloadedBytes(taskId: String, downloadedBytes: Long) {
        return downloadDao.updateDownloadedBytes(taskId, downloadedBytes)
    }

    override suspend fun updateETagAndLastModified(taskId: String, eTag: String?, lastModified: String?) {
        return downloadDao.updateETagAndLastModified(taskId, eTag, lastModified)
    }

    override suspend fun updateMd5FromServer(taskId: String, md5: String?) {
        return downloadDao.updateMd5FromServer(taskId, md5)
    }

    override suspend fun updateBothPointers(taskId: String, downloadedBytes: Long, committedBytes: Long, commitTime: Long) {
        return downloadDao.updateBothPointers(taskId, downloadedBytes, committedBytes, commitTime)
    }

    override suspend fun updateCommittedBytes(taskId: String, committedBytes: Long, commitTime: Long) {
        return downloadDao.updateCommittedBytes(taskId, committedBytes, commitTime)
    }

    override suspend fun resetPointers(taskId: String, position: Long, commitTime: Long) {
        return downloadDao.resetPointers(taskId, position, commitTime)
    }

    override suspend fun setFileIntegrityCheck(taskId: String, isValid: Boolean) {
        return downloadDao.setFileIntegrityCheck(taskId, isValid)
    }

    override suspend fun updateChunkedConfig(
        taskId: String,
        mode: DownloadMode,
        chunkSize: Long,
        maxChunks: Int,
        supportsRange: Boolean,
        chunkCount: Int
    ) {
        return downloadDao.updateChunkedConfig(taskId, mode, chunkSize, maxChunks, supportsRange, chunkCount)
    }

    override suspend fun updateRangeSupport(taskId: String, supports: Boolean) {
        return downloadDao.updateRangeSupport(taskId, supports)
    }

    override suspend fun insertOrUpdateChunk(chunk: DownloadChunk) {
        return chunkDao.insertOrUpdateChunk(chunk)
    }

    override suspend fun getChunkById(chunkId: String): DownloadChunk? {
        return chunkDao.getChunkById(chunkId)
    }

    override suspend fun getChunksByTaskId(taskId: String): List<DownloadChunk> {
        return chunkDao.getChunksByTaskId(taskId)
    }

    override suspend fun getChunksByTaskIdAndStatus(
        taskId: String,
        status: DownloadStatus
    ): List<DownloadChunk> {
        return chunkDao.getChunksByTaskIdAndStatus(taskId, status)
    }

    override suspend fun updateChunkProgress(chunkId: String, downloadedBytes: Long, status: DownloadStatus) {
        chunkDao.updateChunkProgress(chunkId, downloadedBytes, status)
    }

    override suspend fun updateChunkStatus(chunkId: String, status: DownloadStatus, error: String?) {
        return chunkDao.updateChunkStatus(chunkId, status, error)
    }

    override suspend fun incrementRetryCount(chunkId: String, retryTime: Long) {
        return chunkDao.incrementRetryCount(chunkId, retryTime)
    }

    override suspend fun resetChunkRetryCount(chunkId: String) {
        return chunkDao.resetChunkRetryCount(chunkId)
    }

    override suspend fun deleteChunksByTaskId(taskId: String) {
        return chunkDao.deleteChunksByTaskId(taskId)
    }

    override suspend fun getChunkCountByStatus(taskId: String, status: DownloadStatus): Int {
        return chunkDao.getChunkCountByStatus(taskId, status)
    }

    override suspend fun getTotalDownloadedBytesForTask(taskId: String): Long? {
        return chunkDao.getTotalDownloadedBytesForTask(taskId)
    }
}