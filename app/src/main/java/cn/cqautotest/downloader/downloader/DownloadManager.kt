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
     * 将新的下载请求加入队列，或处理针对同一文件的现有任务。
     *
     * 如果相同 filePath 的任务已存在：
     * - 如果是 COMPLETED：发出进度并返回现有 ID。
     * - 如果是 FAILED 或 CANCELLED：重置任务并将其重新排队为 PENDING。
     * - 如果是 PAUSED、DOWNLOADING 或 PENDING：发出进度并返回现有 ID (不重新排队)。
     *
     * 如果不存在任务，则创建一个新任务，保存它，并将其加入队列。
     *
     * @param url 要下载的文件的 URL。
     * @param dirPath 文件应保存到的目录路径。
     * @param fileName 文件名。
     * @return 下载任务的 ID。
     * @throws IOException 如果无法创建目录。
     * @throws IllegalStateException 如果在入队后无法保存或检索任务。
     */
    suspend fun enqueueNewDownload(url: String, dirPath: String, fileName: String): String {
        checkInitialized() // 检查 DownloadManager 是否已初始化
        val directory = File(dirPath)
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                Timber.e("创建目录失败: $dirPath")
                throw IOException("创建目录失败: $dirPath")
            }
        }
        val filePath = File(dirPath, fileName).absolutePath
        var existingTask = downloadDao.getTaskByFilePath(filePath) // 根据文件路径查找现有任务

        if (existingTask != null) {
            Timber.i("文件 '$fileName' 在 '$dirPath' 的任务已存在，ID 为 ${existingTask.id}，状态为: ${existingTask.status}。")
            when (existingTask.status) {
                DownloadStatus.COMPLETED -> {
                    Timber.i("任务 ${existingTask.id} 已完成。发出进度并返回现有 ID。")
                    // 发送当前已完成的状态和进度
                    _downloadProgressFlow.tryEmit(
                        DownloadProgress(
                            existingTask.id,
                            existingTask.downloadedBytes,
                            existingTask.totalBytes,
                            existingTask.status
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
                    downloadDao.insertOrUpdateTask(existingTask) // 更新数据库中的任务
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
                            existingTask.errorDetails?.let { IOException(it) } // 如果有错误，也一并发出
                        )
                    )
                    return existingTask.id // 返回现有任务ID
                }
            }
        } else {
            // 没有找到现有任务，创建一个新的下载任务
            Timber.i("文件 '$fileName' 在 '$dirPath' 不存在现有任务。正在创建新任务。")
            existingTask = DownloadTask(url = url, filePath = filePath, fileName = fileName)
            downloadDao.insertOrUpdateTask(existingTask) // 将新任务插入数据库
        }

        // 无论是更新的旧任务还是新创建的任务，都需要从数据库重新获取一次
        // 这样可以确保我们拥有的是包含正确ID（特别是对于新任务）和所有数据库默认值的最新版本
        val taskToProcess = downloadDao.getTaskByFilePath(filePath)
            ?: throw IllegalStateException("在入队后无法保存或检索任务: $filePath")

        Timber.i("任务 ${taskToProcess.id} (${taskToProcess.fileName}) 已处理并准备入队。状态: ${taskToProcess.status}。 URL: ${taskToProcess.url}")
        // 发出任务的初始状态 (通常是 PENDING)
        _downloadProgressFlow.tryEmit(
            DownloadProgress(
                taskToProcess.id,
                taskToProcess.downloadedBytes,
                taskToProcess.totalBytes,
                taskToProcess.status
            )
        )

        // 仅当任务确实处于 PENDING 状态时才将其发送到处理队列
        if (taskToProcess.status == DownloadStatus.PENDING) {
            if (!isNetworkConnected) {
                // 如果当前没有网络连接，则不将任务放入下载队列，而是将其标记为因网络暂停
                Timber.w("网络未连接。任务 ${taskToProcess.id} (${taskToProcess.fileName}) 将被标记为 PAUSED (因网络原因)。")
                updateTaskStatus(
                    taskToProcess.id,
                    DownloadStatus.PAUSED,
                    isNetworkPaused = true,
                    error = IOException("入队时网络不可用")
                )
            } else {
                // 网络已连接，将任务 ID 发送到 taskQueueChannel，由任务处理器协程处理
                Timber.d("网络已连接。任务 ${taskToProcess.id} (${taskToProcess.fileName}) 状态为 PENDING，正在添加到队列。")
                taskQueueChannel.send(taskToProcess.id)
            }
        }
        return taskToProcess.id // 返回处理后的任务 ID
    }

    /**
     * 暂停指定的下载任务。
     *
     * @param taskId 要暂停的任务的 ID。
     * @param byUser 指示暂停是由用户（true）还是系统（false，例如网络断开）发起的。
     *               这会影响暂停时是否保留网络暂停标记和错误详情。
     */
    suspend fun pauseDownload(taskId: String, byUser: Boolean = true) {
        checkInitialized() // 检查 DownloadManager 是否已初始化
        Timber.i("尝试暂停任务 $taskId，操作者: ${if (byUser) "用户" else "系统"}.")

        // 取消与此任务关联的活动下载 Job (如果存在)
        // 这将中断 executeDownload 中的下载循环 (如果正在运行)
        activeDownloads[taskId]?.cancel(CancellationException("下载被 ${if (byUser) "用户" else "系统"} 暂停"))

        val task = downloadDao.getTaskById(taskId) // 从数据库获取任务信息
        if (task != null) {
            // 仅当任务当前处于 PENDING 或 DOWNLOADING 状态时才将其更新为 PAUSED
            // 其他状态 (如 COMPLETED, FAILED, CANCELLED, PAUSED) 不应由此方法更改为 PAUSED
            if (task.status == DownloadStatus.PENDING || task.status == DownloadStatus.DOWNLOADING) {
                // 如果是用户暂停，则 isNetworkPaused 应为 false，且不应设置新的 error
                // 如果是系统暂停 (例如网络断开)，则保留之前的 isPausedByNetwork 状态 (通常应为 true)
                // 和之前的 errorDetails (如果存在)
                val newIsNetworkPaused = if (byUser) false else task.isPausedByNetwork
                val errorForStatusUpdate = if (byUser) {
                    null // 用户暂停时不记录错误
                } else {
                    task.errorDetails?.let { IOException(it) } // 系统暂停时保留现有错误
                }

                updateTaskStatus(
                    taskId,
                    DownloadStatus.PAUSED,
                    isNetworkPaused = newIsNetworkPaused,
                    error = errorForStatusUpdate
                )
                Timber.i("任务 $taskId 成功暂停。isNetworkPaused 设置为: $newIsNetworkPaused")
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
                        task.errorDetails?.let { IOException(it) }
                    )
                )
            }
        } else {
            Timber.w("未找到要暂停的任务 $taskId。")
        }
    }

    /**
     * 恢复指定的下载任务。
     *
     * 仅当任务处于 PAUSED 或 FAILED 状态时才能恢复。
     * 如果网络未连接，任务将被标记为 PAUSED (因网络原因) 并且不会被恢复。
     * 成功恢复的任务将被设置为 PENDING 状态并添加到下载队列。
     *
     * @param taskId 要恢复的任务的 ID。
     */
    suspend fun resumeDownload(taskId: String) {
        checkInitialized() // 检查 DownloadManager 是否已初始化
        val task = downloadDao.getTaskById(taskId) // 从数据库获取任务信息
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
                        updateTaskStatus(
                            taskId,
                            DownloadStatus.PAUSED,
                            isNetworkPaused = true,
                            error = IOException("尝试在网络离线时恢复。原始错误: ${task.errorDetails}")
                        )
                    } else {
                        // 如果任务已经是网络暂停状态，则仅发出当前状态，因为网络仍然不可用
                        _downloadProgressFlow.tryEmit(
                            DownloadProgress(
                                task.id,
                                task.downloadedBytes,
                                task.totalBytes,
                                task.status,
                                IOException("网络不可用。原始错误: ${task.errorDetails}")
                            )
                        )
                    }
                    return // 网络未连接，无法恢复
                }

                // 网络已连接，可以将任务设置为 PENDING 以进行恢复
                Timber.d("网络已连接。正在将任务 $taskId 设置为 PENDING 以进行恢复。")
                // 在恢复/重试时，清除 isPausedByNetwork 标记和 errorDetails
                updateTaskStatus(taskId, DownloadStatus.PENDING, isNetworkPaused = false, error = null)

                // 任务状态现在在数据库中是 PENDING。将其添加到下载通道 (channel)。
                // 如果 updateTaskStatus 已经发出了状态，这里重新获取任务不是严格必要的，
                // 但是 taskQueueChannel.send 使用的是 taskId，并且最好确认状态确实已更新。
                val taskAfterPendingUpdate = downloadDao.getTaskById(taskId)
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
                        task.errorDetails?.let { IOException(it) }
                    )
                )
            }
        } else {
            Timber.w("未找到要恢复的任务 $taskId。")
        }
    }

    /**
     * 取消指定的下载任务。
     *
     * 此函数会执行以下操作：
     * 1. 如果任务当前正在下载 (存在于 `activeDownloads` 中)，则尝试取消其关联的协程 Job。
     * 2. 从数据库中获取任务信息。
     * 3. 如果任务存在，将其状态更新为 `DownloadStatus.CANCELLED`。
     * 4. 尝试删除与该任务关联的本地文件。
     * 5. 记录相应的日志信息。
     *
     * @param taskId 要取消的任务的 ID。
     */
    suspend fun cancelDownload(taskId: String) {
        checkInitialized() // 确保 DownloadManager 已初始化
        Timber.i("尝试取消任务 $taskId 的下载。")

        // 步骤 1: 取消活跃的下载 Job (如果存在)
        // 这会尝试中断 executeDownload 函数中正在进行的下载操作。
        // 提供一个 CancellationException，说明是用户取消的。
        activeDownloads[taskId]?.cancel(CancellationException("下载被用户取消"))
        // 注意：如果任务此时处于 PENDING 状态且尚未被任务处理器 (task processor) 拾取，
        // 任务处理器在稍后拾取它时，executeDownload 内部的状态检查机制会处理其 CANCELLED 状态。
        // 因此，executeDownload 能够正确处理在它开始执行前就被取消的任务。

        // 步骤 2: 从数据库获取任务信息
        val task = downloadDao.getTaskById(taskId)

        if (task != null) {
            // 任务在数据库中存在

            // 步骤 3: 首先将数据库中的任务状态更新为 CANCELLED。
            // 这样做可以确保即使后续的文件删除操作失败，任务的状态也是正确的 (CANCELLED)。
            // isNetworkPaused 设置为 false，因为取消操作与网络状态无关。
            // error 设置为 null，因为这是用户主动取消，不视为错误。
            updateTaskStatus(taskId, DownloadStatus.CANCELLED, isNetworkPaused = false, error = null)

            // 步骤 4: 然后删除与此任务关联的本地文件。
            try {
                val file = File(task.filePath) // 根据任务记录中的文件路径创建 File 对象
                if (file.exists()) { // 检查文件是否存在
                    if (file.delete()) { // 尝试删除文件
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
                    IOException("尝试取消时未找到任务 $taskId") // 附带一个错误信息
                )
            )
        }
    }

    /**
     * 重试指定的下载任务。
     *
     * 此函数本质上是对 `resumeDownload` 函数的调用。`resumeDownload` 负责处理
     * 处于 `FAILED` 或 `PAUSED` 状态的任务，将其状态设置为 `PENDING` 以便重新加入下载队列。
     * 在此过程中，它还会清除任务的错误详情 (`errorDetails`) 和网络暂停标记 (`isPausedByNetwork`)。
     *
     * @param taskId 要重试的下载任务的 ID。
     */
    suspend fun retryDownload(taskId: String) {
        checkInitialized() // 确保 DownloadManager 已初始化
        Timber.i("正在为任务 $taskId 尝试重试下载。")

        // 调用 resumeDownload 函数。
        // resumeDownload 会处理 FAILED 或 PAUSED 状态，并将任务设置为 PENDING。
        // 它同时也会清除错误详情 (error details) 和 isPausedByNetwork 标记。
        resumeDownload(taskId)
    }

    /**
     * 更新指定任务的状态，并可选地记录网络暂停状态和错误信息。
     *
     * 此函数会执行以下操作：
     * 1. （可选）根据传入的 `error` 和当前任务状态确定最终要存储的错误消息 `errorMsg`。
     * 2. 在数据库中更新任务的 `status`、`isPausedByNetwork` 和 `errorDetails`。
     * 3. 从数据库重新获取更新后的任务信息。
     * 4. 向 `_downloadProgressFlow` 发出一个新的 `DownloadProgress` 状态，以便UI和其他观察者可以响应。
     * 5. 如果新的状态是终态（如 `FAILED` 或 `CANCELLED`），则从 `activeDownloads` 맵中移除与该任务关联的 Job（如果存在）。
     * 6. 记录相应的日志信息。
     *
     * @param taskId 要更新状态的任务的 ID。
     * @param newStatus 新的 `DownloadStatus`。
     * @param isNetworkPaused 可选参数，指示任务是否因网络问题而暂停。默认为 `false`。
     * @param error 可选参数，一个 `Throwable` 对象，表示导致状态更改的错误（例如，在状态为 `FAILED` 时）。默认为 `null`。
     */
    private suspend fun updateTaskStatus(
        taskId: String,
        newStatus: DownloadStatus,
        isNetworkPaused: Boolean = false,
        error: Throwable? = null
    ) {
        checkInitialized() // 确保 DownloadManager 已初始化

        // 在更新前获取当前任务信息，主要用于确定 errorMsg 和在任务更新后找不到时回退使用旧数据
        val currentTaskBeforeUpdate = downloadDao.getTaskById(taskId)

        // 确定要存储到数据库的错误消息 (errorMsg)
        val errorMsg = error?.message // 优先使用新传入的错误信息
            ?: if (newStatus == DownloadStatus.FAILED && currentTaskBeforeUpdate?.errorDetails == null) {
                // 如果新状态是 FAILED，且没有提供新的 error 对象，并且数据库中也没有旧的 errorDetails，
                // 则设置一个默认的 "Unknown error" 消息。
                "Unknown error"
            } else {
                // 否则 (即有新 error、新状态不是 FAILED、或数据库中已有 errorDetails)，
                // 保留数据库中已有的错误信息 (currentTaskBeforeUpdate?.errorDetails)。
                // 如果 error?.message 存在，它会覆盖此处的逻辑。
                currentTaskBeforeUpdate?.errorDetails
            }

        // 步骤 1: 更新数据库中的任务状态
        downloadDao.updateStatus(taskId, newStatus, isNetworkPaused, errorMsg)

        // 步骤 2: 为了发射最准确的进度，在更新数据库状态后，重新获取任务的最新信息
        val updatedTask = downloadDao.getTaskById(taskId)

        if (updatedTask != null) {
            // 如果成功获取到更新后的任务
            _downloadProgressFlow.tryEmit(
                DownloadProgress(
                    updatedTask.id,
                    updatedTask.downloadedBytes,
                    updatedTask.totalBytes,
                    newStatus, // 使用传入的 newStatus，因为 updatedTask.status 可能由于事务原因尚未在内存中立即完全同步，或者为了确保一致性
                    error ?: updatedTask.errorDetails?.let { IOException(it) } // 优先使用传入的 error 对象；如果为 null，则尝试使用数据库中的 errorDetails (转换为 IOException)
                )
            )
            Timber.d(
                "任务 ${updatedTask.id} 状态更新为 $newStatus。 IsNetworkPaused: $isNetworkPaused。 发射进度: ${updatedTask.downloadedBytes}/${updatedTask.totalBytes}。 错误: ${errorMsg ?: "无"}" // 使用上面确定的 errorMsg 进行日志记录
            )

            // 如果任务进入了终态 (例如 FAILED, CANCELLED)，
            // 则从 activeDownloads 映射中移除其对应的协程 Job (如果存在)。
            // 这是因为这些状态意味着任务的执行生命周期已经结束。
            if (newStatus == DownloadStatus.FAILED || newStatus == DownloadStatus.CANCELLED) {
                activeDownloads.remove(updatedTask.id)?.let {
                    // Job 存在且已被移除
                    Timber.d("任务 ${updatedTask.id} (当前新状态为 $newStatus) 的 Job 已在 updateTaskStatus 中从 activeDownloads 映射中移除，因为这是一个最终状态。")
                }
            }
        } else {
            // 在数据库更新状态后未能找到该任务，这通常不应该发生，但需要处理。
            Timber.w(
                "任务 $taskId 在状态更新为 $newStatus 后未在数据库中找到，无法发射确切的进度。" +
                        " 将尝试基于提供的新状态和更新前的旧数据 (如果可用) 发射一个状态。"
            )
            // 即使任务在更新后找不到了 (例如，在极端的并发情况下被删除)，
            // 如果 newStatus 是一个重要的终态，仍然尝试为 taskId 发射一个简单的状态通知。
            // 这有助于UI层至少能够根据 taskId 和新状态进行更新，即使数据不完整。
            _downloadProgressFlow.tryEmit(
                DownloadProgress(
                    taskId, // 仍然使用原始 taskId
                    currentTaskBeforeUpdate?.downloadedBytes ?: 0L, // 尝试使用更新前获取的旧下载字节数，如果不存在则为 0
                    currentTaskBeforeUpdate?.totalBytes ?: 0L,   // 尝试使用更新前获取的旧总字节数，如果不存在则为 0
                    newStatus,                                      // 使用传入的新状态
                    error ?: currentTaskBeforeUpdate?.errorDetails?.let { IOException(it) } // 优先使用传入的 error，其次是旧的 errorDetails
                )
            )
        }
    }

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
