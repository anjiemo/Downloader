package cn.cqautotest

import android.app.Application
import cn.cqautotest.downloader.di.DownloadModule
import cn.cqautotest.downloader.util.log.DebugLoggerTree
import com.blankj.utilcode.util.LogUtils
import timber.log.Timber

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (LogUtils.getConfig().isLogSwitch) {
            Timber.plant(DebugLoggerTree())
        }
        DownloadModule.initialize(this)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        DownloadModule.cleanup()
    }
}