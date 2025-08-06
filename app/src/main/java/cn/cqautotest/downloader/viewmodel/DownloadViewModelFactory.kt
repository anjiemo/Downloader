package cn.cqautotest.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cn.cqautotest.downloader.domain.usecase.DownloadUseCase

class DownloadViewModelFactory(private val application: Application, private val downloadUseCase: DownloadUseCase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DownloadViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DownloadViewModel(application, downloadUseCase) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}