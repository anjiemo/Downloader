package cn.cqautotest.downloader.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.cqautotest.downloader.entity.DownloadChunk
import cn.cqautotest.downloader.entity.DownloadStatus

@Dao
interface ChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateChunk(chunk: DownloadChunk)

    @Query("SELECT * FROM download_chunks WHERE id = :chunkId")
    suspend fun getChunkById(chunkId: String): DownloadChunk?

    @Query("SELECT * FROM download_chunks WHERE taskId = :taskId ORDER BY chunkIndex")
    suspend fun getChunksByTaskId(taskId: String): List<DownloadChunk>

    @Query("SELECT * FROM download_chunks WHERE taskId = :taskId AND status = :status")
    suspend fun getChunksByTaskIdAndStatus(taskId: String, status: DownloadStatus): List<DownloadChunk>

    @Query("UPDATE download_chunks SET downloadedBytes = :downloadedBytes, status = :status WHERE id = :chunkId")
    suspend fun updateChunkProgress(chunkId: String, downloadedBytes: Long, status: DownloadStatus)

    @Query("UPDATE download_chunks SET status = :status, errorDetails = :error WHERE id = :chunkId")
    suspend fun updateChunkStatus(chunkId: String, status: DownloadStatus, error: String?)

    @Query("UPDATE download_chunks SET retryCount = retryCount + 1, lastRetryTime = :retryTime WHERE id = :chunkId")
    suspend fun incrementRetryCount(chunkId: String, retryTime: Long = System.currentTimeMillis())

    @Query("UPDATE download_chunks SET retryCount = 0, lastRetryTime = 0 WHERE id = :chunkId")
    suspend fun resetChunkRetryCount(chunkId: String)

    @Query("DELETE FROM download_chunks WHERE taskId = :taskId")
    suspend fun deleteChunksByTaskId(taskId: String)

    @Query("SELECT COUNT(*) FROM download_chunks WHERE taskId = :taskId AND status = :status")
    suspend fun getChunkCountByStatus(taskId: String, status: DownloadStatus): Int

    @Query("SELECT SUM(downloadedBytes) FROM download_chunks WHERE taskId = :taskId")
    suspend fun getTotalDownloadedBytesForTask(taskId: String): Long?
}