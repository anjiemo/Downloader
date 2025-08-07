package cn.cqautotest.downloader.db.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import cn.cqautotest.downloader.db.dao.ChunkDao
import cn.cqautotest.downloader.db.dao.DownloadDao
import cn.cqautotest.downloader.entity.DownloadChunk
import cn.cqautotest.downloader.entity.DownloadTask

@Database(entities = [DownloadTask::class, DownloadChunk::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao
    abstract fun chunkDao(): ChunkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "download_manager_db"
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}