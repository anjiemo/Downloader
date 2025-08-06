package cn.cqautotest.downloader.domain

import cn.cqautotest.downloader.entity.ChunkProgress
import cn.cqautotest.downloader.entity.ChunkedDownloadConfig
import cn.cqautotest.downloader.entity.DownloadChunk
import cn.cqautotest.downloader.entity.DownloadMode
import cn.cqautotest.downloader.entity.DownloadProgress
import cn.cqautotest.downloader.entity.DownloadStatus
import cn.cqautotest.downloader.entity.DownloadTask
import cn.cqautotest.downloader.entity.FileIntegrityResult
import cn.cqautotest.downloader.infrastructure.file.FileManager
import cn.cqautotest.downloader.infrastructure.network.NetworkManager
import cn.cqautotest.downloader.repository.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class DownloadManager(
    private val repository: DownloadRepository,
    private val networkManager: NetworkManager,
    private val fileManager: FileManager,
) {

    private lateinit var okHttpClient: OkHttpClient
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 用于任务ID的队列
    private val taskQueueChannel = Channel<String>(Channel.UNLIMITED)
    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private var taskProcessorJob: Job? = null

    // 用于广播下载进度和状态
    private val _downloadProgressFlow = MutableSharedFlow<DownloadProgress>(
        replay = 0, // 缓存最近10条进度，供新订阅者获取
        extraBufferCapacity = 20 // 额外的缓冲，防止发送过快时背压
    )
    val downloadProgressFlow: SharedFlow<DownloadProgress> = _downloadProgressFlow.asSharedFlow()

    private var maxConcurrentDownloads = 3 // 默认并发数
    private lateinit var downloadSemaphore: Semaphore // 控制并发下载的信号量

    @Volatile
    private var isInitialized = false // DownloadManager 初始化标志
    private var isEnableGzip = true
    private val isNetworkConnected
        get() = networkManager.isNetworkConnected.value

    /**
     * 下载配置数据类
     */
    data class Config(
        val maxConcurrent: Int = 3,
        val connectTimeoutSeconds: Long = 10L,
        val readTimeoutSeconds: Long = 60L,
        val writeTimeoutSeconds: Long = 30L,
        val enableGzip: Boolean = true
    )

    private fun createDefaultOkHttpClient(config: Config): OkHttpClient {
        val enableGzip = config.enableGzip.also { isEnableGzip = it }
        return OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
            .apply {
                if (enableGzip) {
                    addInterceptor { chain ->
                        val newRequest = chain.request()
                            .newBuilder()
                            .addHeader("Accept-Encoding", "gzip")
                            .build()
                        chain.proceed(newRequest)
                    }
                }
            }
            .build()
    }

    fun initialize(config: Config = Config(), client: OkHttpClient? = null) {
        if (isInitialized) {
            Timber.w("DownloadManager 已经初始化。")
            return
        }
        // 设置最大并发下载数，确保其至少为1，避免无效值导致问题
        maxConcurrentDownloads = config.maxConcurrent.coerceAtLeast(1)

        // 创建一个 Semaphore (信号量) 来控制并发下载的数量
        downloadSemaphore = Semaphore(maxConcurrentDownloads)
        okHttpClient = client ?: createDefaultOkHttpClient(config)

        networkManager.initialize()
        isInitialized = true

        // 启动后台任务处理器协程，用于处理下载队列中的任务
        startTaskProcessor()
        // 恢复在应用上次关闭时可能被中断的下载任务
        downloadScope.launch {
            resumeInterruptedTasksOnStart()
        }
    }

    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("DownloadManager 尚未初始化。请在您的 Application 的 onCreate() 方法中调用 DownloadManager.initialize()。")
        }
    }

    private fun startTaskProcessor() {
        checkInitialized()

        if (taskProcessorJob?.isActive == true) {
            Timber.d("任务处理器已经在运行中。")
            return
        }

        Timber.i("正在启动任务处理器 (Task processor)...")
        taskProcessorJob = downloadScope.launch {
            processDownloadTasks()
        }
    }

    private suspend fun processDownloadTasks() {
        Timber.i("任务处理器 (Task processor) 协程已启动。")
        for (taskId in taskQueueChannel) {
            if (!currentCoroutineContext().isActive) {
                Timber.i("任务处理器协程不再活动。退出循环。")
                break
            }

            // 检查网络连接状态
            if (!isNetworkConnected) {
                handleNetworkUnavailable(taskId)
                continue
            }

            // 检查任务是否存在且状态为PENDING
            if (!isTaskValidForProcessing(taskId)) continue

            // 处理信号量获取和任务执行
            processTask(taskId)
        }
        Timber.i("任务处理器 (Task processor) 协程已结束。")
    }

    private suspend fun handleNetworkUnavailable(taskId: String) {
        Timber.w("网络未连接。任务 $taskId 当前无法从队列中处理。")
        val task = repository.getTaskById(taskId)
        if (task?.status == DownloadStatus.PENDING) {
            updateTaskStatus(taskId, DownloadStatus.PAUSED, isPausedByNetwork = true, error = IOException("待处理任务 $taskId 处理时网络不可用"))
        }
    }


    private suspend fun isTaskValidForProcessing(taskId: String): Boolean {
        val task = repository.getTaskById(taskId)
        if (task == null) {
            Timber.w("任务处理器：任务 $taskId 不存在于数据库中。跳过。")
            return false
        }

        if (task.status != DownloadStatus.PENDING) {
            Timber.w("任务处理器：任务 $taskId 状态不是 PENDING，而是 ${task.status}。跳过。")
            return false
        }

        if (task.isPausedByNetwork) {
            Timber.w("任务处理器：任务 $taskId 被网络暂停。跳过。")
            return false
        }

        return true
    }

    private suspend fun processTask(taskId: String) {
        try {
            Timber.d("任务处理器：正在尝试为任务 $taskId 获取信号量...")
            downloadSemaphore.acquire()
            Timber.d("任务处理器：已为任务 $taskId 获取信号量。")

            if (!currentCoroutineContext().isActive) {
                Timber.i("任务处理器在为任务 $taskId 获取信号量后变为非活动状态。正在释放信号量并退出。")
                downloadSemaphore.release()
                return
            }

            val task = repository.getTaskById(taskId)
            if (task != null && task.status == DownloadStatus.PENDING && !task.isPausedByNetwork) {
                launchDownloadJob(task)
            } else {
                Timber.w("任务 $taskId 不符合下载条件 (状态: ${task?.status}, isPausedByNetwork: ${task?.isPausedByNetwork})。正在释放信号量。")
                downloadSemaphore.release()
            }
        } catch (e: InterruptedException) {
            handleTaskProcessorInterruption(taskId, e)
        } catch (e: CancellationException) {
            handleTaskProcessorCancellation(taskId, e)
        } catch (e: Exception) {
            handleTaskProcessorException(taskId, e)
        }
    }

    private fun launchDownloadJob(task: DownloadTask) {
        val job = downloadScope.launch {
            try {
                // 根据下载模式选择执行方法
                when (task.downloadMode) {
                    DownloadMode.CHUNKED -> executeChunkedDownload(task)
                    DownloadMode.SINGLE -> executeDownload(task)
                }
            } finally {
                Timber.d("任务处理器：正在为任务 ${task.id} 释放信号量 (下载结束或失败)。")
                downloadSemaphore.release()
                activeDownloads.remove(task.id)
            }
        }
        activeDownloads[task.id] = job

        job.invokeOnCompletion { throwable ->
            if (throwable is CancellationException) {
                Timber.i("任务 ${task.id} 的下载 Job 通过 invokeOnCompletion 被取消: ${throwable.message}")
            }
        }
    }

    private fun handleTaskProcessorInterruption(taskId: String, e: InterruptedException) {
        Timber.w("任务处理器：为任务 $taskId 获取信号量时被中断。正在释放信号量 (如果持有)。")
        Thread.currentThread().interrupt()
        if (downloadSemaphore.availablePermits < maxConcurrentDownloads) {
            downloadSemaphore.release()
        }
    }

    private fun handleTaskProcessorCancellation(taskId: String, e: CancellationException) {
        Timber.i("任务处理器：协程在处理任务 $taskId 或等待信号量时被取消。正在释放信号量 (如果持有)。")
        if (downloadSemaphore.availablePermits < maxConcurrentDownloads) {
            downloadSemaphore.release()
        }
    }

    private fun handleTaskProcessorException(taskId: String, e: Exception) {
        Timber.e(e, "任务处理器：获取信号量或为任务 $taskId 启动下载时发生错误")
        if (downloadSemaphore.availablePermits < maxConcurrentDownloads) {
            downloadSemaphore.release()
        }
    }

    suspend fun enqueueNewDownload(
        url: String,
        dirPath: String,
        fileName: String,
        useCustomFileName: Boolean = false,
        md5Expected: String? = null,
        chunkedConfig: ChunkedDownloadConfig? = null
    ): String {
        checkInitialized()

        if (!fileManager.ensureDirectoryExists(dirPath)) {
            Timber.e("创建目录失败: $dirPath")
            throw IOException("创建目录失败: $dirPath")
        }

        val actualFileName = fileManager.resolveFileName(url, emptyMap(), fileName, useCustomFileName)
        val filePath = File(dirPath, actualFileName).absolutePath
        var existingTask = repository.getTaskByFilePath(filePath) // 根据文件路径查找现有任务

        if (existingTask != null) {
            Timber.i("文件 '$actualFileName' 在 '$dirPath' 的任务已存在，ID 为 ${existingTask.id}，状态为: ${existingTask.status}。")
            when (existingTask.status) {
                DownloadStatus.COMPLETED -> {
                    Timber.i("任务 ${existingTask.id} 已完成。发出进度并返回现有 ID。")
                    // 发送当前已完成的状态和进度
                    _downloadProgressFlow.tryEmit(
                        DownloadProgress(
                            existingTask.id,
                            existingTask.downloadedBytes,
                            existingTask.totalBytes,
                            existingTask.status,
                            fileName = existingTask.fileName
                        )
                    )
                    return existingTask.id // 直接返回现有任务ID
                }

                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                    Timber.i("任务 ${existingTask.id} 状态为 ${existingTask.status}。正在重置并重新入队。")
                    // 对于失败或已取消的任务，我们重置它并重新尝试
                    // 创建一个新的任务对象用于更新，保留 ID，并重置相关下载参数
                    existingTask = existingTask.copy(
                        url = url, // 更新 URL，以防其发生变化
                        status = DownloadStatus.PENDING, // 状态设置为 PENDING
                        downloadedBytes = 0L,            // 重置已下载字节
                        totalBytes = 0L,                 // 重置总字节数
                        eTag = null,                     // 清除 ETag
                        lastModified = null,             // 清除 LastModified
                        isPausedByNetwork = false,       // 清除网络暂停标记
                        errorDetails = null,             // 清除错误详情
                        createdAt = System.currentTimeMillis() // 可以选择更新创建时间或将其视为“重新激活”时间
                    )
                    repository.insertOrUpdateTask(existingTask)
                }

                DownloadStatus.PAUSED, DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> {
                    Timber.i("任务 ${existingTask.id} 已经处于 ${existingTask.status} 状态。不重新入队。发出当前状态。")
                    // 如果任务已在进行中或等待中，则不重新创建或修改，仅发出当前状态
                    _downloadProgressFlow.tryEmit(
                        DownloadProgress(
                            existingTask.id,
                            existingTask.downloadedBytes,
                            existingTask.totalBytes,
                            existingTask.status,
                            existingTask.errorDetails?.let { IOException(it) }, // 如果有错误，也一并发出
                            fileName = existingTask.fileName
                        )
                    )
                    return existingTask.id // 返回现有任务ID
                }
            }
        } else {
            // 没有找到现有任务，创建一个新的下载任务
            Timber.i("文件 '$actualFileName' 在 '$dirPath' 不存在现有任务。正在创建新任务。")

            // 根据配置决定是否使用分片下载
            val downloadMode = if (chunkedConfig?.enabled == true) {
                DownloadMode.CHUNKED
            } else {
                DownloadMode.SINGLE
            }

            existingTask = DownloadTask(
                url = url,
                filePath = filePath,
                fileName = actualFileName,
                md5Expected = md5Expected,
                downloadMode = downloadMode,
                chunkSize = chunkedConfig?.chunkSize ?: (1024 * 1024 * 1024), // 默认1GB
                maxConcurrentChunks = chunkedConfig?.maxConcurrentChunks ?: 3
            )
            repository.insertOrUpdateTask(existingTask) // 将新任务插入数据库
        }

        // 无论是更新的旧任务还是新创建的任务，都需要从数据库重新获取一次
        // 这样可以确保我们拥有的是包含正确ID（特别是对于新任务）和所有数据库默认值的最新版本
        val taskToProcess = repository.getTaskByFilePath(filePath) ?: throw IllegalStateException("在入队后无法保存或检索任务: $filePath")

        Timber.i("任务 ${taskToProcess.id} (${taskToProcess.fileName}) 已处理并准备入队。状态: ${taskToProcess.status}。 URL: ${taskToProcess.url}")
        // 发出任务的初始状态 (通常是 PENDING)
        _downloadProgressFlow.tryEmit(
            DownloadProgress(
                taskToProcess.id,
                taskToProcess.downloadedBytes,
                taskToProcess.totalBytes,
                taskToProcess.status,
                fileName = actualFileName
            )
        )

        // 仅当任务确实处于 PENDING 状态时才将其发送到处理队列
        if (taskToProcess.status == DownloadStatus.PENDING) {
            if (!isNetworkConnected) {
                // 如果当前没有网络连接，则不将任务放入下载队列，而是将其标记为因网络暂停
                Timber.w("网络未连接。任务 ${taskToProcess.id} (${taskToProcess.fileName}) 将被标记为 PAUSED (因网络原因)。")
                updateTaskStatus(taskToProcess.id, DownloadStatus.PAUSED, isPausedByNetwork = true, error = IOException("入队时网络不可用"))
            } else {
                // 网络已连接，将任务 ID 发送到 taskQueueChannel，由任务处理器协程处理
                Timber.d("网络已连接。任务 ${taskToProcess.id} (${taskToProcess.fileName}) 状态为 PENDING，正在添加到队列。")
                taskQueueChannel.send(taskToProcess.id)
            }
        }
        return taskToProcess.id // 返回处理后的任务 ID
    }

    suspend fun pauseDownload(taskId: String, byUser: Boolean = true) {
        checkInitialized()
        Timber.i("尝试暂停任务 $taskId，操作者: ${if (byUser) "用户" else "系统"}.")

        // 取消与此任务关联的活动下载 Job (如果存在)
        // 这将中断 executeDownload 中的下载循环 (如果正在运行)
        activeDownloads[taskId]?.cancel(CancellationException("下载被 ${if (byUser) "用户" else "系统"} 暂停"))

        val task = repository.getTaskById(taskId) // 从数据库获取任务信息
        if (task != null) {
            // 仅当任务当前处于 PENDING 或 DOWNLOADING 状态时才将其更新为 PAUSED
            // 其他状态 (如 COMPLETED, FAILED, CANCELLED, PAUSED) 不应由此方法更改为 PAUSED
            if (task.status == DownloadStatus.PENDING || task.status == DownloadStatus.DOWNLOADING) {
                // 如果是用户暂停，则 isPausedByNetwork 应为 false，且不应设置新的 error
                // 如果是系统暂停 (例如网络断开)，则保留之前的 isPausedByNetwork 状态 (通常应为 true)
                // 和之前的 errorDetails (如果存在)
                val newIsNetworkPaused = if (byUser) false else task.isPausedByNetwork
                // 用户暂停时不记录错误，系统暂停时保留现有错误
                val errorForStatusUpdate = if (byUser) null else task.errorDetails?.let { IOException(it) }

                updateTaskStatus(taskId, DownloadStatus.PAUSED, isPausedByNetwork = newIsNetworkPaused, error = errorForStatusUpdate)
                Timber.i("任务 $taskId 成功暂停。isPausedByNetwork 设置为: $newIsNetworkPaused")
            } else {
                // 如果任务已处于其他状态 (例如已经 PAUSED, FAILED, COMPLETED)，
                // 则不更改其状态，但仍然发出当前状态以通知监听器
                Timber.w("无法暂停任务 $taskId。当前状态: ${task.status}。正在发出当前状态。")
                _downloadProgressFlow.tryEmit(
                    DownloadProgress(
                        task.id,
                        task.downloadedBytes,
                        task.totalBytes,
                        task.status,
                        task.errorDetails?.let { IOException(it) },
                        fileName = task.fileName
                    )
                )
            }
        } else {
            Timber.w("未找到要暂停的任务 $taskId。")
        }
    }

    suspend fun resumeDownload(taskId: String) {
        checkInitialized()
        val task = repository.getTaskById(taskId) // 从数据库获取任务信息
        Timber.i("尝试恢复任务 $taskId。当前数据库状态: ${task?.status}，isPausedByNetwork: ${task?.isPausedByNetwork}，错误: ${task?.errorDetails}")

        if (task != null) {
            // 仅当任务处于 PAUSED 或 FAILED 状态时才尝试恢复
            if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                // 检查网络连接
                if (!isNetworkConnected) {
                    Timber.w("无法恢复任务 $taskId，网络已断开。确保将其标记为网络暂停。")
                    // 如果任务当前不是“因网络暂停”的 PAUSED 状态，则更新它
                    // 这可以处理从 FAILED 状态尝试在无网络时恢复的情况，或者从非网络原因的 PAUSED 状态尝试恢复的情况
                    if (!(task.status == DownloadStatus.PAUSED && task.isPausedByNetwork)) {
                        updateTaskStatus(taskId, DownloadStatus.PAUSED, isPausedByNetwork = true, error = IOException("尝试在网络离线时恢复。原始错误: ${task.errorDetails}"))
                    } else {
                        // 如果任务已经是网络暂停状态，则仅发出当前状态，因为网络仍然不可用
                        _downloadProgressFlow.tryEmit(
                            DownloadProgress(
                                task.id,
                                task.downloadedBytes,
                                task.totalBytes,
                                task.status,
                                IOException("网络不可用。原始错误: ${task.errorDetails}"),
                                fileName = task.fileName
                            )
                        )
                    }
                    return // 网络未连接，无法恢复
                }

                // 网络已连接，可以将任务设置为 PENDING 以进行恢复
                Timber.d("网络已连接。正在将任务 $taskId 设置为 PENDING 以进行恢复。")
                // 在恢复/重试时，清除 isPausedByNetwork 标记和 errorDetails
                updateTaskStatus(taskId, DownloadStatus.PENDING, isPausedByNetwork = false, error = null)

                // 任务状态现在在数据库中是 PENDING。将其添加到下载通道 (channel)。
                // 如果 updateTaskStatus 已经发出了状态，这里重新获取任务不是严格必要的，
                // 但是 taskQueueChannel.send 使用的是 taskId，并且最好确认状态确实已更新。
                val taskAfterPendingUpdate = repository.getTaskById(taskId)
                if (taskAfterPendingUpdate?.status == DownloadStatus.PENDING) {
                    taskQueueChannel.send(taskId) // 将任务 ID 发送到队列
                    Timber.i("任务 $taskId 已设置为 PENDING 并添加到队列以进行恢复。")
                } else {
                    // 这种情况可能在极少数并发场景下发生，例如在 updateTaskStatus 和 getTaskById 之间任务状态再次被改变
                    Timber.w("任务 $taskId 被设置为 PENDING 后，其状态现在是 ${taskAfterPendingUpdate?.status}。未添加到队列。这可能表示快速的并发更新。")
                }

            } else {
                // 如果任务不处于 PAUSED 或 FAILED 状态，则无法恢复
                Timber.w("任务 $taskId 无法从当前状态恢复: ${task.status}。正在发出当前状态。")
                _downloadProgressFlow.tryEmit(
                    DownloadProgress(
                        task.id,
                        task.downloadedBytes,
                        task.totalBytes,
                        task.status,
                        task.errorDetails?.let { IOException(it) },
                        fileName = task.fileName
                    )
                )
            }
        } else {
            Timber.w("未找到要恢复的任务 $taskId。")
        }
    }

    suspend fun cancelDownload(taskId: String) {
        checkInitialized()
        Timber.i("尝试取消任务 $taskId 的下载。")

        // 步骤 1: 取消活跃的下载 Job (如果存在)
        // 这会尝试中断 executeDownload 函数中正在进行的下载操作。
        // 提供一个 CancellationException，说明是用户取消的。
        activeDownloads[taskId]?.cancel(CancellationException("下载被用户取消"))
        // 注意：如果任务此时处于 PENDING 状态且尚未被任务处理器 (task processor) 拾取，
        // 任务处理器在稍后拾取它时，executeDownload 内部的状态检查机制会处理其 CANCELLED 状态。
        // 因此，executeDownload 能够正确处理在它开始执行前就被取消的任务。

        // 步骤 2: 从数据库获取任务信息
        val task = repository.getTaskById(taskId)

        if (task != null) {
            // 任务在数据库中存在

            // 步骤 3: 首先将数据库中的任务状态更新为 CANCELLED。
            // 这样做可以确保即使后续的文件删除操作失败，任务的状态也是正确的 (CANCELLED)。
            // isPausedByNetwork 设置为 false，因为取消操作与网络状态无关。
            // error 设置为 null，因为这是用户主动取消，不视为错误。
            updateTaskStatus(taskId, DownloadStatus.CANCELLED, isPausedByNetwork = false, error = null)

            // 步骤 4: 然后删除与此任务关联的本地文件。
            try {
                val file = File(task.filePath) // 根据任务记录中的文件路径创建 File 对象
                if (file.exists()) { // 检查文件是否存在
                    if (fileManager.deleteFile(file)) { // 尝试删除文件
                        Timber.d("已删除已取消任务 $taskId 的文件，路径: ${task.filePath}")
                    } else {
                        // 文件存在但删除失败 (可能由于权限问题、文件被占用等)
                        Timber.w("删除已取消任务 $taskId 的文件失败，路径: ${task.filePath}")
                        // 即使文件删除失败，任务状态在数据库中仍然是 CANCELLED。
                        // 应用程序可以根据需要实现后续的文件清理机制。
                    }
                } else {
                    // 文件路径指向的文件不存在，无需删除。
                    Timber.d("未找到已取消任务 $taskId 的文件，路径: ${task.filePath}，无需删除。")
                }
            } catch (e: Exception) {
                // 捕获在文件删除过程中可能发生的任何异常 (例如 SecurityException)。
                Timber.e(e, "删除已取消任务 $taskId 的文件时出错")
            }
            Timber.i("任务 $taskId 已成功标记为取消状态，并尝试了文件删除。")
        } else {
            // 任务在数据库中未找到
            Timber.w("未找到要取消的任务 $taskId。")
            // 如果任务未找到，它可能已被其他操作删除，或者提供的 taskId 无效。
            // 尽管如此，我们仍然可以尝试发出一个表示取消意图的通用状态通知，
            // 即使没有具体的任务数据可以关联。这有助于UI层统一处理。
            _downloadProgressFlow.tryEmit(
                DownloadProgress(
                    taskId,
                    0L, // downloadedBytes 未知
                    0L, // totalBytes 未知
                    DownloadStatus.CANCELLED, // 状态设置为 CANCELLED
                    IOException("尝试取消时未找到任务 $taskId"), // 附带一个错误信息
                    fileName = null
                )
            )
        }
    }

    suspend fun retryDownload(taskId: String) {
        checkInitialized()
        Timber.i("正在为任务 $taskId 尝试重试下载。")

        // 调用 resumeDownload 函数。
        // resumeDownload 会处理 FAILED 或 PAUSED 状态，并将任务设置为 PENDING。
        // 它同时也会清除错误详情 (error details) 和 isPausedByNetwork 标记。
        resumeDownload(taskId)
    }

    suspend fun getAllTasks(): List<DownloadTask> {
        return repository.getAllTasks()
    }

    suspend fun getTaskById(taskId: String): DownloadTask? {
        return repository.getTaskById(taskId)
    }

    /**
     * 动态配置管理
     */
    fun updateConfig(newConfig: Config) {
        checkInitialized()
        try {
            maxConcurrentDownloads = newConfig.maxConcurrent.coerceAtLeast(1)
            downloadSemaphore = Semaphore(maxConcurrentDownloads)

            // 重新创建 OkHttpClient 以应用新的超时设置
            okHttpClient = createDefaultOkHttpClient(newConfig)

            Timber.i("下载配置已更新: 最大并发数=$maxConcurrentDownloads, 连接超时=${newConfig.connectTimeoutSeconds}s, 读取超时=${newConfig.readTimeoutSeconds}s")
        } catch (e: Exception) {
            Timber.e(e, "更新下载配置失败")
        }
    }

    fun getCurrentConfig(): Config {
        return Config(
            maxConcurrent = maxConcurrentDownloads,
            connectTimeoutSeconds = okHttpClient.connectTimeoutMillis.toLong(),
            readTimeoutSeconds = okHttpClient.readTimeoutMillis.toLong(),
            writeTimeoutSeconds = okHttpClient.writeTimeoutMillis.toLong(),
            enableGzip = isEnableGzip
        )
    }

    private suspend fun resumeInterruptedTasksOnStart() {
        checkInitialized()
        Timber.d("应用启动时恢复被中断的任务...")
        // 获取在应用关闭时可能处于 DOWNLOADING 或 PENDING 状态的任务
        val tasksToProcess = repository.getTasksByStatuses(listOf(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING))

        tasksToProcess.forEach loop@{ initialTask ->
            var currentTaskState = repository.getTaskById(initialTask.id) ?: return@loop

            // 如果任务上次是 DOWNLOADING 状态，说明被中断了，先标记为 PAUSED
            // 这样做可以统一后续处理逻辑，因为所有中断的任务都会先进入 PAUSED 状态
            if (currentTaskState.status == DownloadStatus.DOWNLOADING) {
                Timber.i("任务 ${currentTaskState.id} 状态为 DOWNLOADING，设置为 PAUSED (启动时被中断)。")
                Timber.d("任务 ${currentTaskState.id} 双指针状态: 主指针=${currentTaskState.downloadedBytes}, 副指针=${currentTaskState.committedBytes}")

                // 保留原始的网络暂停状态
                updateTaskStatus(currentTaskState.id, DownloadStatus.PAUSED, currentTaskState.isPausedByNetwork, IOException("下载因应用重启被中断"))
                // 重新获取任务状态，因为上面已经更新了
                currentTaskState = repository.getTaskById(initialTask.id) ?: return@loop
            }

            when (currentTaskState.status) {
                DownloadStatus.PAUSED -> {
                    // 场景1: 如果任务是网络暂停的，并且现在网络已连接，则尝试恢复
                    if (currentTaskState.isPausedByNetwork && isNetworkConnected) {
                        Timber.i("任务 ${currentTaskState.id} 因网络暂停，网络已恢复。尝试恢复。")
                        resumeDownload(currentTaskState.id)
                    }
                    // 场景4: 如果任务是用户手动暂停的 (PAUSED 但非 isPausedByNetwork)，并且网络连接，则保持不变
                    else if (!currentTaskState.isPausedByNetwork && isNetworkConnected) {
                        Timber.d("任务 ${currentTaskState.id} 状态为 PAUSED (非网络原因) 且网络已连接。将等待手动恢复。")
                        // 此处无需操作，等待用户手动恢复
                    }
                    // 其他 PAUSED 情况 (例如网络未连接时，因网络暂停的任务) 则不作处理，保持 PAUSED
                }

                DownloadStatus.PENDING -> {
                    // 场景2: 如果任务是 PENDING 状态，并且网络已连接，则加入下载队列
                    if (isNetworkConnected) {
                        Timber.d("任务 ${currentTaskState.id} 状态为 PENDING 且网络已连接。添加到队列。")
                        taskQueueChannel.send(currentTaskState.id)
                    }
                    // 场景3: 如果任务是 PENDING 状态，但网络未连接，则标记为网络暂停
                    else { // !isNetworkConnected
                        Timber.w("任务 ${currentTaskState.id} 状态为 PENDING，但网络已断开。标记为网络暂停。")
                        updateTaskStatus(
                            currentTaskState.id,
                            DownloadStatus.PAUSED,
                            isPausedByNetwork = true,
                            error = IOException("待处理任务启动时网络不可用")
                        )
                    }
                }
                // 其他状态 (例如，COMPLETED, FAILED, CANCELLED) 在初始获取时已被过滤，
                // 或者在 DOWNLOADING -> PAUSED 转换后不符合这里的条件，因此默认不处理。
                else -> {
                    Timber.d("任务 ${currentTaskState.id} 状态为 ${currentTaskState.status}，在启动恢复逻辑中不执行任何操作。")
                }
            }
        }
    }

    /**
     * 检查服务器是否支持Range请求
     */
    private suspend fun checkRangeSupport(url: String): Boolean {
        checkInitialized()
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=0-0")
                .build()

            val response = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }
            response.use { response ->
                val supportsRange = response.code == 206 ||
                        response.headers["Accept-Ranges"]?.equals("bytes", ignoreCase = true) == true
                Timber.d("服务器Range支持检查: $supportsRange (HTTP ${response.code})")
                supportsRange
            }
        } catch (e: Exception) {
            Timber.w(e, "检查Range支持时出错")
            false
        }
    }

    /**
     * 获取文件大小
     */
    private suspend fun getFileSize(url: String): Long {
        val request = Request.Builder()
            .url(url)
            .head() // 使用HEAD请求获取文件信息
            .build()

        val response = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute()
        }
        response.use { response ->
            if (!response.isSuccessful) {
                throw IOException("无法获取文件信息: HTTP ${response.code}")
            }

            val contentLength = response.header("Content-Length")
            if (contentLength != null) {
                return contentLength.toLong()
            } else {
                throw IOException("服务器未提供Content-Length")
            }
        }
    }

    // region 分片下载相关
    /**
     * 创建分片任务
     */
    private suspend fun createChunks(task: DownloadTask): List<DownloadChunk> {
        val chunks = mutableListOf<DownloadChunk>()
        val totalSize = task.totalBytes
        val chunkSize = task.chunkSize

        var chunkIndex = 0
        var startByte = 0L

        while (startByte < totalSize) {
            val endByte = minOf(startByte + chunkSize - 1, totalSize - 1)
            val chunk = DownloadChunk(
                taskId = task.id,
                chunkIndex = chunkIndex,
                startByte = startByte,
                endByte = endByte
            )
            chunks.add(chunk)
            repository.insertOrUpdateChunk(chunk)

            startByte = endByte + 1
            chunkIndex++
        }

        // 更新任务的分片数量
        repository.updateChunkedConfig(
            taskId = task.id,
            mode = task.downloadMode,
            chunkSize = task.chunkSize,
            maxChunks = task.maxConcurrentChunks,
            supportsRange = task.supportsRangeRequests,
            chunkCount = chunks.size
        )

        Timber.i("任务 ${task.id} 创建了 ${chunks.size} 个分片")
        return chunks
    }

    /**
     * 更新分片下载的总进度
     */
    private suspend fun updateChunkedProgress(taskId: String) {
        val totalDownloaded = repository.getTotalDownloadedBytesForTask(taskId) ?: 0L
        val task = repository.getTaskById(taskId) ?: return

        repository.updateBothPointers(taskId, totalDownloaded, totalDownloaded)

        // 发送进度更新
        val chunks = repository.getChunksByTaskId(taskId)
        val chunkProgress = chunks.map { chunk ->
            val progress = if (chunk.endByte - chunk.startByte + 1 > 0) {
                chunk.downloadedBytes.toFloat() / (chunk.endByte - chunk.startByte + 1)
            } else 0f

            ChunkProgress(
                chunkIndex = chunk.chunkIndex,
                startByte = chunk.startByte,
                endByte = chunk.endByte,
                downloadedBytes = chunk.downloadedBytes,
                status = chunk.status,
                progress = progress
            )
        }

        _downloadProgressFlow.tryEmit(
            DownloadProgress(
                taskId = taskId,
                downloadedBytes = totalDownloaded,
                totalBytes = task.totalBytes,
                status = task.status,
                fileName = task.fileName,
                downloadMode = DownloadMode.CHUNKED,
                chunkProgress = chunkProgress
            )
        )
    }

    /**
     * 下载单个分片
     */
    private suspend fun downloadChunk(task: DownloadTask, chunk: DownloadChunk): Boolean {
        val file = File(task.filePath)
        var randomAccessFile: RandomAccessFile? = null
        var response: Response? = null

        try {
            // 更新分片状态为下载中
            repository.updateChunkStatus(chunk.id, DownloadStatus.DOWNLOADING, null)

            // 创建Range请求
            val requestBuilder = Request.Builder()
                .url(task.url)
                .addHeader("Range", "bytes=${chunk.startByte}-${chunk.endByte}")

            task.eTag?.let { requestBuilder.addHeader("If-Range", it) }
                ?: task.lastModified?.let { requestBuilder.addHeader("If-Range", it) }

            val request = requestBuilder.build()
            response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }

            // 验证响应
            val contentRange = response.header("Content-Range")
            val expectedRange = "bytes ${chunk.startByte}-${chunk.endByte}/${task.totalBytes}"
            if (contentRange != null && contentRange != expectedRange) {
                Timber.w("Content-Range不匹配: 期望=$expectedRange, 实际=$contentRange")
            }

            // 写入文件
            randomAccessFile = RandomAccessFile(file, "rw")
            randomAccessFile.seek(chunk.startByte)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L

            response.body.byteStream().use { inputStream ->
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    randomAccessFile.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    // 更新分片进度
                    chunk.downloadedBytes = totalBytesRead
                    repository.updateChunkProgress(chunk.id, totalBytesRead, DownloadStatus.DOWNLOADING)

                    // 检查是否超出分片范围
                    if (totalBytesRead > (chunk.endByte - chunk.startByte + 1)) {
                        Timber.w("分片 ${chunk.chunkIndex} 下载超出范围")
                        break
                    }
                }
            }

            // 强制刷新到磁盘
            randomAccessFile.fd.sync()
            randomAccessFile.close()
            randomAccessFile = null

            // 更新分片状态为完成
            repository.updateChunkStatus(chunk.id, DownloadStatus.COMPLETED, null)

            // 更新总进度
            updateChunkedProgress(task.id)


            Timber.d("分片 ${chunk.chunkIndex} 下载完成: ${totalBytesRead} bytes")
            return true

        } catch (e: Exception) {
            val errorMessage = when (e) {
                is ConnectException -> "连接失败: ${e.message}"
                is SocketException -> "网络连接异常: ${e.message}"
                is UnknownHostException -> "无法解析主机: ${e.message}"
                is IOException -> "IO异常: ${e.message}"
                else -> "未知异常: ${e.message}"
            }

            Timber.e(e, "分片 ${chunk.chunkIndex} 下载失败: $errorMessage")

            // 重试逻辑
            if (chunk.retryCount < 3) {
                repository.incrementRetryCount(chunk.id)
                repository.updateChunkStatus(chunk.id, DownloadStatus.PENDING, "重试中 (${chunk.retryCount + 1}/3)")

                val retryDelay = when (e) {
                    is ConnectException -> 3000L * (chunk.retryCount + 1) // 连接失败，更长的重试间隔
                    is SocketException -> 2000L * (chunk.retryCount + 1) // 网络异常，延长重试间隔
                    else -> 1000L * (chunk.retryCount + 1) // 其他异常，标准重试间隔
                }

                Timber.i("分片 ${chunk.chunkIndex} 将在 ${retryDelay}ms 后重试 (${e.javaClass.simpleName})")
                delay(retryDelay)

                // 递归重试
                return downloadChunk(task, chunk)
            } else {
                // 重试次数用完，标记为失败
                repository.updateChunkStatus(chunk.id, DownloadStatus.FAILED, "重试失败: $errorMessage")
                Timber.w("分片 ${chunk.chunkIndex} 重试次数已用完，标记为失败: $errorMessage")
                return false
            }
        } finally {
            randomAccessFile?.closeQuietly()
            response?.closeQuietly()
        }
    }

    /**
     * 回退到单线程下载
     */
    private suspend fun fallbackToSingleDownload(task: DownloadTask, reason: String) {
        Timber.w("回退到单线程下载: $reason")

        // 更新任务配置为单线程模式
        task.downloadMode = DownloadMode.SINGLE
        repository.updateChunkedConfig(
            taskId = task.id,
            mode = DownloadMode.SINGLE,
            chunkSize = task.chunkSize,
            maxChunks = task.maxConcurrentChunks,
            supportsRange = task.supportsRangeRequests,
            chunkCount = 0
        )

        // 清理分片数据
        repository.deleteChunksByTaskId(task.id)

        // 执行单线程下载
        executeDownload(task)
    }

    /**
     * 重新尝试下载失败的分片
     */
    private suspend fun retryFailedChunks(task: DownloadTask, failedChunks: List<DownloadChunk>) {
        Timber.i("开始重新下载 ${failedChunks.size} 个失败的分片")

        // 重置失败分片的状态
        failedChunks.forEach { chunk ->
            repository.updateChunkStatus(chunk.id, DownloadStatus.PENDING, "重新尝试下载")
            repository.updateChunkProgress(chunk.id, 0L, DownloadStatus.PENDING)
        }

        // 重新下载失败的分片
        val retryResults = failedChunks.map { chunk ->
            downloadScope.async {
                downloadChunk(task, chunk)
            }
        }.awaitAll()

        val retrySuccessCount = retryResults.count { it }
        val retryFailureCount = retryResults.size - retrySuccessCount

        Timber.i("重试结果: 成功=$retrySuccessCount, 失败=$retryFailureCount")

        if (retryFailureCount == 0) {
            // 所有重试都成功，检查文件完整性
            val finalTask = repository.getTaskById(task.id) ?: return
            val integrityResult = checkFileIntegrity(finalTask)

            if (integrityResult.isValid) {
                updateTaskStatus(task.id, DownloadStatus.COMPLETED)
                Timber.i("重试成功，分片下载完成: ${task.id}")
            } else {
                updateTaskStatus(task.id, DownloadStatus.FAILED, error = IOException("重试后文件完整性检查失败"))
            }
        } else {
            // 仍有分片失败，切换到单线程下载
            val stillFailedChunks = repository.getChunksByTaskIdAndStatus(task.id, DownloadStatus.FAILED)
            val stillFailedDetails = stillFailedChunks.joinToString(", ") {
                "分片${it.chunkIndex}(${it.errorDetails})"
            }
            fallbackToSingleDownload(task, "重试后仍有分片失败: $stillFailedDetails")
        }
    }

    /**
     * 处理失败的分片，尝试恢复策略
     */
    private suspend fun handleFailedChunks(
        task: DownloadTask,
        failedChunks: List<DownloadChunk>,
        completedCount: Int,
        totalCount: Int
    ) {
        val failedChunkDetails = failedChunks.joinToString(", ") {
            "分片${it.chunkIndex}(${it.errorDetails})"
        }

        Timber.w("检测到失败的分片: $failedChunkDetails")
        Timber.i("分片完成情况: $completedCount/$totalCount")

        // 计算成功率
        val successRate = completedCount.toFloat() / totalCount

        when {
            // 如果成功率超过80%，尝试重新下载失败的分片
            successRate >= 0.8f -> {
                Timber.i("成功率 ${(successRate * 100).toInt()}% 较高，尝试重新下载失败的分片")
                retryFailedChunks(task, failedChunks)
            }
            // 如果成功率在50%-80%之间，考虑切换到单线程下载
            successRate >= 0.5f -> {
                Timber.w("成功率 ${(successRate * 100).toInt()}% 中等，建议切换到单线程下载")
                fallbackToSingleDownload(task, "分片下载成功率过低: ${(successRate * 100).toInt()}%")
            }
            // 如果成功率低于50%，直接失败
            else -> {
                val errorMessage = "分片下载成功率过低 (${(successRate * 100).toInt()}%): $failedChunkDetails"
                Timber.e(errorMessage)
                updateTaskStatus(task.id, DownloadStatus.FAILED, error = IOException(errorMessage))
            }
        }
    }

    /**
     * 下载所有分片
     */
    private suspend fun downloadChunks(task: DownloadTask, chunks: List<DownloadChunk>) {
        val file = File(task.filePath)
        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        // 创建或清空目标文件
        if (!file.exists()) {
            file.createNewFile()
        }

        val randomAccessFile = RandomAccessFile(file, "rw")
        randomAccessFile.setLength(task.totalBytes)
        randomAccessFile.close()

        // 使用信号量控制并发分片数
        val concurrentChunks = task.maxConcurrentChunks.coerceAtLeast(1)
        val chunkSemaphore = Semaphore(concurrentChunks)
        val chunkResults = mutableListOf<Deferred<Boolean>>()

        try {
            // 启动所有分片下载
            chunks.forEach { chunk ->
                val deferred = downloadScope.async {
                    chunkSemaphore.withPermit {
                        downloadChunk(task, chunk)
                    }
                }
                chunkResults.add(deferred)
            }

            // 等待所有分片完成并收集结果
            val results = chunkResults.awaitAll()
            val successCount = results.count { it }
            val failureCount = results.size - successCount

            Timber.i("分片下载结果: 成功=$successCount, 失败=$failureCount, 总计=${chunks.size}")

            // 检查是否所有分片都成功完成
            val completedChunks = repository.getChunksByTaskIdAndStatus(task.id, DownloadStatus.COMPLETED)
            val failedChunks = repository.getChunksByTaskIdAndStatus(task.id, DownloadStatus.FAILED)
            val pendingChunks = repository.getChunksByTaskIdAndStatus(task.id, DownloadStatus.PENDING)

            Timber.i("分片状态统计: 完成=${completedChunks.size}, 失败=${failedChunks.size}, 等待中=${pendingChunks.size}")

            if (completedChunks.size == chunks.size) {
                // 所有分片完成，进行文件完整性检查
                val finalTask = repository.getTaskById(task.id) ?: return
                val downloadedBytes = completedChunks.sumOf { it.downloadedBytes }
                finalTask.downloadedBytes = downloadedBytes
                val integrityResult = checkFileIntegrity(finalTask)

                if (integrityResult.isValid) {
                    updateTaskStatus(task.id, DownloadStatus.COMPLETED)
                    repository.updateTotalBytes(task.id, downloadedBytes)
                    Timber.i("分片下载完成: ${task.id}")
                } else {
                    updateTaskStatus(task.id, DownloadStatus.FAILED, error = IOException("文件完整性检查失败"))
                }
            } else if (failedChunks.isNotEmpty()) {
                // 有分片失败，尝试恢复策略
                handleFailedChunks(task, failedChunks, completedChunks.size, chunks.size)
            } else if (pendingChunks.isNotEmpty()) {
                // 有分片仍在等待中（可能是重试中）
                Timber.w("部分分片仍在处理中，任务状态保持为下载中")
                // 不更新任务状态，让它继续重试
            } else {
                // 其他情况
                updateTaskStatus(task.id, DownloadStatus.FAILED, error = IOException("分片下载状态异常"))
            }

        } catch (e: Exception) {
            Timber.e(e, "分片下载过程中出现未处理的异常: ${task.id}")
            updateTaskStatus(task.id, DownloadStatus.FAILED, error = e)
        }
    }

    /**
     * 执行分片下载
     */
    private suspend fun executeChunkedDownload(task: DownloadTask) {
        checkInitialized()

        try {
            // 1. 检查服务器是否支持Range请求
            val supportsRange = checkRangeSupport(task.url)
            repository.updateRangeSupport(task.id, supportsRange)

            if (!supportsRange) {
                Timber.w("服务器不支持Range请求，回退到单线程下载")
                task.downloadMode = DownloadMode.SINGLE
                repository.updateChunkedConfig(
                    taskId = task.id,
                    mode = DownloadMode.SINGLE,
                    chunkSize = task.chunkSize,
                    maxChunks = task.maxConcurrentChunks,
                    supportsRange = false,
                    chunkCount = 0
                )
                executeDownload(task)
                return
            }

            // 2. 获取文件大小（如果还不知道）
            if (task.totalBytes <= 0) {
                val fileSize = getFileSize(task.url)
                repository.updateTotalBytes(task.id, fileSize)
                task.totalBytes = fileSize
            }

            // 3. 检查是否需要创建分片
            val existingChunks = repository.getChunksByTaskId(task.id)
            val chunks = existingChunks.ifEmpty {
                createChunks(task)
            }

            // 4. 检查文件大小是否足够进行分片下载
            if (task.totalBytes < task.chunkSize) {
                Timber.i("文件大小 ${task.totalBytes} 小于分片大小 ${task.chunkSize}，使用单线程下载")
                task.downloadMode = DownloadMode.SINGLE
                repository.updateChunkedConfig(
                    taskId = task.id,
                    mode = DownloadMode.SINGLE,
                    chunkSize = task.chunkSize,
                    maxChunks = task.maxConcurrentChunks,
                    supportsRange = supportsRange,
                    chunkCount = 0
                )
                executeDownload(task)
                return
            }

            // 5. 开始分片下载
            downloadChunks(task, chunks)

        } catch (e: Exception) {
            Timber.e(e, "分片下载执行失败: ${task.id}")
            updateTaskStatus(task.id, DownloadStatus.FAILED, error = e)
        }
    }

    /**
     * 便捷的分片下载方法
     */
    suspend fun enqueueChunkedDownload(
        url: String,
        dirPath: String,
        fileName: String,
        config: ChunkedDownloadConfig = ChunkedDownloadConfig()
    ): String {
        return enqueueNewDownload(
            url = url,
            dirPath = dirPath,
            fileName = fileName,
            chunkedConfig = config.copy(enabled = true)
        )
    }
    // endregion

    /**
     * 检查文件完整性，验证双指针一致性
     */
    private suspend fun checkFileIntegrity(task: DownloadTask): FileIntegrityResult {
        currentCoroutineContext().ensureActive()
        val file = File(task.filePath)

        if (!file.exists()) {
            return FileIntegrityResult(
                isValid = false,
                actualSize = 0L,
                expectedSize = task.downloadedBytes,
                errorMessage = "文件不存在"
            )
        }

        val actualFileSize = file.length()
        val expectedSize = task.downloadedBytes

        // 检查文件大小是否与主指针一致
        if (actualFileSize != expectedSize) {
            Timber.w("任务 ${task.id} 文件完整性检查失败: 实际大小=$actualFileSize, 期望大小=$expectedSize")
            return FileIntegrityResult(
                isValid = false,
                actualSize = actualFileSize,
                expectedSize = expectedSize,
                corruptionPoint = if (actualFileSize < expectedSize) actualFileSize else expectedSize,
                errorMessage = "文件大小不匹配: 实际=$actualFileSize, 期望=$expectedSize"
            )
        }

        // 检查主指针和副指针的差异是否在合理范围内
        val pointerDiff = task.downloadedBytes - task.committedBytes
        val maxAllowedDiff = 2 * 1024 * 1024 // 允许2MB的差异

        if (pointerDiff > maxAllowedDiff) {
            Timber.w("任务 ${task.id} 双指针差异过大: 主指针=${task.downloadedBytes}, 副指针=${task.committedBytes}, 差异=$pointerDiff")
            return FileIntegrityResult(
                isValid = false,
                actualSize = actualFileSize,
                expectedSize = expectedSize,
                corruptionPoint = task.committedBytes,
                errorMessage = "双指针差异过大: $pointerDiff bytes"
            )
        }

        // 如果文件存在且大小正确，进行基本的数据完整性检查
        if (actualFileSize > 0) {
            try {
                // 简单检查：尝试读取文件的最后一个字节
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(actualFileSize - 1)
                    raf.read() // 如果文件损坏，这里可能会抛出异常
                }
            } catch (e: Exception) {
                Timber.e(e, "任务 ${task.id} 文件读取检查失败")
                return FileIntegrityResult(
                    isValid = false,
                    actualSize = actualFileSize,
                    expectedSize = expectedSize,
                    corruptionPoint = actualFileSize - 1,
                    errorMessage = "文件读取失败: ${e.message}"
                )
            }
        }

        Timber.d("任务 ${task.id} 文件完整性检查通过: 大小=$actualFileSize, 主指针=${task.downloadedBytes}, 副指针=${task.committedBytes}")
        return FileIntegrityResult(
            isValid = true,
            actualSize = actualFileSize,
            expectedSize = expectedSize
        )
    }

    private suspend fun executeDownload(initialTaskStateFromQueue: DownloadTask) {
        checkInitialized()
        var currentTask: DownloadTask // 定义明确的任务对象，将在整个函数中引用最新的任务状态

        // --- 步骤 1: 从数据库重新获取任务，以确保我们拥有最新的状态 ---
        val taskFromDbOnEntry = repository.getTaskById(initialTaskStateFromQueue.id)
        if (taskFromDbOnEntry == null) {
            Timber.e("任务 ${initialTaskStateFromQueue.id} 在 executeDownload 开始时未在数据库中找到。")
            _downloadProgressFlow.tryEmit(
                DownloadProgress(
                    initialTaskStateFromQueue.id, 0L, 0L, DownloadStatus.FAILED,
                    IOException("任务记录在启动时丢失"),
                    fileName = null
                )
            )
            activeDownloads.remove(initialTaskStateFromQueue.id) // 清理 activeDownloads
            return // 任务不存在，无法继续
        }
        currentTask = taskFromDbOnEntry
        Timber.i("executeDownload 开始处理任务 ${currentTask.id} (${currentTask.fileName})。URL: ${currentTask.url}。初始数据库状态: ${currentTask.status}，已下载: ${currentTask.downloadedBytes}，已确认: ${currentTask.committedBytes}")

        // --- 步骤 2: 验证任务是否处于可开始的状态 ---
        if (currentTask.status != DownloadStatus.PENDING || currentTask.isPausedByNetwork) {
            Timber.w("任务 ${currentTask.id} 无法启动 (数据库状态: ${currentTask.status}, isPausedByNetwork: ${currentTask.isPausedByNetwork})。正在中止。")
            if (currentTask.status == DownloadStatus.FAILED || currentTask.status == DownloadStatus.PAUSED || currentTask.status == DownloadStatus.CANCELLED) {
                _downloadProgressFlow.tryEmit(
                    DownloadProgress(
                        currentTask.id,
                        currentTask.downloadedBytes,
                        currentTask.totalBytes,
                        currentTask.status,
                        currentTask.errorDetails?.let { IOException(it) },
                        fileName = File(currentTask.filePath).name
                    )
                )
            } else if (currentTask.status != DownloadStatus.COMPLETED) {
                updateTaskStatus(currentTask.id, DownloadStatus.FAILED, currentTask.isPausedByNetwork, IOException("任务启动时状态无效: ${currentTask.status}"))
            }
            activeDownloads.remove(currentTask.id) // 清理 activeDownloads
            return
        }

        // --- 步骤 3: 执行文件完整性检查和双指针同步 ---
        val integrityResult = checkFileIntegrity(currentTask)
        if (!integrityResult.isValid) {
            Timber.w("任务 ${currentTask.id} 文件完整性检查失败: ${integrityResult.errorMessage}")
            // 文件损坏，重置到安全位置
            val safePosition = integrityResult.corruptionPoint ?: 0L
            Timber.i("任务 ${currentTask.id} 重置双指针到安全位置: $safePosition")
            repository.resetPointers(currentTask.id, safePosition)
            currentTask = repository.getTaskById(currentTask.id) ?: return
        }

        // --- 步骤 4: 将任务状态更新为 DOWNLOADING ---
        updateTaskStatus(currentTask.id, DownloadStatus.DOWNLOADING, isPausedByNetwork = false, error = null)

        // --- 步骤 5: 在设置为 DOWNLOADING 后重新获取任务状态，并记录进入下载时的初始字节数 ---
        val taskAfterUpdateToDownloading = repository.getTaskById(currentTask.id)
        if (taskAfterUpdateToDownloading == null) {
            Timber.e("任务 ${currentTask.id} 在状态更新为 DOWNLOADING 后从数据库消失。")
            updateTaskStatus(initialTaskStateFromQueue.id, DownloadStatus.FAILED, error = IOException("任务在状态更新为 DOWNLOADING 后消失"))
            return
        }
        if (taskAfterUpdateToDownloading.status != DownloadStatus.DOWNLOADING) {
            Timber.w("任务 ${currentTask.id} 在数据库更新后状态为 ${taskAfterUpdateToDownloading.status} (不是 DOWNLOADING)。错误: ${taskAfterUpdateToDownloading.errorDetails}。正在中止 executeDownload。")
            return
        }
        currentTask = taskAfterUpdateToDownloading
        val initialDownloadedBytesForSession = currentTask.downloadedBytes
        val initialCommittedBytesForSession = currentTask.committedBytes
        var bytesActuallyWrittenThisSession: Long = 0 // 本次下载会话实际写入文件的字节数
        Timber.d("任务 ${currentTask.id} 已确认状态为 DOWNLOADING。文件: ${currentTask.fileName}。会话开始时已下载: $initialDownloadedBytesForSession，已确认: $initialCommittedBytesForSession")
        val requestBuilder = Request.Builder().url(currentTask.url)
        var expectedContentLengthFromServer: Long = -1 // 用于跟踪本次 HTTP 响应头中的 Content-Length

        // 使用downloadedBytes作为Range请求的起始位置，这是用户看到的进度
        val resumePosition = currentTask.downloadedBytes
        if (resumePosition > 0) {
            requestBuilder.addHeader("Range", "bytes=${resumePosition}-")
            currentTask.eTag?.let { requestBuilder.addHeader("If-Range", it) }
                ?: currentTask.lastModified?.let { requestBuilder.addHeader("If-Range", it) }
            Timber.d("任务 ${currentTask.id}: 从 $resumePosition 字节处恢复下载 (downloadedBytes)。")
        }

        var response: Response? = null
        var randomAccessFile: RandomAccessFile? = null

        try {
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("下载任务 ${currentTask.id} 在网络请求前已被取消 (coroutine not active)。")
            }

            val request = requestBuilder.build()
            Timber.d("任务 ${currentTask.id}: 构建的请求头:")
            request.headers.forEach { header ->
                Timber.d("任务 ${currentTask.id}: ReqHeader: ${header.first}=${header.second}")
            }
            Timber.d("任务 ${currentTask.id}: 正在执行 HTTP 请求到 ${request.url}，期望恢复从: ${initialDownloadedBytesForSession}, ETag: ${currentTask.eTag}, LastModified: ${currentTask.lastModified}")

            response = okHttpClient.newCall(request).execute()
            Timber.d("任务 ${currentTask.id}: 收到的响应头:")
            response.headers.forEach { header ->
                Timber.d("任务 ${currentTask.id}: ResHeader: ${header.first}=${header.second}")
            }
            // 特别关注服务器返回的 ETag, Last-Modified, Content-Range, Accept-Ranges
            Timber.d("任务 ${currentTask.id}: ResHeader 'ETag': ${response.header("ETag")}")
            Timber.d("任务 ${currentTask.id}: ResHeader 'Last-Modified': ${response.header("Last-Modified")}")
            Timber.d("任务 ${currentTask.id}: ResHeader 'Content-Range': ${response.header("Content-Range")}")
            Timber.d("任务 ${currentTask.id}: ResHeader 'Accept-Ranges': ${response.header("Accept-Ranges")}")

            val serverMd5 = response.header("Content-MD5") ?: response.header("X-Content-MD5")
            serverMd5?.let { repository.updateMd5FromServer(currentTask.id, serverMd5) }

            // 在处理响应前，再次检查任务状态和协程状态
            val taskStateBeforeWrite = repository.getTaskById(currentTask.id)
            if (!currentCoroutineContext().isActive || taskStateBeforeWrite == null || taskStateBeforeWrite.status != DownloadStatus.DOWNLOADING) {
                Timber.w("任务 ${currentTask.id} 在网络响应后被取消或状态已更改 (DB状态: ${taskStateBeforeWrite?.status} / Coroutine: ${currentCoroutineContext().isActive})。中止写入。")
                response.closeQuietly()
                if (taskStateBeforeWrite == null) {
                    updateTaskStatus(currentTask.id, DownloadStatus.FAILED, error = IOException("任务在网络响应后消失"))
                }
                return // 不再继续写入
            }
            currentTask = taskStateBeforeWrite // 刷新 currentTask
            // --- 处理 HTTP 响应状态码 ---
            if (response.code == 416) { // Range Not Satisfiable
                Timber.w("任务 ${currentTask.id}: HTTP 416 Range Not Satisfiable。当前已下载 (DB): ${currentTask.downloadedBytes}。")
                val contentRange = response.header("Content-Range") // e.g., "bytes */12345"
                val serverTotalSize = contentRange?.substringAfterLast('/')?.toLongOrNull()
                response.closeQuietly()

                if (serverTotalSize != null && currentTask.downloadedBytes >= serverTotalSize) {
                    Timber.i("任务 ${currentTask.id}: HTTP 416 确认下载已完成。服务器总大小: $serverTotalSize。标记为 COMPLETED。")
                    if (currentTask.totalBytes != serverTotalSize || currentTask.downloadedBytes != serverTotalSize) {
                        repository.updateProgress(currentTask.id, serverTotalSize, serverTotalSize)
                        currentTask = currentTask.copy(downloadedBytes = serverTotalSize, totalBytes = serverTotalSize)
                    }
                    updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED)
                } else {
                    Timber.w("任务 ${currentTask.id}: HTTP 416，但已下载 (${currentTask.downloadedBytes}) 与服务器总大小 ($serverTotalSize) 不符或总大小未知。文件可能已更改。正在重置任务。")
                    val error = IOException("Range not satisfiable (416). File may have changed. Server total: $serverTotalSize. Current downloaded: ${currentTask.downloadedBytes}. Resetting.")
                    repository.updateProgress(currentTask.id, 0L, 0L)
                    repository.updateETagAndLastModified(currentTask.id, null, null)
                    updateTaskStatus(currentTask.id, DownloadStatus.FAILED, isPausedByNetwork = false, error = error)
                }
                return
            }

            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                response.closeQuietly()
                Timber.e("任务 ${currentTask.id}: HTTP 响应不成功 ${response.code} ${response.message}. Body: $errorBody")
                throw IOException("Server error ${response.code} ${response.message}. Task ${currentTask.id}. Body: $errorBody")
            }
            // --- 处理成功的 HTTP 响应 ---
            val responseBody = response.body
            expectedContentLengthFromServer = responseBody.contentLength()
            val realFileName = fileManager.resolveFileName(currentTask.url, response.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" }, null, false)
            if (realFileName != File(currentTask.filePath).name) {
                val newPath = File(File(currentTask.filePath).parent, realFileName).absolutePath
                File(currentTask.filePath).renameTo(File(newPath))
                repository.insertOrUpdateTask(currentTask.copy(filePath = newPath, fileName = realFileName))
            }

            var serverReportedTotalBytesInHeader = currentTask.totalBytes
            val newETag = response.header("ETag")
            val newLastModified = response.header("Last-Modified")

            if (response.code == 200) { // HTTP 200 OK (完整内容)
                Timber.i("任务 ${currentTask.id}: 收到 HTTP 200 OK。会话的初始下载字节数: $initialDownloadedBytesForSession. 将从头开始下载。")
                if (resumePosition > 0) {
                    Timber.w("任务 ${currentTask.id}: 收到 HTTP 200，但之前有进度 ($resumePosition bytes)。重置双指针为 0。")
                    repository.resetPointers(currentTask.id, 0L)
                    currentTask = currentTask.copy(downloadedBytes = 0L, committedBytes = 0L)
                }
                serverReportedTotalBytesInHeader = expectedContentLengthFromServer
            } else if (response.code == 206) { // HTTP 206 Partial Content
                Timber.d("任务 ${currentTask.id}: 成功恢复下载 (HTTP 206)。从 $resumePosition 开始。")
                val contentRange = response.header("Content-Range")
                val serverTotalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
                if (serverTotalFromRange != null && serverTotalFromRange > 0) {
                    serverReportedTotalBytesInHeader = serverTotalFromRange
                }
            } else {
                Timber.w("任务 ${currentTask.id}: 收到未知成功码 ${response.code}。按 HTTP 200 处理。")
                if (resumePosition > 0) {
                    repository.resetPointers(currentTask.id, 0L)
                    currentTask = currentTask.copy(downloadedBytes = 0L, committedBytes = 0L)
                }
                serverReportedTotalBytesInHeader = expectedContentLengthFromServer
            }

            var taskMetaChanged = false
            if (newETag != currentTask.eTag || newLastModified != currentTask.lastModified) {
                repository.updateETagAndLastModified(currentTask.id, newETag, newLastModified)
                taskMetaChanged = true
            }
            if (serverReportedTotalBytesInHeader > 0 && serverReportedTotalBytesInHeader != currentTask.totalBytes) {
                repository.updateTotalBytes(currentTask.id, serverReportedTotalBytesInHeader)
                taskMetaChanged = true
            }

            if (taskMetaChanged) {
                val refreshedTask = repository.getTaskById(currentTask.id)
                if (refreshedTask != null) {
                    currentTask = refreshedTask
                    Timber.d("任务 ${currentTask.id} 元数据已更新。ETag: ${currentTask.eTag}, LastModified: ${currentTask.lastModified}, TotalBytes: ${currentTask.totalBytes}, CurrentDownloaded: ${currentTask.downloadedBytes}")
                } else {
                    response.closeQuietly()
                    throw IOException("任务 ${currentTask.id} 在元数据更新后消失")
                }
            }

            // --- 准备文件写入 ---
            val file = File(currentTask.filePath)
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    response.closeQuietly()
                    throw IOException("为任务 ${currentTask.id} 创建目录失败: ${parentDir.absolutePath}")
                }
            }

            randomAccessFile = RandomAccessFile(file, "rw")
            val fileLength = randomAccessFile.length()

            // 验证文件长度与已下载字节数是否匹配
            if (fileLength != currentTask.downloadedBytes) {
                Timber.w("任务 ${currentTask.id}: 文件长度 (${fileLength}) 与已下载字节数 (${currentTask.downloadedBytes}) 不匹配")
                if (fileLength > currentTask.downloadedBytes) {
                    // 文件比记录的大，可能是上次中断时写入不完整，截断到安全位置
                    Timber.i("任务 ${currentTask.id}: 截断文件到安全位置: ${currentTask.downloadedBytes}")
                    randomAccessFile.setLength(currentTask.downloadedBytes)
                } else {
                    // 文件比记录的小，重置双指针
                    Timber.i("任务 ${currentTask.id}: 文件长度小于记录，重置双指针到文件长度: $fileLength")
                    repository.resetPointers(currentTask.id, fileLength)
                    currentTask = currentTask.copy(downloadedBytes = fileLength, committedBytes = fileLength)
                }
            }

            randomAccessFile.seek(currentTask.downloadedBytes)

            // 简化的双指针机制：正常情况下主指针和副指针同步更新
            var bytesSinceLastDbUpdate: Long = 0
            val dbUpdateThresholdBytes: Long = 1 * 1024 * 1024 // 1MB - 更新阈值
            var bytesReadFromStream: Int
            var lastUiEmitTime = System.currentTimeMillis()
            val buffer = ByteArray(8192)

            responseBody.byteStream().use { inputStream ->
                while (true) {
                    if (!currentCoroutineContext().isActive) {
                        Timber.i("任务 ${currentTask.id} 在读取循环中检测到协程非活动状态。")
                        break // 由 finally 处理进度保存和状态
                    }

                    // 检查数据库状态以响应外部暂停/取消
                    if (System.currentTimeMillis() - lastUiEmitTime > 1000) { // 可调整检查频率
                        val taskStateInLoop = repository.getTaskById(currentTask.id)
                        if (taskStateInLoop == null || taskStateInLoop.status != DownloadStatus.DOWNLOADING) {
                            Timber.w("任务 ${currentTask.id} 在下载过程中数据库状态变为 ${taskStateInLoop?.status}。中止写入循环。")
                            break
                        }
                        // 可选：如果 totalBytes 更新，则刷新 currentTask.totalBytes
                        // currentTask = taskStateInLoop
                    }

                    bytesReadFromStream = inputStream.read(buffer)
                    if (bytesReadFromStream == -1) break // EOF
                    if (bytesReadFromStream == 0) continue

                    randomAccessFile.write(buffer, 0, bytesReadFromStream)
                    bytesActuallyWrittenThisSession += bytesReadFromStream
                    bytesSinceLastDbUpdate += bytesReadFromStream

                    // 同步更新双指针
                    if (bytesSinceLastDbUpdate >= dbUpdateThresholdBytes) {
                        val newDownloadedBytes = currentTask.downloadedBytes + bytesActuallyWrittenThisSession
                        repository.updateBothPointers(currentTask.id, newDownloadedBytes, newDownloadedBytes)
                        bytesSinceLastDbUpdate = 0
                        Timber.v("任务 ${currentTask.id} 双指针同步更新: $newDownloadedBytes/${currentTask.totalBytes}")
                    }

                    // 更新UI进度
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUiEmitTime >= 1000) {
                        val currentTotalDownloadedInMemory = currentTask.downloadedBytes + bytesActuallyWrittenThisSession
                        _downloadProgressFlow.tryEmit(
                            DownloadProgress(
                                currentTask.id,
                                currentTotalDownloadedInMemory,
                                currentTask.totalBytes,
                                DownloadStatus.DOWNLOADING,
                                fileName = file.name
                            )
                        )
                        lastUiEmitTime = currentTime
                    }
                }
            }

            // 下载完成后同步双指针到最终位置
            val finalTotalDownloadedBytes = currentTask.downloadedBytes + bytesActuallyWrittenThisSession
            repository.updateBothPointers(currentTask.id, finalTotalDownloadedBytes, finalTotalDownloadedBytes)
            Timber.i("任务 ${currentTask.id} 下载完成，双指针同步到: $finalTotalDownloadedBytes")

            // 强制刷新文件缓冲区到磁盘，确保所有数据都已写入
            try {
                randomAccessFile.fd.sync()
                Timber.d("任务 ${currentTask.id} 文件已同步到磁盘")
            } catch (e: IOException) {
                Timber.w(e, "任务 ${currentTask.id} 文件同步失败，但继续处理: ${e.message}")
            }

            // 关闭文件句柄，确保所有数据都已写入
            try {
                randomAccessFile.closeQuietly()
                randomAccessFile = null
                Timber.d("任务 ${currentTask.id} 文件句柄已关闭")
            } catch (e: IOException) {
                Timber.e(e, "任务 ${currentTask.id} 关闭文件句柄失败: ${e.message}")
            }

            // 等待一小段时间确保文件系统完成写入
            kotlinx.coroutines.delay(100)

            // 验证文件大小是否正确
            val actualFileSize = file.length()
            val expectedFileSize = finalTotalDownloadedBytes

            if (actualFileSize != expectedFileSize) {
                Timber.e("任务 ${currentTask.id} 文件大小验证失败：期望大小=$expectedFileSize, 实际大小=$actualFileSize")
                updateTaskStatus(currentTask.id, DownloadStatus.FAILED, error = IOException("文件大小不匹配：期望=$expectedFileSize, 实际=$actualFileSize"))
                return
            }

            Timber.i("任务 ${currentTask.id} 文件大小验证通过：$actualFileSize bytes")

            if (file.exists()) {
                val actualMd5 = fileManager.calculateFileMd5(file)
                val expected = currentTask.md5Expected ?: currentTask.md5FromServer

                when {
                    expected == null -> {
                        Timber.d("任务 ${currentTask.id} 无需校验 MD5")
                        updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED)
                    }

                    !actualMd5.equals(expected, ignoreCase = true) -> {
                        Timber.e("任务 ${currentTask.id} MD5校验失败：期望值=$expected, 实际值=$actualMd5")
                        updateTaskStatus(currentTask.id, DownloadStatus.FAILED, error = IOException("MD5 校验失败"))
                        return
                    }

                    else -> {
                        Timber.d("任务 ${currentTask.id} MD5校验成功")
                        updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED)
                    }
                }
            }

            val finalTaskStateFromDb = repository.getTaskById(currentTask.id)
            if (finalTaskStateFromDb == null) {
                Timber.e("任务 ${currentTask.id} 在最终状态更新前从数据库消失。")
                updateTaskStatus(currentTask.id, DownloadStatus.FAILED, error = IOException("任务记录在下载结束时消失"))
                return
            }
            currentTask = finalTaskStateFromDb

            if (!currentCoroutineContext().isActive) {
                Timber.i("任务 ${currentTask.id} 在下载循环后，但在最终状态判定前，协程被取消。当前已下载: ${currentTask.downloadedBytes}")
                if (currentTask.status == DownloadStatus.DOWNLOADING) {
                    // 交给外层 CancellationException 处理，或者如果到这里还没抛，则手动处理
                    updateTaskStatus(currentTask.id, DownloadStatus.PAUSED, isPausedByNetwork = currentTask.isPausedByNetwork, error = CancellationException("下载在完成检查前被取消"))
                }
                return
            }

            if (currentTask.status == DownloadStatus.DOWNLOADING) {
                val knownTotalBytes = currentTask.totalBytes
                if (knownTotalBytes > 0) {
                    if (currentTask.downloadedBytes < knownTotalBytes) {
                        val errMsg = "下载不完整: ${currentTask.downloadedBytes}/$knownTotalBytes. 期望内容长度: $expectedContentLengthFromServer, 本次会话实际写入: $bytesActuallyWrittenThisSession."
                        Timber.e("任务 ${currentTask.id}: $errMsg")
                        updateTaskStatus(currentTask.id, DownloadStatus.FAILED, isPausedByNetwork = false, error = IOException(errMsg))
                    } else if (currentTask.downloadedBytes > knownTotalBytes) {
                        Timber.w("任务 ${currentTask.id}: 下载字节 (${currentTask.downloadedBytes}) > 总字节 ($knownTotalBytes). 修正并完成.")
                        repository.updateProgress(currentTask.id, knownTotalBytes, knownTotalBytes)
                        updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED)
                    } else { // downloadedBytes == knownTotalBytes
                        Timber.i("任务 ${currentTask.id} 成功完成。已下载: ${currentTask.downloadedBytes}/$knownTotalBytes")
                        updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED)
                    }
                } else {
                    Timber.i("任务 ${currentTask.id} 完成 (总大小未知). 已下载: ${currentTask.downloadedBytes}. 标记为 COMPLETED.")
                    repository.updateTotalBytes(currentTask.id, currentTask.downloadedBytes)
                    updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED)
                }
            } else {
                Timber.i("任务 ${currentTask.id}: 下载循环结束。最终DB状态为 ${currentTask.status}。进度: ${currentTask.downloadedBytes}/${currentTask.totalBytes}")
                _downloadProgressFlow.tryEmit(
                    DownloadProgress(
                        currentTask.id, currentTask.downloadedBytes, currentTask.totalBytes,
                        currentTask.status, currentTask.errorDetails?.let { IOException(it) },
                        fileName = file.name
                    )
                )
            }
        } catch (e: CancellationException) {
            Timber.i(e, "任务 ${currentTask.id} 的下载被取消: ${e.message}")
            // 关键: 在取消时，保存当前会话实际已下载的进度
            val currentDownloadedBeforeError = currentTask.downloadedBytes + bytesActuallyWrittenThisSession
            repository.updateBothPointers(currentTask.id, currentDownloadedBeforeError, currentDownloadedBeforeError)

            Timber.d("任务 ${currentTask.id} 取消时双指针更新为: $currentDownloadedBeforeError")

            // isNetworkIssue false, 因为是外部/用户取消，除非特定情况
            handleCancellationOrError(currentTask.id, DownloadStatus.PAUSED, e, false)
        } catch (e: IOException) {
            Timber.e(e, "任务 ${currentTask.id} 下载期间发生 IOException。原始消息: '${e.message}'") // 打印原始异常消息
            val currentDownloadedBeforeError = currentTask.downloadedBytes + bytesActuallyWrittenThisSession
            repository.updateBothPointers(currentTask.id, currentDownloadedBeforeError, currentDownloadedBeforeError)

            Timber.d("任务 ${currentTask.id} IOException时双指针更新为: $currentDownloadedBeforeError")

            // --- 开始详细诊断 isLikelySuddenNetworkLoss ---
            val exceptionIsSocketException = e is SocketException
            val messageContent = e.message ?: "null" // 获取消息内容，如果是null则为"null"字符串
            Timber.d("任务 ${currentTask.id}: IOException诊断: e is SocketException = $exceptionIsSocketException")
            Timber.d("任务 ${currentTask.id}: IOException诊断: e.message = '$messageContent'")

            val matchesSoftwareCausedAbort = messageContent.contains("Software caused connection abort", ignoreCase = true)
            val matchesConnectionReset = messageContent.contains("Connection reset", ignoreCase = true)
            val matchesNetworkUnreachable = messageContent.contains("Network is unreachable", ignoreCase = true) ||
                    messageContent.contains("ENETUNREACH", ignoreCase = true)
            val matchesHostUnreachable = messageContent.contains("EHOSTUNREACH", ignoreCase = true)

            Timber.d("任务 ${currentTask.id}: IOException诊断: matchesSoftwareCausedAbort = $matchesSoftwareCausedAbort")
            Timber.d("任务 ${currentTask.id}: IOException诊断: matchesConnectionReset = $matchesConnectionReset")
            Timber.d("任务 ${currentTask.id}: IOException诊断: matchesNetworkUnreachable = $matchesNetworkUnreachable")
            Timber.d("任务 ${currentTask.id}: IOException诊断: matchesHostUnreachable = $matchesHostUnreachable")

            val isSocketExceptionWithMatchingMessage = exceptionIsSocketException &&
                    (matchesSoftwareCausedAbort || matchesConnectionReset || matchesNetworkUnreachable || matchesHostUnreachable)
            Timber.d("任务 ${currentTask.id}: IOException诊断: isSocketExceptionWithMatchingMessage = $isSocketExceptionWithMatchingMessage")

            val exceptionIsUnknownHost = e is UnknownHostException
            val exceptionIsConnectException = e is ConnectException
            Timber.d("任务 ${currentTask.id}: IOException诊断: e is UnknownHostException = $exceptionIsUnknownHost")
            Timber.d("任务 ${currentTask.id}: IOException诊断: e is ConnectException = $exceptionIsConnectException")

            val isLikelySuddenNetworkLoss = isSocketExceptionWithMatchingMessage || exceptionIsUnknownHost || exceptionIsConnectException
            Timber.d("任务 ${currentTask.id}: IOException诊断: 最终 isLikelySuddenNetworkLoss = $isLikelySuddenNetworkLoss")
            // --- 结束详细诊断 isLikelySuddenNetworkLoss ---

            val newStatus: DownloadStatus
            val isConsideredNetworkIssueForThisError: Boolean

            if (isLikelySuddenNetworkLoss) {
                newStatus = DownloadStatus.PAUSED
                isConsideredNetworkIssueForThisError = true
                Timber.i("任务 ${currentTask.id}: IOException (${e.javaClass.simpleName}: '${e.message}') 被识别为可能的网络突断。设置状态为 PAUSED，标记为网络问题。")
            } else {
                // 如果不是预定义的网络突断异常，则依赖全局 isNetworkConnected 状态
                val currentGlobalNetworkConnectedState = isNetworkConnected // 捕获当前全局状态以供日志记录
                Timber.d("任务 ${currentTask.id}: IOException (${e.javaClass.simpleName}: '${e.message}') 未被识别为典型的网络突断。将依赖全局 isNetworkConnected 状态 (当前值: $currentGlobalNetworkConnectedState)。")
                if (!currentGlobalNetworkConnectedState) { // 使用捕获的全局状态
                    newStatus = DownloadStatus.PAUSED
                    isConsideredNetworkIssueForThisError = true
                    Timber.i("任务 ${currentTask.id}: 全局网络状态为断开。设置状态为 PAUSED，标记为网络问题。")
                } else {
                    newStatus = DownloadStatus.FAILED
                    isConsideredNetworkIssueForThisError = false
                    Timber.w("任务 ${currentTask.id}: 全局网络状态为连接。设置状态为 FAILED，标记为非网络问题。")
                }
            }
            Timber.d("任务 ${currentTask.id}: IOException 处理决策: newStatus=$newStatus, isConsideredNetworkIssueForThisError=$isConsideredNetworkIssueForThisError (基于 isLikelySuddenNetworkLoss=$isLikelySuddenNetworkLoss 和当时的全局 isNetworkConnected=${isNetworkConnected})")
            handleCancellationOrError(currentTask.id, newStatus, e, isConsideredNetworkIssueForThisError)
        } catch (e: Exception) {
            Timber.e(e, "任务 ${currentTask.id} 下载期间发生意外错误: ${e.message}")
            val currentDownloadedBeforeError = currentTask.downloadedBytes + bytesActuallyWrittenThisSession
            repository.updateBothPointers(currentTask.id, currentDownloadedBeforeError, currentDownloadedBeforeError)

            Timber.d("任务 ${currentTask.id} 异常时双指针更新为: $currentDownloadedBeforeError")

            handleCancellationOrError(currentTask.id, DownloadStatus.FAILED, e, !isNetworkConnected)
        } finally {
            try {
                // 只有在文件句柄还没有关闭的情况下才关闭
                randomAccessFile?.closeQuietly()
                Timber.d("任务 ${currentTask.id} 在finally块中关闭文件句柄")
            } catch (e: Exception) {
                Timber.e(e, "为任务 ${currentTask.id} 关闭 randomAccessFile 时出错")
            }
            try {
                response?.closeQuietly()
            } catch (e: Exception) {
                Timber.e(e, "为任务 ${currentTask.id} 关闭 response 时出错")
            }
            Timber.d("任务 ${currentTask.id} 的 executeDownload 执行路径结束。")
            // activeDownloads 的清理主要由 updateTaskStatus (对于终态 FAILED, COMPLETED, CANCELLED)
            // 或 pauseDownload/cancelDownload (通过取消Job间接触发 handleCancellationOrError) 处理。
            // 确保清理
            val finalTaskCheck = repository.getTaskById(currentTask.id) // 重新获取以检查最终状态
            if (finalTaskCheck == null || (finalTaskCheck.status != DownloadStatus.DOWNLOADING && finalTaskCheck.status != DownloadStatus.PENDING)) {
                activeDownloads.remove(currentTask.id)
            }
        }
    }

    private suspend fun handleCancellationOrError(
        taskId: String,
        statusToSet: DownloadStatus,
        error: Throwable?,
        isNetworkIssue: Boolean // 这个参数很重要
    ) {
        checkInitialized()
        val currentTaskState = repository.getTaskById(taskId)

        if (currentTaskState != null) {
            // 只有当任务还在下载、等待，或者要从活动状态变为PAUSED/FAILED时才更新
            if (currentTaskState.status == DownloadStatus.DOWNLOADING ||
                currentTaskState.status == DownloadStatus.PENDING ||
                (statusToSet == DownloadStatus.FAILED && (currentTaskState.status == DownloadStatus.PAUSED || currentTaskState.status == DownloadStatus.DOWNLOADING || currentTaskState.status == DownloadStatus.PENDING)) || // 允许从PAUSED到FAILED
                (statusToSet == DownloadStatus.PAUSED && (currentTaskState.status == DownloadStatus.DOWNLOADING || currentTaskState.status == DownloadStatus.PENDING))
            ) {
                Timber.w("任务 $taskId 当前状态 ${currentTaskState.status}, 因错误/取消 (${error?.javaClass?.simpleName}: ${error?.message}), 将设置状态为 $statusToSet. isNetworkIssue: $isNetworkIssue")

                val finalIsNetworkPaused = when (statusToSet) {
                    DownloadStatus.PAUSED -> isNetworkIssue // 用户取消时 isNetworkIssue=false, 网络问题时 isNetworkIssue=true
                    DownloadStatus.FAILED -> if (error is IOException && !isNetworkConnected) true else currentTaskState.isPausedByNetwork
                    else -> currentTaskState.isPausedByNetwork
                }
                updateTaskStatus(taskId, statusToSet, isPausedByNetwork = finalIsNetworkPaused, error = error)
            } else {
                Timber.i("任务 $taskId 遇到错误/取消. DB状态: ${currentTaskState.status}. 不会用 $statusToSet 覆盖. Error: ${error?.message}")
                // 仅发出当前DB状态，但使用传入的error（如果存在）
                _downloadProgressFlow.tryEmit(
                    DownloadProgress(
                        currentTaskState.id, currentTaskState.downloadedBytes, currentTaskState.totalBytes,
                        currentTaskState.status, error ?: currentTaskState.errorDetails?.let { IOException(it) }
                    )
                )
            }
        } else {
            Timber.e("处理错误/取消 (${error?.message}) 时未找到任务 $taskId.")
            // 任务不存在，但仍尝试发出一个表示失败的进度事件
            _downloadProgressFlow.tryEmit(DownloadProgress(taskId, 0, 0, statusToSet, error))
        }
    }

    private suspend fun updateTaskStatus(taskId: String, newStatus: DownloadStatus, isPausedByNetwork: Boolean = false, error: Throwable? = null) {
        checkInitialized()

        // 在更新前获取当前任务信息，主要用于确定 errorMsg 和在任务更新后找不到时回退使用旧数据
        val currentTaskBeforeUpdate = repository.getTaskById(taskId)

        // 确定要存储到数据库的错误消息（errorMsg）
        val errorMsg = error?.message ?: if (newStatus == DownloadStatus.FAILED && currentTaskBeforeUpdate?.errorDetails == null) {
            // 如果新状态是 FAILED，且没有提供新的 error 对象，并且数据库中也没有旧的 errorDetails，则设置一个默认的 "Unknown error" 消息。
            "Unknown error"
        } else {
            // 否则 (即有新 error、新状态不是 FAILED、或数据库中已有 errorDetails)，
            // 保留数据库中已有的错误信息 (currentTaskBeforeUpdate?.errorDetails)。
            // 如果 error?.message 存在，它会覆盖此处的逻辑。
            currentTaskBeforeUpdate?.errorDetails
        }

        // 更新数据库中的任务状态
        repository.updateTaskStatus(taskId, newStatus, isPausedByNetwork, errorMsg)

        // 为了发射最准确的进度，在更新数据库状态后，重新获取任务的最新信息
        val updatedTask = repository.getTaskById(taskId)

        if (updatedTask != null) {
            // 如果成功获取到更新后的任务
            _downloadProgressFlow.tryEmit(
                DownloadProgress(
                    updatedTask.id,
                    updatedTask.downloadedBytes,
                    updatedTask.totalBytes,
                    newStatus, // 使用传入的 newStatus，因为 updatedTask.status 可能由于事务原因尚未在内存中立即完全同步，或者为了确保一致性
                    error ?: updatedTask.errorDetails?.let { IOException(it) }, // 优先使用传入的 error 对象；如果为 null，则尝试使用数据库中的 errorDetails (转换为 IOException)
                    fileName = updatedTask.fileName
                )
            )
            // 使用上面确定的 errorMsg 进行日志记录
            Timber.d("任务 ${updatedTask.id} 状态更新为 $newStatus。 isPausedByNetwork: ${isPausedByNetwork}。 发射进度: ${updatedTask.downloadedBytes}/${updatedTask.totalBytes}。 错误: ${errorMsg ?: "无"}")

            // 如果任务进入了终态 (例如 FAILED, CANCELLED)，则从 activeDownloads 映射中移除其对应的协程 Job (如果存在)。
            // 这是因为这些状态意味着任务的执行生命周期已经结束。
            if (newStatus == DownloadStatus.FAILED || newStatus == DownloadStatus.CANCELLED) {
                activeDownloads.remove(updatedTask.id)?.let {
                    // Job 存在且已被移除
                    Timber.d("任务 ${updatedTask.id} (当前新状态为 $newStatus) 的 Job 已在 updateTaskStatus 中从 activeDownloads 映射中移除，因为这是一个最终状态。")
                }
            }
        } else {
            // 在数据库更新状态后未能找到该任务，这通常不应该发生，但需要处理。
            Timber.w("任务 $taskId 在状态更新为 $newStatus 后未在数据库中找到，无法发射确切的进度。 将尝试基于提供的新状态和更新前的旧数据 (如果可用) 发射一个状态。")
            // 即使任务在更新后找不到了 (例如，在极端的并发情况下被删除)，
            // 如果 newStatus 是一个重要的终态，仍然尝试为 taskId 发射一个简单的状态通知。
            // 这有助于UI层至少能够根据 taskId 和新状态进行更新，即使数据不完整。
            _downloadProgressFlow.tryEmit(
                DownloadProgress(
                    taskId, // 仍然使用原始 taskId
                    currentTaskBeforeUpdate?.downloadedBytes ?: 0L, // 尝试使用更新前获取的旧下载字节数，如果不存在则为 0
                    currentTaskBeforeUpdate?.totalBytes ?: 0L, // 尝试使用更新前获取的旧总字节数，如果不存在则为 0
                    newStatus, // 使用传入的新状态
                    error ?: currentTaskBeforeUpdate?.errorDetails?.let { IOException(it) }, // 优先使用传入的 error，其次是旧的 errorDetails
                    fileName = currentTaskBeforeUpdate?.fileName
                )
            )
        }
    }

    suspend fun testRawOkHttpSpeed(url: String, client: OkHttpClient) {
        val request = Request.Builder().url(url).build()
        var totalBytesRead = 0L
        val startTime = System.currentTimeMillis()
        try {
            withContext(Dispatchers.IO) { // 确保在IO线程执行
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.e("Raw Test Failed: ${response.code}")
                        return@withContext
                    }
                    response.body.source().use { source ->
                        val buffer = okio.Buffer()
                        var readCount: Long
                        while (source.read(buffer, 8192L).also { readCount = it } != -1L) {
                            totalBytesRead += readCount
                            buffer.clear() // 只是读取并清除，不处理数据
                            // 你可以在这里加一个非常简单的进度打印，但不要太频繁
                            if (System.currentTimeMillis() - startTime > 1000L && totalBytesRead > 0) { // 每秒打印一次
                                val durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                                val speedMbps = (totalBytesRead * 8.0 / (1024.0 * 1024.0)) / durationSeconds
                                Timber.d("Raw Speed: %.2f Mbps, Downloaded: %.2f MB", speedMbps, totalBytesRead / (1024.0 * 1024.0))
                            }
                        }
                    }
                }
            }
            val durationMillis = System.currentTimeMillis() - startTime
            if (durationMillis > 0) {
                val speedKbps = (totalBytesRead * 8 / 1024) / (durationMillis / 1000.0)
                val speedMbps = speedKbps / 1024.0
                Timber.i(
                    "Raw Test Finished. Total: $totalBytesRead bytes in $durationMillis ms. Speed: %.2f Mbps (%.2f MB/s)",
                    speedMbps, totalBytesRead / (1024.0 * 1024.0 * (durationMillis / 1000.0))
                )
            } else {
                Timber.i("Raw Test Finished. Total: $totalBytesRead bytes (duration too short)")
            }

        } catch (e: Exception) {
            Timber.e(e, "Raw Test Exception")
        }
    }

    fun cleanup() {
        networkManager.cleanup()
        taskProcessorJob?.cancel()
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        Timber.i("DownloadManager 清理完成")
    }
}