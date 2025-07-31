package cn.cqautotest.downloader

import android.os.Bundle
// import android.util.Log // No longer needed for Timber if only used here
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.cqautotest.downloader.downloader.DownloadStatus
import cn.cqautotest.downloader.downloader.DownloadUiState
import cn.cqautotest.downloader.downloader.DownloadViewModel
import cn.cqautotest.downloader.downloader.DownloadViewModelFactory
import cn.cqautotest.downloader.ui.theme.DownloaderTheme
import timber.log.Timber // Import Timber

class MainActivity : ComponentActivity() {

    private val apkUrl = "https://imtt2.dd.qq.com/sjy.00009/sjy.00004/16891/apk/6C6CA789EC18DFB2BC63DAFF24C2AC0A.apk?fsname=com.liuzh.deviceinfo_v2.9.16.apk"
    // private val apkUrl = "https://cdn.fwyouni.com/media/default/2507/28/1753672310_pSncWFb7x3.apk"
    // private val apkUrl = "https://15a2e1bdce38c6fbde3c4eadb199d5ed.rdt.tfogc.com:49156/downv6.qq.com/qqweb/QQ_1/android_apk/Android_9.2.5_64.apk?mkey=6889ac6d71f938fd092dce5fb7ea7d28&cip=113.249.30.8&proto=https&access_type=&tx_domain=down.qq.com&tx_path=%2Fqqweb%2F&tx_id=6c9382a8c8"
    // private val apkUrl = "https://images.sunofbeaches.com/content/2022_01_03/927680635693170688.png?imageMogr2/thumbnail/600x300"
    private val downloadViewModel: DownloadViewModel by viewModels {
        DownloadViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DownloaderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val uiState by downloadViewModel.uiState.collectAsState()

                    DownloadScreen(
                        modifier = Modifier.padding(innerPadding),
                        uiState = uiState,
                        onDownloadAction = { downloadViewModel.handleDownloadAction(apkUrl) },
                        onCancelAction = { downloadViewModel.cancelCurrentDownload() },
                        onDownloadLog = { message -> Timber.d("MainActivityDownloadLog: $message") } // Changed to Timber.d
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadScreen(
    modifier: Modifier = Modifier,
    uiState: DownloadUiState,
    onDownloadAction: () -> Unit,
    onCancelAction: () -> Unit,
    onDownloadLog: (String) -> Unit // 可选
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = {
                onDownloadLog("Download action button clicked. Status: ${uiState.status}") // Example of using onDownloadLog
                onDownloadAction()
            },
        ) {
            Text(
                text = when {
                    uiState.status == DownloadStatus.DOWNLOADING -> "暂停"
                    uiState.status == DownloadStatus.PAUSED -> "恢复"
                    (uiState.status == DownloadStatus.COMPLETED ||
                            uiState.status == DownloadStatus.FAILED ||
                            uiState.status == DownloadStatus.CANCELLED) && uiState.currentTaskId == null -> "重新下载"
                    uiState.currentTaskId != null && (uiState.status == DownloadStatus.COMPLETED ||
                            uiState.status == DownloadStatus.FAILED ||
                            uiState.status == DownloadStatus.CANCELLED) -> "重新下载"
                    else -> "下载 APK"
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.isActionInProgress ||
            (uiState.currentTaskId != null &&
                    (uiState.status == DownloadStatus.COMPLETED || uiState.status == DownloadStatus.FAILED))
        ) {
            LinearProgressIndicator(
                progress = { uiState.progressPercent },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "已下载: ${uiState.downloadedBytesFormatted} / ${uiState.totalBytesFormatted}"
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = uiState.statusText)

        if (uiState.errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "错误: ${uiState.errorMessage}",
                color = MaterialTheme.colorScheme.error
            )
        }

        if (uiState.isActionInProgress && uiState.status != DownloadStatus.COMPLETED) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    onDownloadLog("Cancel action button clicked.") // Example of using onDownloadLog
                    onCancelAction()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("取消下载")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DownloadScreenPreview() {
    DownloaderTheme {
        val previewState = DownloadUiState(
            progressPercent = 0.6f,
            downloadedBytesFormatted = "600 KB",
            totalBytesFormatted = "1.00 MB",
            status = DownloadStatus.DOWNLOADING,
            statusText = "下载中: 60.0%",
            isActionInProgress = true
        )
        DownloadScreen(
            uiState = previewState,
            onDownloadAction = {},
            onCancelAction = {},
            onDownloadLog = { message -> Timber.d("PreviewLog: $message") } // Example for preview
        )
    }
}

@Preview(showBackground = true, name = "Download Completed Preview")
@Composable
fun DownloadScreenCompletedPreview() {
    DownloaderTheme {
        val previewState = DownloadUiState(
            progressPercent = 1.0f,
            downloadedBytesFormatted = "1.00 MB",
            totalBytesFormatted = "1.00 MB",
            status = DownloadStatus.COMPLETED,
            statusText = "下载完成!",
            isActionInProgress = false
        )
        DownloadScreen(
            uiState = previewState,
            onDownloadAction = {},
            onCancelAction = {},
            onDownloadLog = { message -> Timber.d("PreviewLog: $message") }
        )
    }
}

@Preview(showBackground = true, name = "Download Error Preview")
@Composable
fun DownloadScreenErrorPreview() {
    DownloaderTheme {
        val previewState = DownloadUiState(
            status = DownloadStatus.FAILED,
            statusText = "下载失败: 网络连接超时",
            isActionInProgress = false,
            errorMessage = "网络连接超时"
        )
        DownloadScreen(
            uiState = previewState,
            onDownloadAction = {},
            onCancelAction = {},
            onDownloadLog = { message -> Timber.d("PreviewLog: $message") }
        )
    }
}
