package cn.cqautotest.downloader.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import cn.cqautotest.downloader.downloader.DownloadManager.isInitialized
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
     * 初始化 [DownloadManager]。
     * 强烈建议在 [Application] 的 onCreate 方法中调用此方法，以确保 [DownloadManager] 在整个应用生命周期内可用。
     * 此方法只能调用一次。重复调用将被忽略。
     *
     * @param context [Application] context。用于获取系统服务和应用级资源。
     * @param config 下载相关的配置对象 [DownloadManager.Config]。
     *               如果未提供，将使用默认配置。
     * @param client 自定义的 [OkHttpClient] 实例 (可选)。如果提供，DownloadManager 将使用此实例执行所有网络请求。
     *               这允许用户配置如拦截器、自定义超时、SSL设置等。如果为 null，将创建一个具有默认配置中指定超时的内部 [OkHttpClient]。
     * @throws IllegalStateException 如果无法初始化 [DownloadDao] (通常是因为 [AppDatabase] 未正确设置或无法访问)。
     */
    fun initialize(context: Context, config: Config = Config(), client: OkHttpClient? = null) {
        // 检查是否已经初始化，防止重复初始化
        if (isInitialized) {
            Timber.w("DownloadManager 已经初始化。")
            return
        }
        // 使用 Application Context 以避免内存泄漏
        appContext = context.applicationContext

        // 初始化 Room DAO (Data Access Object)
        try {
            // 假设 AppDatabase.getDatabase(appContext) 是获取数据库实例的静态方法
            // downloadDao() 是从数据库实例获取 DAO 的方法
            downloadDao = AppDatabase.getDatabase(appContext).downloadDao()
        } catch (e: Exception) {
            Timber.e(e, "从 AppDatabase 初始化 DownloadDao 失败。")
            // 如果 DAO 初始化失败，DownloadManager 无法工作，抛出异常
            throw IllegalStateException(
                "DownloadManager: 初始化 DownloadDao 失败。请确保 AppDatabase 已正确设置且可访问。",
                e
            )
        }

        // 设置最大并发下载数
        maxConcurrentDownloads = config.maxConcurrent
        // 对最大并发数进行校验，确保其至少为1，避免无效值导致问题
        if (maxConcurrentDownloads <= 0) {
            Timber.w("maxConcurrentDownloads 配置为 ${config.maxConcurrent}，这是一个无效值。将设置为默认值 1 以避免问题。")
            maxConcurrentDownloads = 1
        }
        // 创建一个 Semaphore (信号量) 来控制并发下载的数量
        downloadSemaphore = Semaphore(maxConcurrentDownloads)

        // 初始化 OkHttpClient
        // 如果外部提供了 client 参数，则使用外部的 client；否则，根据 config 中的超时参数构建一个新的 OkHttpClient
        okHttpClient = client ?: OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS) // 连接超时
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)    // 读取超时
            .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)   // 写入超时
            .build()

        // 获取 ConnectivityManager 系统服务，用于监控网络状态
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // 标记 DownloadManager 已成功初始化
        isInitialized = true

        // 执行初始化后的操作：
        checkInitialNetworkState()     // 1. 检查当前的初始网络状态
        registerNetworkCallback()      // 2. 注册网络状态变化的回调，以便动态响应网络变化
        startTaskProcessor()           // 3. 启动后台任务处理器协程，用于处理下载队列中的任务
        resumeInterruptedTasksOnStart() // 4. 恢复在应用上次关闭时可能被中断的下载任务

        Timber.i("DownloadManager 初始化完成。最大并发下载数: $maxConcurrentDownloads。")
    }

    /**
     * 检查 [DownloadManager] 是否已经初始化。
     * 如果尚未初始化，则抛出 [IllegalStateException]。
     * 这个方法应该在所有需要 [DownloadManager] 初始化的公共或内部方法开始时调用，
     * 以确保 [DownloadManager] 处于可用状态。
     *
     * @throws IllegalStateException 如果 [isInitialized] 标志为 `false`。
     */
    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("DownloadManager 尚未初始化。请在您的 Application 的 onCreate() 方法中调用 DownloadManager.initialize()。")
        }
    }

    /**
     * 检查应用启动时的初始网络连接状态。
     *
     * 此方法会：
     * 1. 确保 `DownloadManager` 已初始化。
     * 2. 尝试获取当前活动的网络 (`connectivityManager.activeNetwork`)。
     * 3. 如果存在活动网络，则获取其网络能力 (`NetworkCapabilities`)。
     * 4. 判断该网络是否具有我们支持的传输类型之一（Wi-Fi, 蜂窝数据, 以太网）。
     *    - 如果是，则将 `isNetworkConnected` 标记设置为 `true`。
     *    - 否则 (无活动网络或活动网络不具备所需传输类型)，`isNetworkConnected` 设置为 `false`。
     * 5. 捕获 `SecurityException` (通常在缺少 `ACCESS_NETWORK_STATE` 权限时发生)，
     *    并在发生异常时将 `isNetworkConnected` 假定为 `false`。
     * 6. 记录初始网络状态的日志。
     *
     * **注意**: 此方法依赖于 `ACCESS_NETWORK_STATE` 权限。如果缺少该权限，
     *          网络状态检查可能会失败，导致 `isNetworkConnected` 被错误地设置为 `false`。
     */
    private fun checkInitialNetworkState() {
        checkInitialized() // 确保 DownloadManager 已初始化

        try {
            // 获取当前活动的网络信息
            val activeNetwork = connectivityManager.activeNetwork // activeNetwork 可能为 null
            isNetworkConnected = if (activeNetwork != null) {
                // 如果存在活动网络，则获取其网络能力
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                // 检查网络能力对象是否存在，并且是否包含我们支持的至少一种网络传输类型
                capabilities != null && (
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||      // 是否是 Wi-Fi 网络
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || // 是否是蜂窝移动网络
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)    // 是否是以太网连接
                        )
            } else {
                // 如果 activeNetwork 为 null，表示当前没有活动的网络连接
                false
            }
        } catch (se: SecurityException) {
            // 如果在尝试访问网络状态时发生 SecurityException (通常是因为缺少 ACCESS_NETWORK_STATE 权限)
            Timber.e(se, "在 checkInitialNetworkState 中发生 SecurityException。是否缺少 ACCESS_NETWORK_STATE 权限？")
            // 由于无法确定实际网络状态，保守地假设网络未连接
            isNetworkConnected = false
        }
        // 记录检测到的初始网络状态
        Timber.i("初始网络状态: ${if (isNetworkConnected) "已连接" else "已断开"}")
    }

    /**
     * 注册一个网络状态回调 (`ConnectivityManager.NetworkCallback`) 来监听网络连接的变化。
     *
     * 当网络状态发生变化时，此回调会触发相应的逻辑：
     * - `onAvailable`: 当一个网络连接变为可用时调用。
     *   - 会再次确认该网络是否真的具有我们需要的传输类型 (WIFI, CELLULAR, ETHERNET)。
     *   - 如果确认网络已连接，并且之前是断开状态 (`!wasConnected`)，则将 `isNetworkConnected` 标记更新为 `true`，
     *     并启动一个协程调用 `handleNetworkReconnection()` 来处理网络重新连接的逻辑 (例如，恢复因网络原因暂停的任务)。
     *   - 如果在 `onAvailable` 中无法确认网络连接性，则会记录警告。
     * - `onLost`: 当一个网络连接丢失时调用。
     *   - 会检查系统是否还有其他活动的网络连接 (`connectivityManager.activeNetwork`)。
     *   - 如果没有其他活动网络，并且之前是连接状态 (`isNetworkConnected` 为 true)，则将 `isNetworkConnected` 更新为 `false`，
     *     并启动一个协程调用 `handleNetworkDisconnection()` 来处理网络断开的逻辑 (例如，暂停当前活动的下载任务)。
     *   - 如果在特定网络丢失后，仍然存在其他符合条件的活动网络，则 `isNetworkConnected` 保持为 `true`。
     *   - 如果存在活动网络，但不符合我们的传输类型要求，则仍会认为网络已断开。
     *
     * 此函数需要 `ACCESS_NETWORK_STATE` 权限。如果注册失败 (例如，由于缺少权限)，
     * 自动的网络恢复和暂停功能将无法工作。
     */
    private fun registerNetworkCallback() {
        checkInitialized()

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val callbackTime = System.currentTimeMillis()
                val previousIsConnected = isNetworkConnected // 记录回调前的状态
                Timber.d("NetworkCallback.onAvailable: 网络 $network 变为可用 (回调时刻: $callbackTime)。之前的 isNetworkConnected = $previousIsConnected。")

                val currentActiveNetwork = connectivityManager.activeNetwork
                val capabilities = currentActiveNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

                val trulyConnected = capabilities != null && (
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        )
                Timber.d("NetworkCallback.onAvailable: currentActiveNetwork = ${currentActiveNetwork?.toString()}, capabilities = ${capabilities?.toString()}, trulyConnected = $trulyConnected")

                if (trulyConnected) {
                    isNetworkConnected = true // 设置全局网络状态为已连接
                    if (!previousIsConnected) { // 仅当之前是断开状态，现在变为连接状态时才处理
                        Timber.i("NetworkCallback.onAvailable: 网络从断开 -> 连接 (isNetworkConnected 从 $previousIsConnected -> true)。触发于网络 $network。准备调用 handleNetworkReconnection。")
                        downloadScope.launch {
                            handleNetworkReconnection()
                        }
                    } else {
                        Timber.d("NetworkCallback.onAvailable: isNetworkConnected 已为 true (之前为 $previousIsConnected)。可能是一个冗余回调或网络接口的确认。网络 $network。")
                    }
                } else {
                    Timber.w(
                        "NetworkCallback.onAvailable: 网络 $network 变为可用，但 getNetworkCapabilities 未返回有效传输类型，或 currentActiveNetwork 为 null。isNetworkConnected 状态将保持为 $isNetworkConnected。"
                    )
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                val callbackTime = System.currentTimeMillis()
                val previousIsConnected = isNetworkConnected // 记录回调前的状态
                Timber.d("NetworkCallback.onLost: 网络 $network 丢失 (回调时刻: $callbackTime)。之前的 isNetworkConnected = $previousIsConnected。")

                val activeNetworkCheck = connectivityManager.activeNetwork
                var stillHasActiveGoodNetwork = false // 假设没有其他可用网络

                if (activeNetworkCheck != null) {
                    Timber.d("NetworkCallback.onLost: 网络接口 $network 已丢失，但检测到另一个活动网络 $activeNetworkCheck。正在检查其能力...")
                    val capabilities = connectivityManager.getNetworkCapabilities(activeNetworkCheck)
                    if (capabilities != null && (
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                                )
                    ) {
                        stillHasActiveGoodNetwork = true
                        Timber.d("NetworkCallback.onLost: 活动网络 $activeNetworkCheck 具有有效传输类型。stillHasActiveGoodNetwork = true。")
                    } else {
                        Timber.w("NetworkCallback.onLost: 活动网络 $activeNetworkCheck 没有合适的传输类型。stillHasActiveGoodNetwork = false。")
                    }
                } else {
                    Timber.d("NetworkCallback.onLost: 网络接口 $network 已丢失，且 activeNetworkCheck 为 null (无其他活动网络)。stillHasActiveGoodNetwork = false。")
                }

                if (!stillHasActiveGoodNetwork && previousIsConnected) { // 仅当之前是连接状态，并且现在确实没有任何可用网络时
                    isNetworkConnected = false // 更新全局网络状态为已断开
                    Timber.i("NetworkCallback.onLost: 网络从连接 -> 断开 (isNetworkConnected 从 $previousIsConnected -> false)。触发于网络 $network。准备调用 handleNetworkDisconnection。")
                    downloadScope.launch {
                        handleNetworkDisconnection()
                    }
                } else if (stillHasActiveGoodNetwork && !previousIsConnected) {
                    // 特殊情况：之前是断开的，但这个 onLost 之后检查发现有其他好网络（可能 onAvailable 还没来得及更新 isNetworkConnected）
                    isNetworkConnected = true
                    Timber.i("NetworkCallback.onLost: (特殊情况) 网络 $network 丢失，但检测到其他可用网络 $activeNetworkCheck，且之前 isNetworkConnected 是 false。将其更新为 true。")
                    // 考虑是否在这里也调用 handleNetworkReconnection，如果 isNetworkConnected 确实从 false 变为 true
                    // if (!previousIsConnected) { downloadScope.launch { handleNetworkReconnection() } } // 这一句等同于 onAvailable 的逻辑
                } else if (stillHasActiveGoodNetwork && previousIsConnected) {
                    // 网络接口丢失，但仍有其他好网络，且之前也是连接状态。无需改变 isNetworkConnected。
                    isNetworkConnected = true // 确保仍然是 true
                    Timber.d("NetworkCallback.onLost: 网络 $network 丢失，但仍有其他可用网络 $activeNetworkCheck。isNetworkConnected 保持 true。")
                }
                else { // !stillHasActiveGoodNetwork && !previousIsConnected
                    Timber.d("NetworkCallback.onLost: 网络 $network 丢失，之前已是断开状态 (isNetworkConnected = $previousIsConnected)。无需操作。")
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "注册网络回调失败。是否缺少 ACCESS_NETWORK_STATE 权限？")
        }
    }

    /**
     * 处理网络断开连接的逻辑。
     *
     * 当检测到网络连接丢失时，此函数会被调用。它会执行以下操作：
     * 1. 确保 DownloadManager 已初始化。
     * 2. 记录网络断开的日志。
     * 3. 从数据库中获取所有当前状态为 `DOWNLOADING`的任务。
     * 4. 遍历这些正在下载的任务：
     *    a. 记录将要自动暂停的任务信息。
     *    b. 取消与该任务关联的下载协程 (`Job`)，并附带一个 `CancellationException`，
     *       原因为 "Network disconnected automatically"。
     *    c. 再次从数据库获取任务的最新状态，以确保在并发修改的情况下，我们只处理仍然是 `DOWNLOADING` 状态的任务。
     *    d. 如果任务确实仍处于 `DOWNLOADING` 状态，则调用 `updateTaskStatus` 将其状态更新为 `PAUSED`，
     *       同时将 `isNetworkPaused` 标记设置为 `true`，并记录一个 `IOException` 表示网络断开。
     */
    private suspend fun handleNetworkDisconnection() {
        checkInitialized() // 确保 DownloadManager 已初始化
        Timber.i("处理网络断开连接：正在暂停活动的下载...")

        // 步骤 1: 获取所有当前正在下载的任务 (状态为 DOWNLOADING)
        val runningTasks = downloadDao.getTasksByStatuses(listOf(DownloadStatus.DOWNLOADING))

        runningTasks.forEach { task -> // 遍历所有正在运行的任务
            Timber.i("网络已断开：自动暂停任务 ${task.id} (${task.fileName})")

            // 步骤 2: 取消与该任务关联的活动下载协程 (Job)
            // activeDownloads 是一个存储 taskId 到其对应 Job 的映射
            // ?.cancel() 会尝试取消 Job，如果 Job 存在的话
            activeDownloads[task.id]?.cancel(CancellationException("网络自动断开"))

            // 步骤 3: 更新数据库中任务的状态为 PAUSED，并标记为因网络原因暂停
            // 为了防止并发修改导致状态不一致 (例如任务可能在获取 runningTasks 和此处之间被其他逻辑改变状态)，
            // 我们在更新前再次从数据库获取任务的最新状态。
            val currentTaskState = downloadDao.getTaskById(task.id)
            if (currentTaskState?.status == DownloadStatus.DOWNLOADING) {
                // 只有当任务在数据库中确实仍然是 DOWNLOADING 状态时，才执行更新
                updateTaskStatus(
                    taskId = task.id,
                    newStatus = DownloadStatus.PAUSED, // 将状态设置为暂停
                    isNetworkPaused = true,            // 标记为因网络原因暂停
                    error = IOException("网络已断开")  // 记录错误原因
                )
            }
        }
    }

    /**
     * 处理网络重新连接的逻辑。
     *
     * 当检测到网络连接恢复时，此函数会被调用。它会执行以下操作：
     * 1. 确保 DownloadManager 已初始化。
     * 2. 记录网络重连的日志。
     * 3. 从数据库中获取所有因网络原因而暂停 (`isPausedByNetwork` 为 true 且 `status` 为 `PAUSED`) 的任务。
     * 4. 遍历这些任务，并为每个任务调用 `resumeDownload(taskId)` 方法，尝试恢复其下载。
     *    `resumeDownload` 内部会处理将任务状态更改为 `PENDING` 并将其添加到下载队列的逻辑。
     */
    private suspend fun handleNetworkReconnection() {
        checkInitialized() // 确保 DownloadManager 已初始化
        val callTimeMs = System.currentTimeMillis()
        Timber.i("handleNetworkReconnection: 函数开始执行 (时刻: $callTimeMs)。当前全局 isNetworkConnected = $isNetworkConnected")

        if (!isNetworkConnected) {
            Timber.w("handleNetworkReconnection: 进入函数时，全局 isNetworkConnected 标志为 false。可能网络状态变化极快。中止恢复流程。")
            return
        }

        Timber.i("handleNetworkReconnection: 网络确认已连接。正在查找因网络问题暂停的任务...")
        val networkPausedTasks = downloadDao.getAllTasks().filter { it.isPausedByNetwork && it.status == DownloadStatus.PAUSED }

        if (networkPausedTasks.isEmpty()) {
            Timber.i("handleNetworkReconnection: 未发现因网络问题暂停的任务需要恢复。")
            return
        }

        Timber.i("handleNetworkReconnection: 发现 ${networkPausedTasks.size} 个任务因网络暂停，将尝试恢复。")

        networkPausedTasks.forEach { task ->
            val taskProcessStartTimeMs = System.currentTimeMillis()
            Timber.i("handleNetworkReconnection: 准备处理任务 ${task.id} (${task.fileName})。DB状态: ${task.status}, isPausedByNetwork: ${task.isPausedByNetwork} (开始处理时刻: $taskProcessStartTimeMs)")

            // 考虑到 resumeDownload 内部已有网络检查，这里的检查可以作为第一道防线。
            // 如果网络在此时已经断开，可以避免调用 resumeDownload，减少不必要的数据库查询和日志。
            if (!isNetworkConnected) {
                Timber.w("handleNetworkReconnection: (前置检查) 在尝试恢复任务 ${task.id} 时，检测到网络连接已断开 (当前时刻: ${System.currentTimeMillis()})。将跳过对此任务调用 resumeDownload。")
                // resumeDownload 内部的逻辑会处理这种情况，如果被调用的话。
                // 但如果这里已经知道网络断了，直接跳过可以更高效。
                return@forEach // 跳到下一个任务
            }

            Timber.i("handleNetworkReconnection: 网络似乎仍然连接 (任务 ${task.id})。调用 resumeDownload(${task.id})。")
            // resumeDownload 内部会再次检查网络，并处理状态更新和任务入队。
            resumeDownload(task.id)
        }
        Timber.i("handleNetworkReconnection: 函数执行完毕。(总耗时: ${System.currentTimeMillis() - callTimeMs}ms)")
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

    /**
     * 启动一个协程来处理下载任务队列 (`taskQueueChannel`)。
     *
     * 这个协程会持续运行，从 `taskQueueChannel` 中接收任务 ID，并为每个任务执行以下操作：
     * 1. 检查协程自身是否仍然活动 (`isActive`)，如果不是，则退出循环。
     * 2. 检查网络连接状态 (`isNetworkConnected`)：
     *    - 如果网络未连接，则从数据库获取该任务。如果任务仍处于 `PENDING` 状态，
     *      将其状态更新为 `PAUSED` 并标记为网络暂停 (`isPausedByNetwork = true`)。
     *      然后，跳过当前任务的处理，等待下一个任务或网络恢复。
     * 3. 如果网络已连接，则尝试获取一个下载信号量 (`downloadSemaphore.acquire()`) 以控制并发下载数量。
     *    这是一个挂起点，如果所有信号量都被占用，协程会在此暂停，直到有信号量被释放。
     * 4. 成功获取信号量后，再次检查协程是否仍然活动。如果在等待信号量期间协程被取消，
     *    则释放信号量并退出循环。
     * 5. 从数据库获取任务的最新状态，确保任务是 `PENDING` 状态且未被网络暂停。
     *    - 如果任务符合下载条件：
     *      a. 启动一个新的子协程 (使用 `downloadScope.launch`) 来调用 `executeDownload(task)` 执行实际的下载操作。
     *      b. 将这个子协程的 `Job` 存储在 `activeDownloads` 映射中，以便可以从外部取消它。
     *      c. 在子协程的 `finally` 块中，确保释放信号量 (`downloadSemaphore.release()`) 并从 `activeDownloads` 中移除任务。
     *         这是至关重要的，无论下载成功、失败还是被取消，都必须执行。
     *      d. 为子协程的 `Job` 设置一个 `invokeOnCompletion` 回调，主要用于在取消时记录日志。
     *         (注意：`finally` 块通常是处理资源释放的主要场所)。
     *    - 如果任务不符合下载条件 (例如，状态已改变或被网络暂停)，则记录警告并释放信号量。
     * 6. 捕获在获取信号量或启动任务过程中可能发生的异常：
     *    - `InterruptedException`: 如果在等待信号量时线程被中断，记录警告，重新设置中断状态，尝试释放信号量，并退出循环。
     *    - `CancellationException`: 如果协程在处理任务或等待信号量时被取消，记录信息，尝试释放信号量，并退出循环。
     *    - 其他 `Exception`: 记录错误，尝试释放信号量。通常，单个任务的此类失败不应停止整个任务处理器循环，除非是无法恢复的严重错误。
     * 7. 当 `taskQueueChannel` 被关闭且所有元素都被处理完毕，或者循环因 `break` 退出时，协程结束。
     */
    private fun startTaskProcessor() {
        checkInitialized() // 确保 DownloadManager 已初始化

        // 在 DownloadManager 的协程作用域 (downloadScope) 中启动一个新的协程
        downloadScope.launch {
            Timber.d("任务处理器 (Task processor) 协程已启动。")

            // 通过迭代 Channel 来处理任务。当 Channel 关闭且为空时，循环会自动结束。
            for (taskId in taskQueueChannel) {
                // 检查1: 当前协程是否仍然处于活动状态
                if (!isActive) { // isActive 是 CoroutineScope 的一个属性
                    Timber.i("任务处理器协程不再活动。正在退出。")
                    break // 退出 for 循环
                }

                // 检查2: 在尝试获取信号量之前检查网络连接状态
                if (!isNetworkConnected) {
                    Timber.w("网络未连接。任务 $taskId 当前无法从队列中处理。")
                    // 从数据库获取任务信息，以检查其当前状态
                    val task = downloadDao.getTaskById(taskId)
                    // 如果任务仍然是 PENDING 状态，则将其标记为因网络原因暂停
                    if (task?.status == DownloadStatus.PENDING) {
                        updateTaskStatus(
                            taskId,
                            DownloadStatus.PAUSED,
                            isNetworkPaused = true,
                            error = IOException("待处理任务 $taskId 处理时网络不可用")
                        )
                    }
                    // 不尝试获取信号量，继续循环等待下一个任务或网络恢复
                    continue
                }

                // 主要的 try-catch 块，用于处理信号量获取和任务执行
                try {
                    Timber.d("任务处理器：正在尝试为任务 $taskId 获取信号量...")
                    downloadSemaphore.acquire() // 挂起点：获取信号量以控制并发下载数
                    Timber.d("任务处理器：已为任务 $taskId 获取信号量。")

                    // 检查3: 在获取信号量后，再次检查协程是否活动
                    // （因为在等待 acquire() 的过程中，协程可能已被取消）
                    if (!isActive) {
                        Timber.i("任务处理器在为任务 $taskId 获取信号量后变为非活动状态。正在释放信号量并退出。")
                        downloadSemaphore.release() // 释放刚刚获取的信号量
                        break // 退出 for 循环
                    }

                    // 步骤4: 从数据库获取最新的任务状态，确保任务适合下载
                    val task = downloadDao.getTaskById(taskId)
                    if (task != null && task.status == DownloadStatus.PENDING && !task.isPausedByNetwork) {
                        // 条件满足：任务存在、状态为 PENDING、且未被网络暂停

                        // 步骤5: 启动一个新的子协程来执行实际的下载操作
                        // 使用 downloadScope.launch 创建一个与父协程 (任务处理器) 生命周期相关联的子协程
                        val job = launch {
                            try {
                                executeDownload(task) // 调用核心下载函数
                            } finally {
                                // 这个 finally 块至关重要：确保在下载结束 (成功、失败或取消) 时释放资源
                                Timber.d("任务处理器：正在为任务 ${task.id} 释放信号量 (下载结束或失败)。")
                                downloadSemaphore.release() // 释放信号量，允许其他任务获取
                                activeDownloads.remove(task.id) // 从活动下载映射中移除此任务的 Job
                            }
                        }
                        activeDownloads[taskId] = job // 将新创建的下载 Job 存储起来，以便可以从外部取消它

                        // (可选) 为 Job 设置完成回调，主要用于日志记录或特定取消场景的额外处理
                        job.invokeOnCompletion { throwable ->
                            if (throwable is CancellationException) {
                                Timber.i("任务 $taskId 的下载 Job 通过 invokeOnCompletion 被取消: ${throwable.message}")
                                // 注意：通常不需要在此处再次 remove activeDownloads 或 release semaphore，
                                // 因为 Job 的取消会触发其自身的 finally 块。
                                // 这个回调更多用于日志记录，或者在 finally 块不足以处理所有取消情况时的补充。
                            }
                            // 如果需要，可以在这里处理其他类型的异常，但通常 executeDownload 内部会处理下载相关的具体错误。
                        }
                    } else {
                        // 如果任务不符合下载条件 (例如，状态已不是 PENDING，或者被网络暂停了)
                        Timber.w("任务 $taskId 不符合下载条件 (状态: ${task?.status}, isPausedByNetwork: ${task?.isPausedByNetwork})。正在释放信号量。")
                        downloadSemaphore.release() // 必须释放信号量，因为它已被获取
                    }
                } catch (e: InterruptedException) {
                    // 当 downloadSemaphore.acquire() 被中断时抛出
                    Timber.w("任务处理器：为任务 $taskId 获取信号量时被中断。正在释放信号量 (如果持有)。")
                    Thread.currentThread().interrupt() // 重新设置当前线程的中断状态
                    // 保守地释放信号量。更安全的方式是确保当前线程确实持有一个许可。
                    // 由于 Semaphore 不跟踪哪个线程持有许可，我们通过检查可用许可数来间接判断。
                    // 如果可用许可少于最大并发数，意味着当前或其他线程至少持有一个。
                    if (downloadSemaphore.availablePermits < maxConcurrentDownloads) {
                        downloadSemaphore.release()
                    }
                    break // 中断通常意味着应该停止处理，所以退出循环
                } catch (e: CancellationException) {
                    // 当任务处理器协程自身被取消时 (例如，在 acquire() 期间或在检查 isActive 之前)
                    Timber.i("任务处理器：协程在处理任务 $taskId 或等待信号量时被取消。正在释放信号量 (如果持有)。")
                    if (downloadSemaphore.availablePermits < maxConcurrentDownloads) {
                        downloadSemaphore.release()
                    }
                    break // 协程被取消，退出循环
                } catch (e: Exception) {
                    // 捕获在获取信号量或启动任务过程中可能发生的其他未知异常
                    Timber.e(e, "任务处理器：获取信号量或为任务 $taskId 启动下载时发生错误")
                    // 尝试释放信号量，以防万一是在获取后、启动下载前发生的错误
                    if (downloadSemaphore.availablePermits < maxConcurrentDownloads) {
                        downloadSemaphore.release()
                    }
                    // 对于单个任务的此类失败，通常不应该中断整个任务处理器循环，
                    // 除非是无法恢复的严重错误 (例如 OOM)。这里我们选择继续处理下一个任务。
                }
            }
            Timber.i("任务处理器 (Task processor) 协程已结束。")
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

    /**
     * 核心下载执行函数。此函数在一个单独的协程中运行，用于处理单个下载任务。
     * 它负责：
     * 1. 验证任务状态并将其设置为 DOWNLOADING。
     * 2. 构建和执行 HTTP 请求 (支持断点续传)。
     * 3. 处理 HTTP 响应 (包括错误，如 416)。
     * 4. 更新任务元数据 (ETag, Last-Modified, TotalBytes)。
     * 5. 将数据写入文件，并定期更新数据库中的下载进度。
     * 6. 定期向 UI Flow 发送进度更新。
     * 7. 在下载过程中响应外部取消或暂停请求。
     * 8. 在下载完成或失败时更新最终状态。
     * 9. 处理各种异常 (CancellationException, IOException等)。
     *
     * @param initialTaskStateFromQueue 从任务队列中获取的初始任务状态。
     *                                  函数会重新从数据库加载以确保状态最新。
     */
    private suspend fun executeDownload(initialTaskStateFromQueue: DownloadTask) {
        checkInitialized() // 确保 DownloadManager 已初始化
        var currentTask: DownloadTask // 定义明确的任务对象，将在整个函数中引用最新的任务状态

        // --- 步骤 1: 从数据库重新获取任务，以确保我们拥有最新的状态 ---
        val taskFromDbOnEntry = downloadDao.getTaskById(initialTaskStateFromQueue.id)
        if (taskFromDbOnEntry == null) {
            Timber.e("任务 ${initialTaskStateFromQueue.id} 在 executeDownload 开始时未在数据库中找到。")
            _downloadProgressFlow.tryEmit(
                DownloadProgress(
                    initialTaskStateFromQueue.id, 0L, 0L, DownloadStatus.FAILED,
                    IOException("任务记录在启动时丢失")
                )
            )
            activeDownloads.remove(initialTaskStateFromQueue.id) // 清理 activeDownloads
            return // 任务不存在，无法继续
        }
        currentTask = taskFromDbOnEntry
        Timber.i("executeDownload 开始处理任务 ${currentTask.id} (${currentTask.fileName})。URL: ${currentTask.url}。初始数据库状态: ${currentTask.status}，已下载: ${currentTask.downloadedBytes}")

        // --- 步骤 2: 验证任务是否处于可开始的状态 ---
        if (currentTask.status != DownloadStatus.PENDING || currentTask.isPausedByNetwork) {
            Timber.w("任务 ${currentTask.id} 无法启动 (数据库状态: ${currentTask.status}, isPausedByNetwork: ${currentTask.isPausedByNetwork})。正在中止。")
            if (currentTask.status == DownloadStatus.FAILED || currentTask.status == DownloadStatus.PAUSED || currentTask.status == DownloadStatus.CANCELLED) {
                _downloadProgressFlow.tryEmit(
                    DownloadProgress(
                        currentTask.id, currentTask.downloadedBytes, currentTask.totalBytes,
                        currentTask.status, currentTask.errorDetails?.let { IOException(it) }
                    )
                )
            } else if (currentTask.status != DownloadStatus.COMPLETED) {
                updateTaskStatus(currentTask.id, DownloadStatus.FAILED, currentTask.isPausedByNetwork, IOException("任务启动时状态无效: ${currentTask.status}"))
            }
            activeDownloads.remove(currentTask.id) // 清理 activeDownloads
            return
        }

        // --- 步骤 3: 将任务状态更新为 DOWNLOADING ---
        // 注意：这里传入的 isNetworkPaused 应该是 false，因为我们要开始下载了
        updateTaskStatus(currentTask.id, DownloadStatus.DOWNLOADING, isNetworkPaused = false, error = null)

        // --- 步骤 4: 在设置为 DOWNLOADING 后重新获取任务状态，并记录进入下载时的初始字节数 ---
        var taskAfterUpdateToDownloading = downloadDao.getTaskById(currentTask.id)
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
        var bytesActuallyWrittenThisSession: Long = 0 // 本次下载会话实际写入文件的字节数
        Timber.d("任务 ${currentTask.id} 已确认状态为 DOWNLOADING。文件: ${currentTask.fileName}。会话开始时已下载: $initialDownloadedBytesForSession")
        val requestBuilder = Request.Builder().url(currentTask.url)
        var expectedContentLengthFromServer: Long = -1 // 用于跟踪本次 HTTP 响应头中的 Content-Length

        if (initialDownloadedBytesForSession > 0) {
            requestBuilder.addHeader("Range", "bytes=${initialDownloadedBytesForSession}-")
            currentTask.eTag?.let { requestBuilder.addHeader("If-Range", it) }
                ?: currentTask.lastModified?.let { requestBuilder.addHeader("If-Range", it) }
            Timber.d("任务 ${currentTask.id}: 从 $initialDownloadedBytesForSession 字节处恢复下载。")
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

            // 在处理响应前，再次检查任务状态和协程状态
            val taskStateBeforeWrite = downloadDao.getTaskById(currentTask.id)
            if (!currentCoroutineContext().isActive || taskStateBeforeWrite == null || taskStateBeforeWrite.status != DownloadStatus.DOWNLOADING) {
                Timber.w("任务 ${currentTask.id} 在网络响应后被取消或状态已更改 (DB状态: ${taskStateBeforeWrite?.status} / Coroutine: ${currentCoroutineContext().isActive})。中止写入。")
                response.close()
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
                response.close()

                if (serverTotalSize != null && currentTask.downloadedBytes >= serverTotalSize) {
                    Timber.i("任务 ${currentTask.id}: HTTP 416 确认下载已完成。服务器总大小: $serverTotalSize。标记为 COMPLETED。")
                    if (currentTask.totalBytes != serverTotalSize || currentTask.downloadedBytes != serverTotalSize) {
                        downloadDao.updateProgress(currentTask.id, serverTotalSize, serverTotalSize)
                        currentTask = currentTask.copy(downloadedBytes = serverTotalSize, totalBytes = serverTotalSize)
                    }
                    updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED)
                } else {
                    Timber.w("任务 ${currentTask.id}: HTTP 416，但已下载 (${currentTask.downloadedBytes}) 与服务器总大小 ($serverTotalSize) 不符或总大小未知。文件可能已更改。正在重置任务。")
                    val error = IOException("Range not satisfiable (416). File may have changed. Server total: $serverTotalSize. Current downloaded: ${currentTask.downloadedBytes}. Resetting.")
                    downloadDao.updateProgress(currentTask.id, 0L, 0L)
                    downloadDao.updateETagAndLastModified(currentTask.id, null, null)
                    updateTaskStatus(currentTask.id, DownloadStatus.FAILED, isNetworkPaused = false, error = error)
                }
                return
            }

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "N/A"
                response.close()
                Timber.e("任务 ${currentTask.id}: HTTP 响应不成功 ${response.code} ${response.message}. Body: $errorBody")
                throw IOException("Server error ${response.code} ${response.message}. Task ${currentTask.id}. Body: $errorBody")
            }
            // --- 处理成功的 HTTP 响应 ---
            val responseBody = response.body ?: throw IOException("任务 ${currentTask.id} 的响应体为 null")
            expectedContentLengthFromServer = responseBody.contentLength()

            var serverReportedTotalBytesInHeader = currentTask.totalBytes
            val newETag = response.header("ETag")
            val newLastModified = response.header("Last-Modified")

            if (response.code == 200) { // HTTP 200 OK (完整内容)
                Timber.i("任务 ${currentTask.id}: 收到 HTTP 200 OK。initialDownloadedBytesForSession Was: $initialDownloadedBytesForSession. 将从头开始下载。") // 添加 initialDownloadedBytesForSession 日志
                if (initialDownloadedBytesForSession > 0) {
                    Timber.w("任务 ${currentTask.id}: 收到 HTTP 200，但之前有进度 ($initialDownloadedBytesForSession bytes)。重置数据库进度为 0。")
                    downloadDao.updateDownloadedBytes(currentTask.id, 0L)
                    currentTask = currentTask.copy(downloadedBytes = 0L)
                }
                serverReportedTotalBytesInHeader = expectedContentLengthFromServer
            } else if (response.code == 206) { // HTTP 206 Partial Content
                Timber.d("任务 ${currentTask.id}: 成功恢复下载 (HTTP 206)。从 ${currentTask.downloadedBytes} 开始。")
                val contentRange = response.header("Content-Range")
                val serverTotalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
                if (serverTotalFromRange != null && serverTotalFromRange > 0) {
                    serverReportedTotalBytesInHeader = serverTotalFromRange
                }
            } else {
                Timber.w("任务 ${currentTask.id}: 收到未知成功码 ${response.code}。按 HTTP 200 处理。")
                if (initialDownloadedBytesForSession > 0) {
                    downloadDao.updateDownloadedBytes(currentTask.id, 0L)
                    currentTask = currentTask.copy(downloadedBytes = 0L)
                }
                serverReportedTotalBytesInHeader = expectedContentLengthFromServer
            }

            var taskMetaChanged = false
            if (newETag != currentTask.eTag || newLastModified != currentTask.lastModified) {
                downloadDao.updateETagAndLastModified(currentTask.id, newETag, newLastModified)
                taskMetaChanged = true
            }
            if (serverReportedTotalBytesInHeader > 0 && serverReportedTotalBytesInHeader != currentTask.totalBytes) {
                downloadDao.updateTotalBytes(currentTask.id, serverReportedTotalBytesInHeader)
                taskMetaChanged = true
            }

            if (taskMetaChanged) {
                val refreshedTask = downloadDao.getTaskById(currentTask.id)
                if (refreshedTask != null) {
                    currentTask = refreshedTask
                    Timber.d("任务 ${currentTask.id} 元数据已更新。ETag: ${currentTask.eTag}, LastModified: ${currentTask.lastModified}, TotalBytes: ${currentTask.totalBytes}, CurrentDownloaded: ${currentTask.downloadedBytes}")
                } else {
                    response.close()
                    throw IOException("任务 ${currentTask.id} 在元数据更新后消失")
                }
            }

            // --- 准备文件写入 ---
            val file = File(currentTask.filePath)
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    response.close()
                    throw IOException("为任务 ${currentTask.id} 创建目录失败: ${parentDir.absolutePath}")
                }
            }

            randomAccessFile = RandomAccessFile(file, "rw")
            // currentTask.downloadedBytes 此时是准确的写入起始点 (0 或之前的进度)
            randomAccessFile.seek(currentTask.downloadedBytes)
            Timber.d("任务 ${currentTask.id}: RandomAccessFile seek to ${currentTask.downloadedBytes}")

            // --- 读取响应体并写入文件 ---
            val buffer = ByteArray(512 * 1024) // 512KB buffer
            var bytesReadFromStream: Int
            var lastUiEmitTime = System.currentTimeMillis()
            var bytesSinceLastDbUpdate: Long = 0
            val dbUpdateThresholdBytes: Long = 512 * 1024 * 1024 // 每 512KB 更新一次数据库

            responseBody.byteStream().use { inputStream ->
                while (true) {
                    if (!currentCoroutineContext().isActive) {
                        Timber.i("任务 ${currentTask.id} 在读取循环中检测到协程非 。")
                        break // 由 finally 处理进度保存和状态
                    }

                    // 检查数据库状态以响应外部暂停/取消
                    if (System.currentTimeMillis() - lastUiEmitTime > 1000) { // 可调整检查频率
                        val taskStateInLoop = downloadDao.getTaskById(currentTask.id)
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

                    // currentTask.downloadedBytes 是会话开始时的值
                    // 最新总下载进度 = 会话开始时的进度 + 本会话已写入的字节
                    val currentTotalDownloadedInMemory = currentTask.downloadedBytes + bytesActuallyWrittenThisSession

                    if (bytesSinceLastDbUpdate >= dbUpdateThresholdBytes) {
                        downloadDao.updateDownloadedBytes(currentTask.id, currentTotalDownloadedInMemory)
                        bytesSinceLastDbUpdate = 0
                        Timber.v("任务 ${currentTask.id} DB进度更新: $currentTotalDownloadedInMemory/${currentTask.totalBytes}")
                    }

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUiEmitTime >= 1000) { // 每秒发射一次UI进度
                        _downloadProgressFlow.tryEmit(
                            DownloadProgress(
                                currentTask.id, currentTotalDownloadedInMemory,
                                currentTask.totalBytes,
                                DownloadStatus.DOWNLOADING
                            )
                        )
                        lastUiEmitTime = currentTime
                    }
                }
            } // inputStream.use 会自动关闭
            // --- 下载循环结束后 (EOF 或 break) ---
            val finalTotalDownloadedBytes = currentTask.downloadedBytes + bytesActuallyWrittenThisSession
            downloadDao.updateDownloadedBytes(currentTask.id, finalTotalDownloadedBytes) // 关键：保存最终精确进度
            Timber.d("任务 ${currentTask.id}: 循环结束后，最终DB进度更新为: $finalTotalDownloadedBytes")

            val finalTaskStateFromDb = downloadDao.getTaskById(currentTask.id)
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
                    updateTaskStatus(currentTask.id, DownloadStatus.PAUSED, isNetworkPaused = currentTask.isPausedByNetwork, error = CancellationException("下载在完成检查前被取消"))
                }
                return
            }

            if (currentTask.status == DownloadStatus.DOWNLOADING) {
                val knownTotalBytes = currentTask.totalBytes
                if (knownTotalBytes > 0) {
                    if (currentTask.downloadedBytes < knownTotalBytes) {
                        val errMsg = "下载不完整: ${currentTask.downloadedBytes}/$knownTotalBytes. 期望内容长度: $expectedContentLengthFromServer, 本次会话实际写入: $bytesActuallyWrittenThisSession."
                        Timber.e("任务 ${currentTask.id}: $errMsg")
                        updateTaskStatus(currentTask.id, DownloadStatus.FAILED, isNetworkPaused = false, error = IOException(errMsg))
                    } else if (currentTask.downloadedBytes > knownTotalBytes) {
                        Timber.w("任务 ${currentTask.id}: 下载字节 (${currentTask.downloadedBytes}) > 总字节 ($knownTotalBytes). 修正并完成.")
                        downloadDao.updateProgress(currentTask.id, knownTotalBytes, knownTotalBytes)
                        updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED)
                    } else { // downloadedBytes == knownTotalBytes
                        Timber.i("任务 ${currentTask.id} 成功完成。已下载: ${currentTask.downloadedBytes}/$knownTotalBytes")
                        updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED)
                    }
                } else {
                    Timber.i("任务 ${currentTask.id} 完成 (总大小未知). 已下载: ${currentTask.downloadedBytes}. 标记为 COMPLETED.")
                    downloadDao.updateTotalBytes(currentTask.id, currentTask.downloadedBytes)
                    updateTaskStatus(currentTask.id, DownloadStatus.COMPLETED)
                }
            } else {
                Timber.i("任务 ${currentTask.id}: 下载循环结束。最终DB状态为 ${currentTask.status}。进度: ${currentTask.downloadedBytes}/${currentTask.totalBytes}")
                _downloadProgressFlow.tryEmit(
                    DownloadProgress(
                        currentTask.id, currentTask.downloadedBytes, currentTask.totalBytes,
                        currentTask.status, currentTask.errorDetails?.let { IOException(it) }
                    )
                )
            }

        } catch (e: CancellationException) {
            Timber.i(e, "任务 ${currentTask.id} 的下载被取消: ${e.message}")
            // 关键: 在取消时，保存当前会话实际已下载的进度
            val currentDownloadedBeforeError = currentTask.downloadedBytes + bytesActuallyWrittenThisSession
            downloadDao.updateDownloadedBytes(currentTask.id, currentDownloadedBeforeError)

            // isNetworkIssue false, 因为是外部/用户取消，除非特定情况
            handleCancellationOrError(currentTask.id, DownloadStatus.PAUSED, e, false)
        } catch (e: IOException) {
            Timber.e(e, "任务 ${currentTask.id} 下载期间发生 IOException。原始消息: '${e.message}'") // 打印原始异常消息
            val currentDownloadedBeforeError = currentTask.downloadedBytes + bytesActuallyWrittenThisSession
            downloadDao.updateDownloadedBytes(currentTask.id, currentDownloadedBeforeError)

            // --- 开始详细诊断 isLikelySuddenNetworkLoss ---
            val exceptionIsSocketException = e is java.net.SocketException
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

            val exceptionIsUnknownHost = e is java.net.UnknownHostException
            val exceptionIsConnectException = e is java.net.ConnectException
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
            Timber.d("任务 ${currentTask.id}: IOException 处理决策: newStatus=$newStatus, isConsideredNetworkIssueForThisError=$isConsideredNetworkIssueForThisError (基于 isLikelySuddenNetworkLoss=$isLikelySuddenNetworkLoss 和当时的全局 isNetworkConnected=$isNetworkConnected)")
            handleCancellationOrError(currentTask.id, newStatus, e, isConsideredNetworkIssueForThisError)
        } catch (e: Exception) {
            Timber.e(e, "任务 ${currentTask.id} 下载期间发生意外错误: ${e.message}")
            val currentDownloadedBeforeError = currentTask.downloadedBytes + bytesActuallyWrittenThisSession
            downloadDao.updateDownloadedBytes(currentTask.id, currentDownloadedBeforeError)

            handleCancellationOrError(currentTask.id, DownloadStatus.FAILED, e, !isNetworkConnected)
        } finally {
            try {
                randomAccessFile?.close()
            } catch (e: IOException) {
                Timber.e(e, "为任务 ${currentTask.id} 关闭 randomAccessFile 时出错")
            }
            try {
                response?.close()
            } catch (e: Exception) {
                Timber.e(e, "为任务 ${currentTask.id} 关闭 response 时出错")
            }
            Timber.d("任务 ${currentTask.id} 的 executeDownload 执行路径结束。")
            // activeDownloads 的清理主要由 updateTaskStatus (对于终态 FAILED, COMPLETED, CANCELLED)
            // 或 pauseDownload/cancelDownload (通过取消Job间接触发 handleCancellationOrError) 处理。
            // 确保清理
            val finalTaskCheck = downloadDao.getTaskById(currentTask.id) // 重新获取以检查最终状态
            if (finalTaskCheck == null ||
                (finalTaskCheck.status != DownloadStatus.DOWNLOADING && finalTaskCheck.status != DownloadStatus.PENDING)
            ) {
                activeDownloads.remove(currentTask.id)
            }
        }
    } // end of executeDownload

    /**
     * 处理下载任务遇到取消 (Cancellation) 或错误 (Error) 后的最终状态。
     *
     * 此函数会：
     * 1. 检查 DownloadManager 是否已初始化。
     * 2. 从数据库获取当前任务的状态 (`currentTaskState`)。
     * 3. 如果任务存在：
     *    a. 检查当前任务状态 (`currentTaskState.status`) 是否适合被更新为 `statusToSet`。
     *       更新条件是：
     *       - 任务当前处于 `DOWNLOADING` 或 `PENDING` 状态。
     *       - 或者，要设置的状态是 `FAILED`，并且任务当前处于 `PAUSED` 状态 (允许重试失败的任务覆盖 PAUSED)。
     *    b. 如果满足更新条件，则调用 `updateTaskStatus` 来更新数据库中的任务状态、网络暂停标记和错误信息。
     *       - 如果 `statusToSet` 是 `PAUSED` 并且是由于网络问题 (`isNetworkIssue` 为 true)，则 `isPausedByNetwork` 会被设置为 true。
     *       - 否则，保留 `currentTaskState` 中已有的 `isPausedByNetwork` 值。
     *    c. 如果不满足更新条件 (例如，任务已经被用户手动取消或完成)，则不会覆盖现有状态，但会发出一个包含当前数据库状态的 `DownloadProgress`，以确保 UI 的一致性。
     * 4. 如果任务在数据库中未找到：
     *    a. 记录错误。
     *    b. 发出一个 `DownloadProgress`，其中包含传入的 `taskId`、未知的进度 (0, 0)、要设置的 `statusToSet` 以及错误信息。这有助于 UI 至少知道这个 `taskId` 尝试进入了某个错误/取消状态。
     *
     * @param taskId 发生错误或取消的任务的 ID。
     * @param statusToSet 在错误或取消发生后，尝试将任务设置到的目标 `DownloadStatus` (通常是 `FAILED` 或 `PAUSED`)。
     * @param error 关联的 `Throwable` 错误对象，可能为 null (例如，对于某些类型的取消)。
     * @param isNetworkIssue 布尔值，指示错误或取消是否与网络连接问题有关。主要用于当 `statusToSet` 为 `PAUSED` 时，决定是否设置 `isPausedByNetwork` 标记。
     */
    private suspend fun handleCancellationOrError(
        taskId: String,
        statusToSet: DownloadStatus,
        error: Throwable?,
        isNetworkIssue: Boolean // 这个参数很重要
    ) {
        checkInitialized()
        val currentTaskState = downloadDao.getTaskById(taskId)

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
                updateTaskStatus(
                    taskId,
                    statusToSet,
                    isNetworkPaused = finalIsNetworkPaused,
                    error = error
                )
            } else {
                Timber.i(
                    "任务 $taskId 遇到错误/取消. DB状态: ${currentTaskState.status}. 不会用 $statusToSet 覆盖. Error: ${error?.message}"
                )
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
