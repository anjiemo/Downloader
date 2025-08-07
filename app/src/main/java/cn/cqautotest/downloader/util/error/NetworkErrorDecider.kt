package cn.cqautotest.downloader.util.error

interface NetworkErrorDecider {

    fun isNetworkError(t: Throwable): Boolean
}