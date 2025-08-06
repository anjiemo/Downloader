package cn.cqautotest.downloader.di

import android.content.Context
import cn.cqautotest.downloader.db.database.AppDatabase
import cn.cqautotest.downloader.domain.DownloadManager
import cn.cqautotest.downloader.domain.usecase.DownloadUseCase
import cn.cqautotest.downloader.domain.usecase.DownloadUseCaseImpl
import cn.cqautotest.downloader.infrastructure.file.FileManager
import cn.cqautotest.downloader.infrastructure.network.NetworkManager
import cn.cqautotest.downloader.repository.DownloadRepository
import cn.cqautotest.downloader.repository.DownloadRepositoryImpl

object DownloadModule {

    private var downloadManager: DownloadManager? = null
    private var downloadUseCase: DownloadUseCase? = null
    private var downloadRepository: DownloadRepository? = null
    private var networkManager: NetworkManager? = null
    private var fileManager: FileManager? = null
    private var appDatabase: AppDatabase? = null

    fun initialize(context: Context) {
        val downloadManager = provideDownloadManager(context)
        downloadManager.initialize()
    }

    fun provideDatabase(context: Context): AppDatabase {
        return appDatabase ?: AppDatabase.getDatabase(context.applicationContext).also { appDatabase = it }
    }

    fun provideDownloadRepository(context: Context): DownloadRepository {
        return downloadRepository ?: DownloadRepositoryImpl(
            downloadDao = provideDatabase(context).downloadDao(),
            chunkDao = provideDatabase(context).chunkDao()
        ).also { downloadRepository = it }
    }

    fun provideNetworkManager(context: Context): NetworkManager {
        return networkManager ?: NetworkManager(context).also { networkManager = it }
    }

    fun provideFileManager(): FileManager {
        return fileManager ?: FileManager().also { fileManager = it }
    }

    fun provideDownloadManager(context: Context): DownloadManager {
        return downloadManager ?: DownloadManager(
            repository = provideDownloadRepository(context),
            networkManager = provideNetworkManager(context),
            fileManager = provideFileManager()
        ).also { downloadManager = it }
    }

    fun provideDownloadUseCase(context: Context): DownloadUseCase {
        return downloadUseCase ?: DownloadUseCaseImpl(
            downloadManager = provideDownloadManager(context)
        ).also { downloadUseCase = it }
    }

    fun cleanup() {
        downloadManager?.cleanup()
        networkManager?.cleanup()
        downloadManager = null
        downloadUseCase = null
        downloadRepository = null
        networkManager = null
        fileManager = null
        appDatabase = null
    }
}