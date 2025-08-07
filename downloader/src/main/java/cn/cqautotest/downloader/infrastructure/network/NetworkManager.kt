package cn.cqautotest.downloader.infrastructure.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

class NetworkManager(context: Context) {

    private var appContext: Context = context.applicationContext
    private val connectivityManager by lazy { appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val _isNetworkConnected = MutableStateFlow(true)
    val isNetworkConnected: StateFlow<Boolean> = _isNetworkConnected.asStateFlow()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun initialize() {
        checkInitialNetworkState()
        registerNetworkCallback()
    }

    /**
     * 检查当前的初始网络状态
     */
    private fun checkInitialNetworkState() {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val isConnected = if (activeNetwork != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                capabilities != null && (
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        )
            } else {
                false
            }
            _isNetworkConnected.value = isConnected
            Timber.i("初始网络状态: ${if (isConnected) "已连接" else "已断开"}")
        } catch (se: SecurityException) {
            Timber.e(se, "在 checkInitialNetworkState 中发生 SecurityException。是否缺少 ACCESS_NETWORK_STATE 权限？")
            _isNetworkConnected.value = false
        }
    }

    /**
     * 注册网络状态变化的回调，以便动态响应网络变化
     */
    private fun registerNetworkCallback() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val callbackTime = System.currentTimeMillis()
                val previousIsConnected = _isNetworkConnected.value
                Timber.d("NetworkCallback.onAvailable: 网络 $network 变为可用 (回调时刻: $callbackTime)。之前的 isNetworkConnected = $previousIsConnected。")

                val currentActiveNetwork = connectivityManager.activeNetwork
                val capabilities = currentActiveNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

                val trulyConnected = capabilities != null && (
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        )

                if (trulyConnected) {
                    _isNetworkConnected.value = true
                    if (!previousIsConnected) {
                        Timber.i("NetworkCallback.onAvailable: 网络从断开 -> 连接 (isNetworkConnected 从 $previousIsConnected -> true)。触发于网络 $network。")
                    }
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                val callbackTime = System.currentTimeMillis()
                val previousIsConnected = _isNetworkConnected.value
                Timber.d("NetworkCallback.onLost: 网络 $network 丢失 (回调时刻: $callbackTime)。之前的 isNetworkConnected = $previousIsConnected。")

                val activeNetworkCheck = connectivityManager.activeNetwork
                var stillHasActiveGoodNetwork = false

                if (activeNetworkCheck != null) {
                    val capabilities = connectivityManager.getNetworkCapabilities(activeNetworkCheck)
                    if (capabilities != null && (
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                                )
                    ) {
                        stillHasActiveGoodNetwork = true
                    }
                }

                if (!stillHasActiveGoodNetwork && previousIsConnected) {
                    _isNetworkConnected.value = false
                    Timber.i("NetworkCallback.onLost: 网络从连接 -> 断开 (isNetworkConnected 从 $previousIsConnected -> false)。触发于网络 $network。")
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: SecurityException) {
            Timber.e(e, "注册网络回调失败。是否缺少 ACCESS_NETWORK_STATE 权限？")
        }
    }

    fun cleanup() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Timber.e(e, "注销网络回调失败")
            }
        }
        networkCallback = null
    }
}