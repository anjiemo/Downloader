package cn.cqautotest.downloader.util.error

object DownloadErrorHandler {

    fun isNetworkError(t: Throwable): Boolean {
        return DefaultNetworkErrorDecider.isNetworkError(t)
    }
}