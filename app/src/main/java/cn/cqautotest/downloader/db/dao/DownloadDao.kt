package cn.cqautotest.downloader.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.cqautotest.downloader.entity.DownloadMode
import cn.cqautotest.downloader.entity.DownloadStatus
import cn.cqautotest.downloader.entity.DownloadTask

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTask(task: DownloadTask)

    @Query("SELECT * FROM download_tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: String): DownloadTask?

    @Query("SELECT * FROM download_tasks WHERE filePath = :filePath")
    suspend fun getTaskByFilePath(filePath: String): DownloadTask?

    @Query("UPDATE download_tasks SET downloadedBytes = :downloaded, totalBytes = :total WHERE id = :taskId")
    suspend fun updateProgress(taskId: String, downloaded: Long, total: Long)

    @Query("UPDATE download_tasks SET status = :newStatus, isPausedByNetwork = :isPausedByNetwork, errorDetails = :errorMsg WHERE id = :taskId")
    suspend fun updateStatus(taskId: String, newStatus: DownloadStatus, isPausedByNetwork: Boolean, errorMsg: String?)

    @Query("SELECT * FROM download_tasks WHERE status IN (:statuses)")
    suspend fun getTasksByStatuses(statuses: List<DownloadStatus>): List<DownloadTask>

    @Query("SELECT * FROM download_tasks")
    suspend fun getAllTasks(): List<DownloadTask>

    // 之前的 setPausedByNetwork 可能可以被 updateStatus 覆盖，或者根据具体逻辑保留。
    // 如果 updateStatus 总是会更新 isPausedByNetwork，那单独的 setPausedByNetwork 可能就不需要了。
    // 为了与 DownloadManager 的现有逻辑保持一致（比如它有时只更新isPausedByNetwork），我们暂时保留它或确保 updateStatus 能处理好所有情况。
    // 不过，DownloadManager 中的 updateTaskStatus 实际上总是同时更新 status 和 isPausedByNetwork，
    // 所以单独的 setPausedByNetwork 在 DownloadManager 的当前实现中没有被直接调用。
    // @Query("UPDATE download_tasks SET isPausedByNetwork = :isPausedByNetwork WHERE id = :taskId")
    // suspend fun setPausedByNetwork(taskId: String, isPausedByNetwork: Boolean) // 考虑是否移除

    /**
     * Updates the ETag and Last-Modified headers for a task.
     */
    @Query("UPDATE download_tasks SET eTag = :eTag, lastModified = :lastModified WHERE id = :taskId")
    suspend fun updateETagAndLastModified(taskId: String, eTag: String?, lastModified: String?)

    /**
     * Updates only the totalBytes for a task.
     */
    @Query("UPDATE download_tasks SET totalBytes = :totalBytes WHERE id = :taskId")
    suspend fun updateTotalBytes(taskId: String, totalBytes: Long)

    @Query("UPDATE download_tasks SET downloadedBytes = :downloadedBytes WHERE id = :taskId")
    suspend fun updateDownloadedBytes(taskId: String, downloadedBytes: Long)

    @Query("UPDATE download_tasks SET md5FromServer = :md5 WHERE id = :taskId")
    suspend fun updateMd5FromServer(taskId: String, md5: String?)

    // 双指针机制相关方法
    /**
     * 更新已确认写入文件的字节数（副指针）
     */
    @Query("UPDATE download_tasks SET committedBytes = :committedBytes, lastCommitTime = :commitTime WHERE id = :taskId")
    suspend fun updateCommittedBytes(taskId: String, committedBytes: Long, commitTime: Long = System.currentTimeMillis())

    /**
     * 同时更新主指针和副指针
     */
    @Query("UPDATE download_tasks SET downloadedBytes = :downloadedBytes, committedBytes = :committedBytes, lastCommitTime = :commitTime WHERE id = :taskId")
    suspend fun updateBothPointers(taskId: String, downloadedBytes: Long, committedBytes: Long, commitTime: Long = System.currentTimeMillis())

    /**
     * 设置文件完整性检查标志
     */
    @Query("UPDATE download_tasks SET fileIntegrityCheck = :isValid WHERE id = :taskId")
    suspend fun setFileIntegrityCheck(taskId: String, isValid: Boolean)

    /**
     * 重置双指针到指定位置
     */
    @Query("UPDATE download_tasks SET downloadedBytes = :position, committedBytes = :position, lastCommitTime = :commitTime WHERE id = :taskId")
    suspend fun resetPointers(taskId: String, position: Long, commitTime: Long = System.currentTimeMillis())

    // 分片下载相关方法
    /**
     * 更新下载模式和分片配置
     */
    @Query("UPDATE download_tasks SET downloadMode = :mode, chunkSize = :chunkSize, maxConcurrentChunks = :maxChunks, supportsRangeRequests = :supportsRange, chunkCount = :chunkCount WHERE id = :taskId")
    suspend fun updateChunkedConfig(taskId: String, mode: DownloadMode, chunkSize: Long, maxChunks: Int, supportsRange: Boolean, chunkCount: Int)

    /**
     * 更新服务器Range请求支持状态
     */
    @Query("UPDATE download_tasks SET supportsRangeRequests = :supports WHERE id = :taskId")
    suspend fun updateRangeSupport(taskId: String, supports: Boolean)
}