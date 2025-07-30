package cn.cqautotest.downloader.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

object DownloadManager {

    private lateinit var appContext: Context
    private lateinit var downloadDao: DownloadDao // 假设这是你的Room DAO接口
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var connectivityManager: ConnectivityManager

    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val taskQueueChannel = Channel<String>(Channel.UNLIMITED) // 用于任务ID的队列
    private val activeDownloads = ConcurrentHashMap<String, Job>() // 存储活动下载任务的Job

    // 用于广播下载进度和状态
    private val _downloadProgressFlow = MutableSharedFlow<DownloadProgress>(
        replay = 10, // 缓存最近10条进度，供新订阅者获取
        extraBufferCapacity = 20 // 额外的缓冲，防止发送过快时背压
    )
    val downloadProgressFlow: SharedFlow<DownloadProgress> = _downloadProgressFlow.asSharedFlow()

    private var maxConcurrentDownloads = 3 // 默认并发数
    private lateinit var downloadSemaphore: Semaphore // 控制并发下载的信号量

    @Volatile
    private var isNetworkConnected: Boolean = true // 当前网络连接状态

    @Volatile
    private var isInitialized = false // DownloadManager 初始化标志

    // 下载配置数据类
    data class Config(
        val maxConcurrent: Int = 3,
        val connectTimeoutSeconds: Long = 20L,
        val readTimeoutSeconds: Long = 60L,
        val writeTimeoutSeconds: Long = 60L
    )

