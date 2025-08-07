package cn.cqautotest.downloader.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.cqautotest.downloader.db.entity.DownloadTask
import cn.cqautotest.downloader.entity.DownloadMode
import cn.cqautotest.downloader.entity.DownloadStatus

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

    /**
     * 更新任务的ETag和Last-Modified头信息。
     */
    @Query("UPDATE download_tasks SET eTag = :eTag, lastModified = :lastModified WHERE id = :taskId")
    suspend fun updateETagAndLastModified(taskId: String, eTag: String?, lastModified: String?)

    /**
     * 仅更新任务的totalBytes（总字节数）。
     */
    @Query("UPDATE download_tasks SET totalBytes = :totalBytes WHERE id = :taskId")
    suspend fun updateTotalBytes(taskId: String, totalBytes: Long)

    @Query("UPDATE download_tasks SET downloadedBytes = :downloadedBytes WHERE id = :taskId")
    suspend fun updateDownloadedBytes(taskId: String, downloadedBytes: Long)

    @Query("UPDATE download_tasks SET md5FromServer = :md5 WHERE id = :taskId")
    suspend fun updateMd5FromServer(taskId: String, md5: String?)

    // region 双指针机制相关方法
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
    // endregion

    // region 分片下载相关方法
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
    // endregion
}