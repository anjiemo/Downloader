package cn.cqautotest

import android.app.Application
import cn.cqautotest.downloader.downloader.DownloadManager
import cn.cqautotest.downloader.util.DebugLoggerTree
import com.blankj.utilcode.util.LogUtils
import timber.log.Timber

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (LogUtils.getConfig().isLogSwitch) {
            Timber.plant(DebugLoggerTree())
        }
        DownloadManager.initialize(this)
    }
}