    /**
     * 初始化 DownloadManager。
     * 强烈建议在 Application 的 onCreate 方法中调用此方法。
     * @param context Application context.
     * @param config 下载配置 (可选).
     * @param client 自定义 OkHttpClient (可选).
     */
    fun initialize(context: Context, config: Config = Config(), client: OkHttpClient? = null) {
        if (isInitialized) {
            Timber.w("DownloadManager is already initialized.")
            return
        }
        appContext = context.applicationContext

        try {
            downloadDao = AppDatabase.getDatabase(appContext).downloadDao()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize DownloadDao from AppDatabase.")
            throw IllegalStateException("DownloadManager: Failed to initialize DownloadDao. Ensure AppDatabase is correctly set up and accessible.", e)
        }

        maxConcurrentDownloads = config.maxConcurrent
        if (maxConcurrentDownloads <= 0) {
            Timber.w("maxConcurrentDownloads was ${config.maxConcurrent}, setting to default 1 to avoid issues.")
            maxConcurrentDownloads = 1
        }
        downloadSemaphore = Semaphore(maxConcurrentDownloads)

        okHttpClient = client ?: OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
            .build()

        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        isInitialized = true

        checkInitialNetworkState() // 检查初始网络状态
        registerNetworkCallback()  // 注册网络状态变化回调

        startTaskProcessor() // 启动任务处理器协程
        resumeInterruptedTasksOnStart() // 恢复应用启动时可能中断的任务

        Timber.i("DownloadManager initialized. Max concurrent: $maxConcurrentDownloads.")
    }

    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException(
                "DownloadManager has not been initialized. " +
                        "Please call DownloadManager.initialize() in your Application's onCreate() method."
            )
        }
    }

    private fun checkInitialNetworkState() {
        checkInitialized()
        try {
            val activeNetwork = connectivityManager.activeNetwork
            isNetworkConnected = if (activeNetwork != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                capabilities != null && (
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        )
            } else {
                false
            }
        } catch (se: SecurityException) {
            Timber.e(se, "SecurityException in checkInitialNetworkState. Missing ACCESS_NETWORK_STATE permission?")
            isNetworkConnected = false // 假设无网络，因为无法检查
        }
        Timber.i("Initial network state: ${if (isNetworkConnected) "Connected" else "Disconnected"}")
    }

    private fun registerNetworkCallback() {
        checkInitialized()
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // 监听网络连接变化
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val wasConnected = isNetworkConnected
                // 再次确认网络真的可用
                val currentActiveNetwork = connectivityManager.activeNetwork
                val capabilities = currentActiveNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
                val trulyConnected = capabilities != null && (
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        )

                if (trulyConnected) {
                    isNetworkConnected = true // 标记网络已连接
                    if (!wasConnected) { // 仅当之前是断开状态时才处理
                        Timber.i("Network reconnected (onAvailable for network: $network).")
                        downloadScope.launch {
                            handleNetworkReconnection()
                        }
                    } else {
                        Timber.d("Network available callback for $network, but was already considered connected.")
                    }
                } else {
                    Timber.w("Network onAvailable for $network, but getNetworkCapabilities returned no usable transport or network is null.")
                    // 如果 onAvailable 被调用但我们仍然无法确认连接性，最好保持谨慎
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                // 再次检查是否有其他可用网络，因为onLost可能只针对特定网络接口
                val activeNetworkCheck = connectivityManager.activeNetwork
                if (activeNetworkCheck == null) { // 确实没有活动网络了
                    if (isNetworkConnected) { // 仅当之前是连接状态时才处理
                        Timber.i("Network disconnected (onLost for network: $network, no other active network).")
                        isNetworkConnected = false // 标记网络已断开
                        downloadScope.launch {
                            handleNetworkDisconnection()
                        }
                    }
                } else {
                    Timber.d("Network lost for interface $network, but another network $activeNetworkCheck is still active.")
                    // 如果还有其他网络，我们可能不需要做任何事情
                    val capabilities = connectivityManager.getNetworkCapabilities(activeNetworkCheck)
                    if (capabilities != null && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                    ) {
                        isNetworkConnected = true // 仍然有可用网络
                        Timber.d("Network still considered connected via $activeNetworkCheck")
                    } else {
                        // 虽然有 activeNetworkCheck，但它可能不符合我们的要求
                        Timber.w("Network lost for $network. Active network $activeNetworkCheck has no suitable transport. Assuming disconnected.")
                        if (isNetworkConnected) { // 仅当之前是连接状态时才处理
                            isNetworkConnected = false
                            downloadScope.launch { handleNetworkDisconnection() }
                        }
                    }
                }
            }
        }
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to register network callback. Missing ACCESS_NETWORK_STATE permission?")
            // 网络状态的自动恢复和暂停功能将无法工作
        }
    }

    private suspend fun handleNetworkDisconnection() {
        checkInitialized()
        Timber.i("Handling network disconnection: Pausing active downloads...")
        // 获取所有当前正在下载的任务
        val runningTasks = downloadDao.getTasksByStatuses(listOf(DownloadStatus.DOWNLOADING))
        runningTasks.forEach { task ->
            Timber.i("Network disconnected: Auto-pausing task ${task.id} (${task.fileName})")
            // 取消对应的 Job
            activeDownloads[task.id]?.cancel(CancellationException("Network disconnected automatically"))
            // 更新数据库状态为 PAUSED，并标记为网络暂停
            // 确保只更新真正是 DOWNLOADING 状态的任务，以防并发修改
            val currentTaskState = downloadDao.getTaskById(task.id)
            if (currentTaskState?.status == DownloadStatus.DOWNLOADING) {
                updateTaskStatus(task.id, DownloadStatus.PAUSED, isNetworkPaused = true, error = IOException("Network disconnected"))
            }
        }
    }

    private suspend fun handleNetworkReconnection() {
        checkInitialized()
        Timber.i("Handling network reconnection: Resuming network-paused tasks...")
        // 获取所有因网络原因暂停的任务
        val networkPausedTasks = downloadDao.getAllTasks().filter { it.isPausedByNetwork && it.status == DownloadStatus.PAUSED }
        networkPausedTasks.forEach { task ->
            Timber.i("Network reconnected: Attempting to resume task ${task.id} (${task.fileName})")
            resumeDownload(task.id) // 调用 resumeDownload 来处理恢复逻辑
        }
    }

    private fun resumeInterruptedTasksOnStart() {
        checkInitialized()
        downloadScope.launch {
            Timber.d("应用启动时恢复被中断的任务...")
            // 获取在应用关闭时可能处于 DOWNLOADING 或 PENDING 状态的任务
            val tasksToProcess = downloadDao.getTasksByStatuses(listOf(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING))

            tasksToProcess.forEach loop@{ initialTask ->
                var currentTaskState = downloadDao.getTaskById(initialTask.id) ?: return@loop

                // 如果任务上次是 DOWNLOADING 状态，说明被中断了，先标记为 PAUSED
                // 这样做可以统一后续处理逻辑，因为所有中断的任务都会先进入 PAUSED 状态
                if (currentTaskState.status == DownloadStatus.DOWNLOADING) {
                    Timber.i("任务 ${currentTaskState.id} 状态为 DOWNLOADING，设置为 PAUSED (启动时被中断)。")
                    updateTaskStatus(
                        currentTaskState.id,
                        DownloadStatus.PAUSED,
                        currentTaskState.isPausedByNetwork, // 保留原始的网络暂停状态
                        IOException("下载因应用重启被中断")
                    )
                    // 重新获取任务状态，因为上面已经更新了
                    currentTaskState = downloadDao.getTaskById(initialTask.id) ?: return@loop
                }

                // 使用 when 表达式处理不同状态的任务
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
                                isNetworkPaused = true,
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
    }

    private fun startTaskProcessor() {
        checkInitialized()
        downloadScope.launch {
            Timber.d("Task processor coroutine started.")
            for (taskId in taskQueueChannel) { // 从 Channel 中不断获取任务ID
                if (!isActive) { // 检查协程是否仍在活动
                    Timber.i("Task processor coroutine is no longer active. Exiting.")
                    break
                }

                // 在尝试获取信号量之前检查网络状态
                if (!isNetworkConnected) {
                    Timber.w("Network not connected. Task $taskId cannot be processed from queue now.")
                    val task = downloadDao.getTaskById(taskId)
                    // 如果任务仍是 PENDING，则将其标记为网络暂停
                    if (task?.status == DownloadStatus.PENDING) {
                        updateTaskStatus(taskId, DownloadStatus.PAUSED, isNetworkPaused = true, error = IOException("Network unavailable for pending task processing"))
                    }
                    // 不获取信号量，继续等待下一个任务或网络恢复
                    continue
                }

                try {
                    Timber.d("Task processor: Attempting to acquire semaphore for task $taskId...")
                    downloadSemaphore.acquire() // 获取信号量，控制并发
                    Timber.d("Task processor: Semaphore acquired for task $taskId.")

                    if (!isActive) { // 再次检查，防止在等待信号量时协程被取消
                        Timber.i("Task processor became inactive after acquiring semaphore for $taskId. Releasing and exiting.")
                        downloadSemaphore.release()
                        break
                    }

                    // 从数据库获取最新的任务状态，确保是 PENDING 且非网络暂停
                    val task = downloadDao.getTaskById(taskId)
                    if (task != null && task.status == DownloadStatus.PENDING && !task.isPausedByNetwork) {
                        // 启动一个新的子协程来执行实际的下载操作
                        val job = launch { // 使用 downloadScope.launch
                            try {
                                executeDownload(task)
                            } finally {
                                Timber.d("Task processor: Releasing semaphore for task ${task.id} (download ended or failed).")
                                downloadSemaphore.release() // 确保在下载结束或异常时释放信号量
                                activeDownloads.remove(task.id) // 从活动下载中移除
                            }
                        }
                        activeDownloads[taskId] = job // 存储 Job 以便可以取消
                        job.invokeOnCompletion { throwable ->
                            if (throwable is CancellationException) {
                                Timber.i("Download job for $taskId was cancelled by invokeOnCompletion: ${throwable.message}")
                                // 此处不需要再 remove activeDownloads 或 release semaphore，因为
                                // cancel 会触发 finally 块。但如果 cancel 是在 executeDownload 之外发生，则需要考虑。
                                // 通常，这个 invokeOnCompletion 是为了日志或额外的清理（如果 finally 不够）。
                            }
                            // 如果需要，可以在这里处理其他类型的异常，但通常 executeDownload 内部会处理
                        }
                    } else {
                        Timber.w("Task $taskId not eligible for download (status: ${task?.status}, isPausedByNetwork: ${task?.isPausedByNetwork}). Releasing semaphore.")
                        downloadSemaphore.release() // 如果任务不符合条件，释放信号量
                    }
                } catch (e: InterruptedException) {
                    Timber.w("Task processor: Semaphore acquisition interrupted for task $taskId. Releasing semaphore if held.")
                    Thread.currentThread().interrupt() // 重新设置中断状态
                    // 检查信号量是否真的被当前线程获取（虽然 acquire() 中断时通常不会完成获取）
                    // 这是一个保守的释放，更安全的做法是仅在确认持有后释放。
                    // 但由于 Semaphore 不跟踪持有者，我们依赖于可获取许可数量。
                    if (downloadSemaphore.availablePermits < maxConcurrentDownloads) downloadSemaphore.release()
                    break // 退出循环
                } catch (e: CancellationException) {
                    Timber.i("Task processor: Coroutine was cancelled while processing task $taskId or waiting for semaphore. Releasing if held.")
                    if (downloadSemaphore.availablePermits < maxConcurrentDownloads) downloadSemaphore.release()
                    break // 退出循环
                } catch (e: Exception) {
                    Timber.e(e, "Task processor: Error acquiring semaphore or launching task $taskId")
                    // 发生其他异常，同样尝试释放信号量以防万一
                    if (downloadSemaphore.availablePermits < maxConcurrentDownloads) {
                        downloadSemaphore.release()
                    }
                    // 对于单个任务的失败，通常不应该中断整个处理器循环，除非是严重错误
                }
            }
            Timber.i("Task processor coroutine finished.")
        }
    }

    /**
     * Enqueues a new download request or handles an existing task for the same file.
     *
     * If a task for the same filePath already exists:
     * - If COMPLETED: Emits progress and returns existing ID.
     * - If FAILED or CANCELLED: Resets the task and re-enqueues it as PENDING.
     * - If PAUSED, DOWNLOADING, or PENDING: Emits progress and returns existing ID (does not re-enqueue).
     *
     * If no task exists, creates a new one, saves it, and enqueues it.
     *
     * @param url The URL of the file to download.
     * @param dirPath The directory path where the file should be saved.
     * @param fileName The name of the file.
     * @return The ID of the download task.
     * @throws IOException if the directory cannot be created.
     * @throws IllegalStateException if the task cannot be saved or retrieved after enqueue.
     */
    suspend fun enqueueNewDownload(url: String, dirPath: String, fileName: String): String {
        checkInitialized()
        val directory = File(dirPath)
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                Timber.e("Failed to create directory: $dirPath")
                throw IOException("Failed to create directory: $dirPath")
            }
        }
        val filePath = File(dirPath, fileName).absolutePath
        var existingTask = downloadDao.getTaskByFilePath(filePath) // Use a different variable name

        if (existingTask != null) {
            Timber.i("Task for file '$fileName' at '$dirPath' already exists with ID ${existingTask.id}, status: ${existingTask.status}.")
            when (existingTask.status) {
                DownloadStatus.COMPLETED -> {
                    Timber.i("Task ${existingTask.id} is already completed. Emitting progress and returning existing ID.")
                    _downloadProgressFlow.tryEmit(DownloadProgress(existingTask.id, existingTask.downloadedBytes, existingTask.totalBytes, existingTask.status))
                    return existingTask.id
                }

                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                    Timber.i("Task ${existingTask.id} was ${existingTask.status}. Resetting and re-enqueueing.")
                    // Create a new task object for update, preserving ID
                    existingTask = existingTask.copy(
                        url = url, // Update URL in case it changed
                        status = DownloadStatus.PENDING,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        eTag = null,
                        lastModified = null,
                        isPausedByNetwork = false,
                        errorDetails = null,
                        createdAt = System.currentTimeMillis() // Reset creation time or update time
                    )
                    downloadDao.insertOrUpdateTask(existingTask) // Update the existing task
                }

                DownloadStatus.PAUSED, DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> {
                    Timber.i("Task ${existingTask.id} is already ${existingTask.status}. Not re-enqueueing. Emitting current state.")
                    _downloadProgressFlow.tryEmit(
                        DownloadProgress(
                            existingTask.id,
                            existingTask.downloadedBytes,
                            existingTask.totalBytes,
                            existingTask.status,
                            existingTask.errorDetails?.let { IOException(it) })
                    )
                    return existingTask.id
                }
            }
        } else {
            // No existing task, create a new one
            existingTask = DownloadTask(url = url, filePath = filePath, fileName = fileName)
            downloadDao.insertOrUpdateTask(existingTask) // Insert the new task
        }

        // Retrieve the task again to ensure we have the version from the DB (especially its ID if new)
        val taskToProcess = downloadDao.getTaskByFilePath(filePath) ?: throw IllegalStateException("Failed to save or retrieve task after enqueue: $filePath")

        Timber.i("Task ${taskToProcess.id} (${taskToProcess.fileName}) processed for enqueue. Status: ${taskToProcess.status}. URL: ${taskToProcess.url}")
        _downloadProgressFlow.tryEmit(DownloadProgress(taskToProcess.id, taskToProcess.downloadedBytes, taskToProcess.totalBytes, taskToProcess.status))

        if (taskToProcess.status == DownloadStatus.PENDING) { // Only send PENDING to channel
            if (!isNetworkConnected) {
                Timber.w("Network is not connected. Task ${taskToProcess.id} (${taskToProcess.fileName}) will be marked as PAUSED (due to network).")
                updateTaskStatus(taskToProcess.id, DownloadStatus.PAUSED, isNetworkPaused = true, error = IOException("Network unavailable at enqueue time"))
            } else {
                Timber.d("Network connected. Task ${taskToProcess.id} (${taskToProcess.fileName}) is PENDING, adding to queue.")
                taskQueueChannel.send(taskToProcess.id)
            }
        }
        return taskToProcess.id
    }

    suspend fun pauseDownload(taskId: String, byUser: Boolean = true) {
        checkInitialized()
        Timber.i("Attempting to pause download for task $taskId by ${if (byUser) "user" else "system"}.")
        activeDownloads[taskId]?.cancel(CancellationException("Download paused by ${if (byUser) "user" else "system"}"))

        val task = downloadDao.getTaskById(taskId)
        if (task != null) {
            if (task.status == DownloadStatus.PENDING || task.status == DownloadStatus.DOWNLOADING) {
                val newIsNetworkPaused = if (byUser) false else task.isPausedByNetwork
                updateTaskStatus(taskId, DownloadStatus.PAUSED, isNetworkPaused = newIsNetworkPaused, error = if (byUser) null else task.errorDetails?.let { IOException(it) })
                Timber.i("Task $taskId paused successfully. IsNetworkPaused set to: $newIsNetworkPaused")
            } else {
                Timber.w("Task $taskId cannot be paused. Current status: ${task.status}. Emitting current state.")
                _downloadProgressFlow.tryEmit(DownloadProgress(task.id, task.downloadedBytes, task.totalBytes, task.status, task.errorDetails?.let { IOException(it) }))
            }
        } else {
            Timber.w("Task $taskId not found for pausing.")
        }
    }

    suspend fun resumeDownload(taskId: String) {
        checkInitialized()
        val task = downloadDao.getTaskById(taskId)
        Timber.i("Attempting to resume download for task $taskId. Current DB status: ${task?.status}, isPausedByNetwork: ${task?.isPausedByNetwork}, error: ${task?.errorDetails}")

        if (task != null) {
            if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                if (!isNetworkConnected) {
                    Timber.w("Cannot resume task $taskId, network is disconnected. Ensuring it's marked as network-paused.")
                    if (!(task.status == DownloadStatus.PAUSED && task.isPausedByNetwork)) {
                        updateTaskStatus(taskId, DownloadStatus.PAUSED, isNetworkPaused = true, error = IOException("Attempted resume while network offline. Original error: ${task.errorDetails}"))
                    } else {
                        _downloadProgressFlow.tryEmit(
                            DownloadProgress(
                                task.id,
                                task.downloadedBytes,
                                task.totalBytes,
                                task.status,
                                IOException("Network unavailable. Original error: ${task.errorDetails}")
                            )
                        )
                    }
                    return
                }

                Timber.d("Network connected. Setting task $taskId to PENDING for resumption.")
                // Clear isPausedByNetwork and errorDetails when resuming/retrying
                updateTaskStatus(taskId, DownloadStatus.PENDING, isNetworkPaused = false, error = null)

                // The task status is now PENDING in DB. Add to channel.
                // Re-fetch is not strictly necessary here if updateTaskStatus already emitted,
                // but taskQueueChannel.send uses the taskId.
                val taskAfterPendingUpdate = downloadDao.getTaskById(taskId)
                if (taskAfterPendingUpdate?.status == DownloadStatus.PENDING) {
                    taskQueueChannel.send(taskId)
                    Timber.i("Task $taskId set to PENDING and added to queue for resumption.")
                } else {
                    Timber.w("Task $taskId was set to PENDING, but its status is now ${taskAfterPendingUpdate?.status}. Not adding to queue. This might indicate a rapid concurrent update.")
                }

            } else {
                Timber.w("Task $taskId cannot be resumed from current status: ${task.status}. Emitting current state.")
                _downloadProgressFlow.tryEmit(DownloadProgress(task.id, task.downloadedBytes, task.totalBytes, task.status, task.errorDetails?.let { IOException(it) }))
            }
        } else {
            Timber.w("Task $taskId not found for resuming.")
        }
    }

    suspend fun cancelDownload(taskId: String) {
        checkInitialized()
        Timber.i("Attempting to cancel download for task $taskId.")
        activeDownloads[taskId]?.cancel(CancellationException("Download cancelled by user"))
        // Task processor might still pick it up if it was PENDING and immediately process its cancellation.
        // It's important that executeDownload also checks for status.

        val task = downloadDao.getTaskById(taskId)
        if (task != null) {
            // Update status to CANCELLED first.
            updateTaskStatus(taskId, DownloadStatus.CANCELLED, isNetworkPaused = false, error = null)

            // Then delete the file.
            try {
                val file = File(task.filePath)
                if (file.exists()) {
                    if (file.delete()) {
                        Timber.d("Deleted file for cancelled task $taskId at ${task.filePath}")
                    } else {
                        Timber.w("Failed to delete file for cancelled task $taskId at ${task.filePath}")
                    }
                } else {
                    Timber.d("File for cancelled task $taskId not found at ${task.filePath}, no deletion needed.")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error deleting file for cancelled task $taskId")
            }
            Timber.i("Task $taskId cancelled successfully.")
        } else {
            Timber.w("Task $taskId not found for cancellation.")
        }
    }

    suspend fun retryDownload(taskId: String) {
        checkInitialized()
        Timber.i("Retrying download for task $taskId.")
        // resumeDownload will handle FAILED or PAUSED state and set it to PENDING.
        // It also clears error details and isPausedByNetwork.
        resumeDownload(taskId)
    }

    private suspend fun updateTaskStatus(taskId: String, newStatus: DownloadStatus, isNetworkPaused: Boolean = false, error: Throwable? = null) {
        checkInitialized()

        val currentTaskBeforeUpdate = downloadDao.getTaskById(taskId)
        val errorMsg = error?.message ?: if (newStatus == DownloadStatus.FAILED && currentTaskBeforeUpdate?.errorDetails == null) {
            "Unknown error" // 如果变为 FAILED 且没有提供新错误，也没有旧错误，则给个默认值
        } else {
            currentTaskBeforeUpdate?.errorDetails // 保留已有的错误信息，除非被新的错误覆盖
        }

        // 更新数据库
        downloadDao.updateStatus(taskId, newStatus, isNetworkPaused, errorMsg)

        // 为了发射最准确的进度，在更新数据库状态后，重新获取任务
        val updatedTask = downloadDao.getTaskById(taskId)
        if (updatedTask != null) {
            _downloadProgressFlow.tryEmit(
                DownloadProgress(
                    updatedTask.id,
                    updatedTask.downloadedBytes,
                    updatedTask.totalBytes,
                    newStatus, // 使用传入的 newStatus，因为 updatedTask.status 可能还未在事务中完全同步
                    error ?: updatedTask.errorDetails?.let { IOException(it) } // 使用新错误，或DB中的错误
                )
            )
            Timber.d("Task ${updatedTask.id} status updated to $newStatus. IsNetworkPaused: $isNetworkPaused. Emitted progress: ${updatedTask.downloadedBytes}/${updatedTask.totalBytes}. Error: ${errorMsg ?: "null"}")

            // 如果任务进入终态 (FAILED, CANCELLED)，从 activeDownloads 中移除 Job (如果存在)
            if (newStatus == DownloadStatus.FAILED || newStatus == DownloadStatus.CANCELLED) {
                activeDownloads.remove(updatedTask.id)?.let {
                    Timber.d("Job for task ${updatedTask.id} (now $newStatus) was removed from activeDownloads map in updateTaskStatus as it's a final state.")
                }
            }
        } else {
            Timber.w("Task $taskId not found after status update to $newStatus, cannot emit definitive progress. Emitting based on provided status and old data if available.")
            // 即使任务找不到了，如果 newStatus 是一个终态，也尝试为 taskId 发射一个简单的状态
            // 这有助于UI更新，即使数据不完整
            _downloadProgressFlow.tryEmit(
                DownloadProgress(
                    taskId,
                    currentTaskBeforeUpdate?.downloadedBytes ?: 0L, // 尝试使用旧值
                    currentTaskBeforeUpdate?.totalBytes ?: 0L,   // 尝试使用旧值
                    newStatus,
                    error ?: currentTaskBeforeUpdate?.errorDetails?.let { IOException(it) }
                )
            )
        }
    }

    // ... (DownloadManager 的其他部分代码保持不变) ...

    private suspend fun executeDownload(initialTaskStateFromQueue: DownloadTask) {
        checkInitialized()
        var currentTask: DownloadTask // Definitive task object

        // 1. Re-fetch task from DB
        val taskFromDb = downloadDao.getTaskById(initialTaskStateFromQueue.id)
        if (taskFromDb == null) {
            Timber.e("Task ${initialTaskStateFromQueue.id} not found in DB at executeDownload start.")
            return
        }
        currentTask = taskFromDb
        Timber.i("executeDownload for task ${currentTask.id} (${currentTask.fileName}). URL: ${currentTask.url}. Initial DB status: ${currentTask.status}")

        // 2. Verify startable state
        if (currentTask.status != DownloadStatus.PENDING || currentTask.isPausedByNetwork) {
            Timber.w("Task ${currentTask.id} not startable (DB Status: ${currentTask.status}, isPausedByNetwork: ${currentTask.isPausedByNetwork}). Aborting.")
            // Optionally, emit current state if it's an error state the UI might want to know about
            if (currentTask.status == DownloadStatus.FAILED || currentTask.status == DownloadStatus.PAUSED) {
                _downloadProgressFlow.tryEmit(
                    DownloadProgress(
                        currentTask.id,
                        currentTask.downloadedBytes,
                        currentTask.totalBytes,
                        currentTask.status,
                        currentTask.errorDetails?.let { IOException(it) }
                    )
                )
            }
            return
        }

        // 3. Update status to DOWNLOADING
        updateTaskStatus(currentTask.id, DownloadStatus.DOWNLOADING, isNetworkPaused = false)

        // 4. Re-fetch after setting to DOWNLOADING to get the most current state
        val taskAfterUpdateToDownloading = downloadDao.getTaskById(currentTask.id)
        if (taskAfterUpdateToDownloading == null) {
            Timber.e("Task ${currentTask.id} disappeared after status update to DOWNLOADING.")
            // Consider this a failure, as we can't proceed
            updateTaskStatus(initialTaskStateFromQueue.id, DownloadStatus.FAILED, error = IOException("Task disappeared after status update to DOWNLOADING"))
            return
        }
        if (taskAfterUpdateToDownloading.status != DownloadStatus.DOWNLOADING) {
            Timber.w("Task ${currentTask.id} status is ${taskAfterUpdateToDownloading.status} (not DOWNLOADING) after DB update. Error: ${taskAfterUpdateToDownloading.errorDetails}. Aborting executeDownload.")
            // The status might have been changed by a concurrent operation (e.g. cancel).
            // Emit the current state from DB.
            _downloadProgressFlow.tryEmit(
                DownloadProgress(
                    taskAfterUpdateToDownloading.id,
                    taskAfterUpdateToDownloading.downloadedBytes,
                    taskAfterUpdateToDownloading.totalBytes,
                    taskAfterUpdateToDownloading.status,
                    taskAfterUpdateToDownloading.errorDetails?.let { IOException(it) }
                )
            )
            return
        }
        currentTask = taskAfterUpdateToDownloading
        Timber.d("Task ${currentTask.id} confirmed DOWNLOADING. File: ${currentTask.fileName}. Current downloaded: ${currentTask.downloadedBytes}")

        val requestBuilder = Request.Builder().url(currentTask.url)
        var expectedBytesToReceiveThisSession: Long = -1 // 用于跟踪本次响应期望接收的字节数

        if (currentTask.downloadedBytes > 0) {
            requestBuilder.addHeader("Range", "bytes=${currentTask.downloadedBytes}-")
            currentTask.eTag?.let { requestBuilder.addHeader("If-Range", it) }
                ?: currentTask.lastModified?.let { requestBuilder.addHeader("If-Range", it) }
            Timber.d("Task ${currentTask.id}: Resuming from ${currentTask.downloadedBytes} bytes.")
        }

        var response: Response? = null
        var randomAccessFile: RandomAccessFile? = null
        var bytesActuallyWrittenThisSession: Long = 0

        try {
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Download job ${currentTask.id} cancelled before network request.")
            }

            val request = requestBuilder.build()
            Timber.d("Task ${currentTask.id}: Executing HTTP request to ${request.url}")
            response = okHttpClient.newCall(request).execute()
            Timber.d("Task ${currentTask.id}: Received response, code: ${response.code}")

            // Re-check task status and coroutine status before writing to file
            val taskStateBeforeWrite = downloadDao.getTaskById(currentTask.id) // Get latest from DB
            if (!currentCoroutineContext().isActive || taskStateBeforeWrite == null || taskStateBeforeWrite.status != DownloadStatus.DOWNLOADING) {
                Timber.w("Task ${currentTask.id} cancelled or status changed (DB: ${taskStateBeforeWrite?.status}) during/after network op. Aborting write.")
                response.close()
                if (taskStateBeforeWrite == null) {
                    updateTaskStatus(currentTask.id, DownloadStatus.FAILED, error = IOException("Task disappeared during network operation"))
                } else if (taskStateBeforeWrite.status != DownloadStatus.CANCELLED && taskStateBeforeWrite.status != DownloadStatus.PAUSED) {
                    // If it wasn't explicitly cancelled or paused by another mechanism, but is no longer DOWNLOADING,
                    // it might be an unexpected state change. Consider it FAILED.
                    // updateTaskStatus(currentTask.id, DownloadStatus.FAILED, error = IOException("Task status changed unexpectedly during network op to ${taskStateBeforeWrite.status}"))
                    // No, better to let the existing status from DB prevail if it's a final one like CANCELLED/PAUSED
                    Timber.i("Task ${currentTask.id} status already ${taskStateBeforeWrite.status}, letting it be.")
                }
                return
            }
            currentTask = taskStateBeforeWrite // Refresh currentTask with the latest from DB before proceeding

            if (!response.isSuccessful) {
                if (response.code == 416 && currentTask.downloadedBytes > 0) {
                    Timber.w("Task ${currentTask.id}: HTTP 416 Range Not Satisfiable. Downloaded: ${currentTask.downloadedBytes}.")
                    val contentRange = response.header("Content-Range")
                    val serverTotalSize = contentRange?.substringAfterLast('/')?.toLongOrNull()

                    if (serverTotalSize != null && currentTask.downloadedBytes >= serverTotalSize) {
                        Timber.i("Task ${currentTask.id}: HTTP 416 confirms download complete. Server total: $serverTotalSize. Marking COMPLETED.")
                        if (currentTask.totalBytes != serverTotalSize || currentTask.downloadedBytes != serverTotalSize) {
                            downloadDao.updateProgress(currentTask.id, serverTotalSize, serverTotalSize)
                        }
                        updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED, isNetworkPaused = false)
                    } else {
                        Timber.w("Task ${currentTask.id}: HTTP 416, but downloaded (${currentTask.downloadedBytes}) inconsistent with server total ($serverTotalSize). File may have changed or range invalid. Resetting.")
                        val error = IOException("Range not satisfiable (416). File may have changed or range invalid. ServerTotal: $serverTotalSize. Please retry from beginning.")
                        downloadDao.updateProgress(currentTask.id, 0L, 0L)
                        downloadDao.updateETagAndLastModified(currentTask.id, null, null)
                        updateTaskStatus(currentTask.id, DownloadStatus.FAILED, isNetworkPaused = false, error = error)
                    }
                    response.close()
                    return
                }
                val errorBody = response.body?.string() ?: "No error body"
                Timber.e("Task ${currentTask.id}: Unsuccessful HTTP response ${response.code} ${response.message}. Body: $errorBody")
                response.close() // Close response before throwing
                throw IOException("Unexpected server response ${response.code} ${response.message} for task ${currentTask.id}. Body: $errorBody")
            }

            val responseBody = response.body ?: throw IOException("Response body is null for task ${currentTask.id}")
            val newETag = response.header("ETag")
            val newLastModified = response.header("Last-Modified")

            var serverReportedTotalBytes = currentTask.totalBytes
            var dbDownloadedBytesSnapshot = currentTask.downloadedBytes // Snapshot of DB downloaded bytes before this session's writes

            if (response.code == 206) { // Partial Content
                Timber.d("Task ${currentTask.id}: Resumed download (HTTP 206).")
                val contentRange = response.header("Content-Range")
                val serverTotalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
                if (serverTotalFromRange != null && serverTotalFromRange > 0) {
                    serverReportedTotalBytes = serverTotalFromRange
                }
                expectedBytesToReceiveThisSession = responseBody.contentLength() // Length of the partial content
            } else { // HTTP 200 (Full content)
                Timber.d("Task ${currentTask.id}: Starting new download (HTTP ${response.code}). Resetting local downloaded count for this session.")
                dbDownloadedBytesSnapshot = 0L // For full download, previous progress is irrelevant for *this session's* start point
                if (currentTask.downloadedBytes > 0) { // If DB had progress but we got HTTP 200, server didn't support/accept If-Range or Range
                    Timber.w("Task ${currentTask.id}: Server returned HTTP 200 despite existing progress. Resetting downloaded bytes in DB to 0.")
                    downloadDao.updateDownloadedBytes(currentTask.id, 0L) // Crucial: reset DB progress
                    currentTask = currentTask.copy(downloadedBytes = 0L) // Update in-memory task
                }
                serverReportedTotalBytes = responseBody.contentLength()
                expectedBytesToReceiveThisSession = serverReportedTotalBytes
            }

            var taskMetaChanged = false
            if (newETag != currentTask.eTag || newLastModified != currentTask.lastModified) {
                downloadDao.updateETagAndLastModified(currentTask.id, newETag, newLastModified)
                taskMetaChanged = true
            }
            if (serverReportedTotalBytes > 0 && serverReportedTotalBytes != currentTask.totalBytes) {
                downloadDao.updateTotalBytes(currentTask.id, serverReportedTotalBytes)
                taskMetaChanged = true
            }

            if (taskMetaChanged) { // If ETag, LastModified or TotalBytes changed, re-fetch task
                val refreshedTask = downloadDao.getTaskById(currentTask.id)
                if (refreshedTask != null) {
                    currentTask = refreshedTask
                    // Ensure dbDownloadedBytesSnapshot is consistent with the (potentially reset) currentTask.downloadedBytes
                    dbDownloadedBytesSnapshot = currentTask.downloadedBytes
                } else {
                    Timber.e("Task ${currentTask.id} disappeared from DB after meta update. Aborting.")
                    response.close()
                    throw IOException("Task ${currentTask.id} disappeared after metadata update")
                }
            }


            val file = File(currentTask.filePath)
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    response.close()
                    throw IOException("Failed to create directory: ${parentDir.absolutePath} for task ${currentTask.id}")
                }
            }

            randomAccessFile = RandomAccessFile(file, "rw")
            randomAccessFile.seek(currentTask.downloadedBytes) // Seek to where new data should be written

            val buffer = ByteArray(64 * 1024) // 64KB buffer, can be tuned
            var bytesRead: Int
            var lastUiEmitTime = System.currentTimeMillis()
            var bytesSinceLastDbUpdate: Long = 0
            val dbUpdateThresholdBytes: Long = 1 * 1024 * 1024 // 1MB, tune this value

            responseBody.byteStream().use { inputStream ->
                while (true) { // Loop indefinitely until break or exception
                    if (!currentCoroutineContext().isActive) {
                        Timber.i("Task ${currentTask.id} cancel detected by coroutine inactive in read loop.")
                        throw CancellationException("Download ${currentTask.id} cancelled (coroutine scope).")
                    }

                    // Periodically check task status in DB to react to external pause/cancel
                    // This check can be less frequent than UI updates
                    if (System.currentTimeMillis() - lastUiEmitTime > 2000) { // e.g., every 2 seconds check DB status
                        val taskStateInLoop = downloadDao.getTaskById(currentTask.id)
                        if (taskStateInLoop == null || taskStateInLoop.status != DownloadStatus.DOWNLOADING) {
                            Timber.w("Task ${currentTask.id} status changed in DB to ${taskStateInLoop?.status} during download. Aborting write loop.")
                            // If it's null, something went wrong, let it fail or be handled by outer catches
                            // If status changed, it means another part of the app (e.g. user pause/cancel) modified it.
                            // The loop should terminate to respect that.
                            break // Exit the while loop, subsequent logic will handle final state.
                        }
                    }

                    bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) {
                        break // End of stream
                    }
                    if (bytesRead == 0) continue

                    randomAccessFile.write(buffer, 0, bytesRead)
                    bytesActuallyWrittenThisSession += bytesRead
                    bytesSinceLastDbUpdate += bytesRead

                    val currentTotalDownloadedInMemory = currentTask.downloadedBytes + bytesActuallyWrittenThisSession

                    // DB Update Logic (based on data volume)
                    if (bytesSinceLastDbUpdate >= dbUpdateThresholdBytes) {
                        downloadDao.updateDownloadedBytes(currentTask.id, currentTotalDownloadedInMemory)
                        bytesSinceLastDbUpdate = 0 // Reset counter
                        Timber.v("Task ${currentTask.id} DB progress: $currentTotalDownloadedInMemory/${currentTask.totalBytes}")
                    }

                    // UI Flow Emit Logic (based on time for smoother UI)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUiEmitTime >= 1000) { // Emit every 1 second
                        _downloadProgressFlow.tryEmit(
                            DownloadProgress(
                                currentTask.id,
                                currentTotalDownloadedInMemory,
                                currentTask.totalBytes, // Use the totalBytes known by currentTask
                                DownloadStatus.DOWNLOADING,
                                null
                            )
                        )
                        lastUiEmitTime = currentTime
                    }
                }
            } // inputStream.use closes it

            // After download loop finishes (either by -1 from read, or break due to status change)
            val finalDownloadedBytesInDb = currentTask.downloadedBytes + bytesActuallyWrittenThisSession

            // Ensure any remaining progress is written to DB
            if (bytesSinceLastDbUpdate > 0) {
                downloadDao.updateDownloadedBytes(currentTask.id, finalDownloadedBytesInDb)
                Timber.d("Task ${currentTask.id}: Final DB progress after loop: $finalDownloadedBytesInDb")
            }

            // Final state check and update
            // Re-fetch the task one last time to get the absolute latest status that might have been set by another operation
            // (e.g. if the periodic DB check in the loop caused a 'break')
            val finalTaskStateFromDb = downloadDao.getTaskById(currentTask.id)
            if (finalTaskStateFromDb == null) {
                Timber.e("Task ${currentTask.id} disappeared before final status update.")
                // This is an error, but data might have been written.
                // We don't have a task record to update to FAILED.
                // Consider emitting a generic error for this taskId if possible.
                _downloadProgressFlow.tryEmit(DownloadProgress(currentTask.id, finalDownloadedBytesInDb, currentTask.totalBytes, DownloadStatus.FAILED, IOException("Task record disappeared")))
                return
            }

            // If the status is still DOWNLOADING, it means the download completed naturally (EOF)
            // or was interrupted by coroutine cancellation *within this execution* but not by an external DB status change.
            if (finalTaskStateFromDb.status == DownloadStatus.DOWNLOADING) {
                val knownTotalBytes = finalTaskStateFromDb.totalBytes // Use latest total from DB
                if (knownTotalBytes > 0) {
                    if (finalDownloadedBytesInDb < knownTotalBytes) {
                        if (expectedBytesToReceiveThisSession != -1L && bytesActuallyWrittenThisSession < expectedBytesToReceiveThisSession) {
                            val errMsg =
                                "Incomplete download: $finalDownloadedBytesInDb/$knownTotalBytes. Expected ${expectedBytesToReceiveThisSession} this session, got $bytesActuallyWrittenThisSession."
                            Timber.e("Task ${currentTask.id}: $errMsg")
                            updateTaskStatus(currentTask.id, DownloadStatus.FAILED, isNetworkPaused = false, error = IOException(errMsg))
                        } else {
                            // EOF reached, but less than total. This can happen if Content-Length was wrong or connection dropped without error.
                            val errMsg = "EOF reached but downloaded bytes ($finalDownloadedBytesInDb) are less than total bytes ($knownTotalBytes)."
                            Timber.w("Task ${currentTask.id}: $errMsg - Marking FAILED.")
                            updateTaskStatus(currentTask.id, DownloadStatus.FAILED, isNetworkPaused = false, error = IOException(errMsg))
                        }
                    } else if (finalDownloadedBytesInDb > knownTotalBytes) {
                        Timber.w("Task ${currentTask.id}: Downloaded more ($finalDownloadedBytesInDb) than total ($knownTotalBytes). Correcting to total and marking COMPLETED.")
                        downloadDao.updateProgress(currentTask.id, knownTotalBytes, knownTotalBytes)
                        updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED, isNetworkPaused = false)
                    } else { // finalDownloadedBytesInDb == knownTotalBytes
                        Timber.i("Task ${currentTask.id} completed successfully. Downloaded: $finalDownloadedBytesInDb / $knownTotalBytes")
                        // No need to call downloadDao.updateProgress again if updateDownloadedBytes already set it correctly
                        // and totalBytes hasn't changed. But to be safe:
                        downloadDao.updateProgress(currentTask.id, finalDownloadedBytesInDb, knownTotalBytes)
                        updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED, isNetworkPaused = false)
                    }
                } else { // totalBytes unknown (or <= 0)
                    Timber.i("Task ${currentTask.id} finished (total size initially unknown). Downloaded: $finalDownloadedBytesInDb. Marking COMPLETED.")
                    downloadDao.updateProgress(currentTask.id, finalDownloadedBytesInDb, finalDownloadedBytesInDb) // Set total to what was downloaded
                    updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED, isNetworkPaused = false)
                }
            } else {
                // Status was changed by an external factor (e.g. user pause/cancel) detected by the loop's DB check,
                // or by cancellation before the loop finished normally.
                // The status in finalTaskStateFromDb is the one to respect.
                Timber.i("Task ${currentTask.id}: Download loop ended. Final DB status is ${finalTaskStateFromDb.status}. Emitting this state.")
                _downloadProgressFlow.tryEmit(
                    DownloadProgress(
                        finalTaskStateFromDb.id,
                        finalTaskStateFromDb.downloadedBytes, // Use actual DB values
                        finalTaskStateFromDb.totalBytes,
                        finalTaskStateFromDb.status,
                        finalTaskStateFromDb.errorDetails?.let { IOException(it) }
                    )
                )
            }

        } catch (e: CancellationException) {
            Timber.i(e, "Download cancelled for task ${currentTask.id}: ${e.message}")
            // The job was cancelled. We need to ensure the task in DB reflects a non-DOWNLOADING state.
            // PAUSED is a common state for user-initiated cancellations if we want to allow resumption.
            // If it's a true "cancel and delete" scenario, then status should be CANCELLED.
            // Here, using PAUSED as a general "stopped by cancellation" state.
            // The handleDownloadEndState function was a good idea, let's refine it.
            handleCancellationOrError(currentTask.id, DownloadStatus.PAUSED, e, false)
        } catch (e: IOException) {
            Timber.e(e, "IOException during download for task ${currentTask.id}: ${e.message}")
            handleCancellationOrError(currentTask.id, DownloadStatus.FAILED, e, !isNetworkConnected)
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error during download for task ${currentTask.id}: ${e.message}")
            handleCancellationOrError(currentTask.id, DownloadStatus.FAILED, e, !isNetworkConnected)
        } finally {
            try {
                randomAccessFile?.close()
            } catch (e: IOException) {
                Timber.e(e, "Error closing randomAccessFile for task ${currentTask.id}")
            }
            try {
                response?.close()
            } catch (e: Exception) { // OkHttp's close can throw RuntimeException on thread interrupt
                Timber.e(e, "Error closing response for task ${currentTask.id}")
            }
            Timber.d("executeDownload for task ${currentTask.id} finished its execution path.")
            // Remove from activeDownloads if it's still there (e.g. if an exception occurred before it was removed)
            // Note: updateTaskStatus to a final state (FAILED, CANCELLED, COMPLETED) should already remove it.
            // This is a safeguard.
            activeDownloads.remove(currentTask.id)?.let {
                Timber.d("Job for task ${currentTask.id} explicitly removed from activeDownloads map in finally block of executeDownload.")
            }
        }
    }

    private suspend fun handleCancellationOrError(
        taskId: String,
        statusToSet: DownloadStatus,
        error: Throwable?,
        isNetworkIssue: Boolean
    ) {
        checkInitialized()
        val currentTaskState = downloadDao.getTaskById(taskId)
        if (currentTaskState != null) {
            // Only update status if it's currently in an active downloading state
            // or if we are setting FAILED (which can override a PAUSED if error occurs on retry)
            if (currentTaskState.status == DownloadStatus.DOWNLOADING || currentTaskState.status == DownloadStatus.PENDING ||
                (statusToSet == DownloadStatus.FAILED && currentTaskState.status == DownloadStatus.PAUSED)
            ) {
                Timber.w("Task $taskId was ${currentTaskState.status}, setting to $statusToSet due to: ${error?.message}")
                updateTaskStatus(
                    taskId,
                    statusToSet,
                    isNetworkPaused = if (statusToSet == DownloadStatus.PAUSED && isNetworkIssue) true else currentTaskState.isPausedByNetwork, // Preserve isNetworkPaused if not explicitly setting it
                    error = error
                )
            } else {
                Timber.i("Task $taskId encountered error/cancellation. Its current status is ${currentTaskState.status}. Not overriding with $statusToSet from executeDownload's catch block. Error was: ${error?.message}")
                // Still emit the current state from DB so UI is consistent
                _downloadProgressFlow.tryEmit(
                    DownloadProgress(
                        currentTaskState.id,
                        currentTaskState.downloadedBytes,
                        currentTaskState.totalBytes,
                        currentTaskState.status,
                        currentTaskState.errorDetails?.let { IOException(it) } ?: error?.let { IOException(it.message, it) }
                    )
                )
            }
        } else {
            Timber.e("Task $taskId not found in DB when handling end state after error: ${error?.message}")
            _downloadProgressFlow.tryEmit(
                DownloadProgress(
                    taskId,
                    0, 0, // Unknown progress
                    statusToSet, // Report the intended status
                    error
                )
            )
        }
    }

    /**
     * Helper function to handle the final state update of a download task after an exception or cancellation.
     * It checks the task's current status in the database before deciding on the final status.
     */
    private suspend fun handleDownloadEndState(
        taskId: String,
        expectedStatusBeforeEnd: DownloadStatus,
        statusToSetOnErrorOrCancel: DownloadStatus,
        error: Throwable?,
        isNetworkIssue: Boolean
    ) {
        checkInitialized()
        val taskStateAfterError = downloadDao.getTaskById(taskId)
        if (taskStateAfterError != null) {
            if (taskStateAfterError.status == expectedStatusBeforeEnd) {
                Timber.w("Task $taskId was in $expectedStatusBeforeEnd, setting to $statusToSetOnErrorOrCancel due to: ${error?.message}")
                updateTaskStatus(taskId, statusToSetOnErrorOrCancel, isNetworkPaused = if (statusToSetOnErrorOrCancel == DownloadStatus.PAUSED) isNetworkIssue else false, error = error)
            } else {
                Timber.w("Task $taskId encountered error/cancellation, but its status is already ${taskStateAfterError.status}. Not overriding from executeDownload's catch block. Error was: ${error?.message}")
            }
        } else {
            Timber.e("Task $taskId not found in DB when handling end state after error: ${error?.message}")
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
                    response.body?.source()?.use { source ->
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
}
