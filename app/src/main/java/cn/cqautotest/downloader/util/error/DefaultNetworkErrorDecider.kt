package cn.cqautotest.downloader.util.error

import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

object DefaultNetworkErrorDecider : NetworkErrorDecider {

    /**
     * 判断一个 Throwable 是否由网络问题引起。
     *
     * 1）只要属于以下类型之一即视为网络错误：
     *    - UnknownHostException       → DNS 解析失败、无网络
     *    - ConnectException           → TCP 连接失败
     *    - SocketTimeoutException     → 连接或读写超时
     *    - SSLHandshakeException      → TLS 握手失败（常见于网络被劫持或证书问题）
     *    - PortUnreachableException   → ICMP 端口不可达
     *    - NoRouteToHostException     → 路由不可达
     *
     * 2）如果是 IOException，再兜底检查 message / cause
     *    是否包含常见关键字（network unreachable、timeout、reset、broken pipe…）。
     *
     * 3）Android 特有的 android.system.ErrnoException 也可一并处理。
     */
    override fun isNetworkError(t: Throwable): Boolean {
        // 1. 常见网络异常类型
        when (t) {
            is UnknownHostException,
            is ConnectException,
            is SocketTimeoutException,
            is SSLHandshakeException,
            is PortUnreachableException,
            is NoRouteToHostException -> return true
        }

        // 2. 兜底关键字匹配
        if (t is IOException) {
            val msg = t.message?.lowercase() ?: ""
            val causeMsg = t.cause?.message?.lowercase() ?: ""
            val keywords = arrayOf(
                "network is unreachable",
                "timeout",
                "connection reset",
                "broken pipe",
                "connection refused",
                "failed to connect",
                "no route to host"
            )
            keywords.forEach {
                if (msg.contains(it) || causeMsg.contains(it)) return true
            }
        }

        // 3. Android 特有的 ErrnoException
        if (Build.VERSION.SDK_INT >= 21 && t is ErrnoException) {
            return t.errno == OsConstants.ENETUNREACH ||
                    t.errno == OsConstants.ETIMEDOUT ||
                    t.errno == OsConstants.EHOSTUNREACH
        }

        return false
    }
}