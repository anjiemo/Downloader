package cn.cqautotest.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.cqautotest.downloader.di.DownloadModule
import cn.cqautotest.downloader.entity.DownloadStatus
import cn.cqautotest.downloader.entity.DownloadTaskUiState
import cn.cqautotest.downloader.entity.DownloadUiState
import cn.cqautotest.downloader.ui.theme.DownloaderTheme
import cn.cqautotest.downloader.viewmodel.DownloadViewModel
import cn.cqautotest.downloader.viewmodel.DownloadViewModelFactory

class MainActivity : ComponentActivity() {

    // private val apkUrl = "https://imtt2.dd.qq.com/sjy.00009/sjy.00004/16891/apk/6C6CA789EC18DFB2BC63DAFF24C2AC0A.apk?fsname=com.liuzh.deviceinfo_v2.9.16.apk"
    private val apkUrl = "https://cdn.fwyouni.com/media/default/2507/28/1753672310_pSncWFb7x3.apk"

    // private val apkUrl = "https://15a2e1bdce38c6fbde3c4eadb199d5ed.rdt.tfogc.com:49156/downv6.qq.com/qqweb/QQ_1/android_apk/Android_9.2.5_64.apk?mkey=6889ac6d71f938fd092dce5fb7ea7d28&cip=113.249.30.8&proto=https&access_type=&tx_domain=down.qq.com&tx_path=%2Fqqweb%2F&tx_id=6c9382a8c8"
    // private val apkUrl = "https://images.sunofbeaches.com/content/2022_01_03/927680635693170688.png?imageMogr2/thumbnail/600x300"
    private val downloadViewModel: DownloadViewModel by viewModels {
        DownloadViewModelFactory(
            application = application,
            downloadUseCase = DownloadModule.provideDownloadUseCase(application)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DownloaderTheme {
                val uiState by downloadViewModel.uiState.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DownloadScreen(
                        modifier = Modifier.padding(innerPadding),
                        uiState = uiState,
                        onStartDownloads = { urls -> downloadViewModel.handleDownloadAction(urls) },
                        onCancelTask = { taskId -> downloadViewModel.cancelTask(taskId) },
                        onPauseTask = { taskId -> downloadViewModel.pauseTask(taskId) },
                        onResumeTask = { taskId -> downloadViewModel.resumeTask(taskId) },
                        onLoadSavedTasks = { downloadViewModel.loadSavedTasks() } // Add this line
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
    onStartDownloads: (List<String>) -> Unit,
    onCancelTask: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onLoadSavedTasks: () -> Unit // Add this line
) {
    val urlInput = remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = urlInput.value,
            onValueChange = { urlInput.value = it },
            label = { Text("输入多个下载链接（每行一个）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val urls = urlInput.value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (urls.isNotEmpty()) onStartDownloads(urls)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("开始批量下载")
        }

        Spacer(modifier = Modifier.height(8.dp)) // Add some space

        Button( // Add this button
            onClick = onLoadSavedTasks,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("加载已保存的任务")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(uiState.tasks) { task ->
                DownloadTaskItem(
                    task = task,
                    onPause = { onPauseTask(task.taskId) },
                    onResume = { onResumeTask(task.taskId) },
                    onCancel = { onCancelTask(task.taskId) }
                )
            }
        }
    }
}

@Composable
fun DownloadTaskItem(
    task: DownloadTaskUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(task.fileName, style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(
                progress = { task.progressPercent },
                color = ProgressIndicatorDefaults.linearColor,
                trackColor = ProgressIndicatorDefaults.linearTrackColor,
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            )
            Text("${task.downloadedBytesFormatted} / ${task.totalBytesFormatted}", style = MaterialTheme.typography.bodySmall)
            Text(task.statusText, style = MaterialTheme.typography.bodyMedium)

            task.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (task.status) {
                    DownloadStatus.DOWNLOADING -> Button(onClick = onPause) { Text("暂停") }
                    DownloadStatus.PAUSED -> Button(onClick = onResume) { Text("恢复") }
                    else -> {}
                }

                if (task.status != DownloadStatus.COMPLETED && task.status != DownloadStatus.CANCELLED) {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("取消")
                    }
                }
            }
        }
    }
}
