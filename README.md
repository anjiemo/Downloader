# Android 下载器 (Downloader)

一个功能强大的Android文件下载器，支持单线程和分片下载，具有断点续传、网络状态监控、文件完整性校验等特性。

## 📋 功能特性

### 🚀 核心功能

- **多模式下载**: 支持单线程下载和分片并发下载
- **断点续传**: 支持HTTP Range请求，实现断点续传功能
- **网络监控**: 实时监控网络状态，网络断开时自动暂停，恢复时自动继续
- **并发控制**: 可配置的最大并发下载数和分片数
- **进度跟踪**: 实时下载进度更新和状态管理
- **文件校验**: 支持MD5文件完整性校验
- **任务管理**: 完整的下载任务生命周期管理

### 🛡️ 可靠性特性

- **双指针机制**: 主指针和副指针确保下载进度的一致性
- **文件完整性检查**: 下载完成后验证文件完整性
- **错误恢复**: 智能错误处理和重试机制
- **状态持久化**: 使用Room数据库持久化下载状态
- **应用重启恢复**: 应用重启后自动恢复未完成的下载

### 🎨 用户界面

- **现代化UI**: 基于Jetpack Compose构建的Material Design 3界面
- **实时进度**: 实时显示下载进度、速度和状态
- **任务控制**: 支持暂停、恢复、取消下载任务
- **状态显示**: 清晰的任务状态和错误信息展示

## 🏗️ 项目架构

### 架构模式

项目采用**Clean Architecture** + **MVVM**架构模式，确保代码的可维护性和可测试性。

```
┌─────────────────────────────────────────────────────────────┐
│                        Presentation Layer                    │
├─────────────────────────────────────────────────────────────┤
│  MainActivity (Jetpack Compose UI)                          │
│  DownloadViewModel (MVVM ViewModel)                         │
├─────────────────────────────────────────────────────────────┤
│                        Domain Layer                          │
├─────────────────────────────────────────────────────────────┤
│  DownloadManager (核心下载逻辑)                              │
│  DownloadUseCase (用例层)                                   │
│  Entities (数据实体)                                        │
├─────────────────────────────────────────────────────────────┤
│                      Infrastructure Layer                    │
├─────────────────────────────────────────────────────────────┤
│  Repository (数据访问层)                                    │
│  NetworkManager (网络管理)                                  │
│  FileManager (文件管理)                                     │
├─────────────────────────────────────────────────────────────┤
│                        Data Layer                            │
├─────────────────────────────────────────────────────────────┤
│  Room Database (本地存储)                                   │
│  DAO (数据访问对象)                                         │
└─────────────────────────────────────────────────────────────┘
```

### 核心组件

#### 1. DownloadManager

- **位置**: `domain/DownloadManager.kt`
- **职责**: 核心下载引擎，管理下载任务的生命周期
- **特性**:
    - 支持单线程和分片下载
    - 网络状态监控和自动恢复
    - 并发下载控制
    - 断点续传实现

#### 2. 数据实体

- **DownloadTask**: 下载任务实体，包含任务的所有信息
- **DownloadStatus**: 下载状态枚举 (PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED)
- **DownloadMode**: 下载模式枚举 (SINGLE, CHUNKED)
- **DownloadChunk**: 分片下载的分片实体

#### 3. 数据库设计

使用Room数据库进行本地存储：

- **download_tasks**: 存储下载任务信息
- **download_chunks**: 存储分片下载的分片信息

#### 4. 网络层

- 基于OkHttp的网络请求
- 支持HTTP Range请求
- 网络状态监控
- 自动重试机制

## 📁 项目结构

```
downloader/src/main/java/cn/cqautotest/downloader/
├── db/                          # 数据库层
│   ├── dao/                     # 数据访问对象
│   │   ├── ChunkDao.kt         # 分片数据访问
│   │   └── DownloadDao.kt      # 下载任务数据访问
│   └── database/
│       └── AppDatabase.kt      # Room数据库配置
├── di/                          # 依赖注入
│   └── DownloadModule.kt       # 模块依赖配置
├── domain/                      # 领域层
│   ├── DownloadManager.kt      # 核心下载管理器
│   └── usecase/                # 用例层
│       ├── DownloadUseCase.kt  # 下载用例接口
│       └── DownloadUseCaseImpl.kt # 下载用例实现
├── entity/                      # 数据实体
│   ├── ChunkedDownloadConfig.kt # 分片下载配置
│   ├── DownloadChunk.kt        # 下载分片实体
│   ├── DownloadMode.kt         # 下载模式枚举
│   ├── DownloadProgress.kt     # 下载进度实体
│   ├── DownloadStatus.kt       # 下载状态枚举
│   ├── DownloadTask.kt         # 下载任务实体
│   ├── DownloadUiState.kt      # UI状态实体
│   └── FileIntegrityResult.kt  # 文件完整性结果
├── infrastructure/              # 基础设施层
│   ├── file/
│   │   └── FileManager.kt      # 文件管理
│   └── network/
│       └── NetworkManager.kt   # 网络管理
├── MainActivity.kt             # 主活动
├── repository/                 # 仓储层
│   ├── DownloadRepository.kt   # 下载仓储接口
│   └── DownloadRepositoryImpl.kt # 下载仓储实现
├── ui/                         # UI层
│   └── theme/                  # 主题配置
├── util/                       # 工具类
│   ├── error/                  # 错误处理
│   ├── format/                 # 格式化工具
│   └── log/                    # 日志工具
└── viewmodel/                  # ViewModel层
    ├── DownloadLogger.kt       # 下载日志
    ├── DownloadViewModel.kt    # 下载ViewModel
    └── DownloadViewModelFactory.kt # ViewModel工厂
```

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog | 2023.1.1 或更高版本
- Android SDK 24+ (API Level 24)
- Kotlin 1.9+
- JDK 17

### 安装步骤

1. **克隆项目**

```bash
git clone <repository-url>
cd Downloader
```

2. **打开项目**

```bash
# 使用Android Studio打开项目
# 或者使用命令行
./gradlew build
```

3. **运行应用**

```bash
# 连接Android设备或启动模拟器
./gradlew installDebug
```

### 基本使用

#### 1. 初始化下载管理器

```kotlin
// 在Application中初始化
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadModule.initialize(this)
    }
}
```

#### 2. 开始下载

```kotlin
// 单线程下载
val taskId = downloadUseCase.enqueueNewDownload(
    url = "https://example.com/file.zip",
    dirPath = "/storage/emulated/0/Download",
    fileName = "file.zip"
)

// 分片下载
val chunkedConfig = ChunkedDownloadConfig(
    enabled = true,
    chunkSize = 1024 * 1024 * 10, // 10MB分片
    maxConcurrentChunks = 3
)
val taskId = downloadUseCase.enqueueChunkedDownload(
    url = "https://example.com/large-file.zip",
    dirPath = "/storage/emulated/0/Download",
    fileName = "large-file.zip",
    config = chunkedConfig
)
```

#### 3. 监听下载进度

```kotlin
viewModelScope.launch {
    downloadUseCase.getDownloadProgressFlow().collect { progress ->
        // 处理下载进度更新
        updateUI(progress)
    }
}
```

#### 4. 控制下载任务

```kotlin
// 暂停下载
downloadUseCase.pauseDownload(taskId)

// 恢复下载
downloadUseCase.resumeDownload(taskId)

// 取消下载
downloadUseCase.cancelDownload(taskId)

// 重试下载
downloadUseCase.retryDownload(taskId)
```

## ⚙️ 配置选项

### 下载管理器配置

```kotlin
val config = DownloadManager.Config(
    maxConcurrent = 3,              // 最大并发下载数
    connectTimeoutSeconds = 10L,     // 连接超时
    readTimeoutSeconds = 60L,        // 读取超时
    writeTimeoutSeconds = 30L,       // 写入超时
    enableGzip = true               // 启用Gzip压缩
)
```

### 分片下载配置

```kotlin
val chunkedConfig = ChunkedDownloadConfig(
    enabled = true,                  // 启用分片下载
    chunkSize = 1024 * 1024 * 10,   // 分片大小 (10MB)
    maxConcurrentChunks = 3         // 最大并发分片数
)
```

## 🔧 技术栈

### 核心框架

- **Kotlin**: 主要开发语言
- **Jetpack Compose**: 现代化UI框架
- **Room**: 本地数据库
- **OkHttp**: 网络请求库
- **Coroutines**: 异步编程
- **Flow**: 响应式数据流

### 架构组件

- **ViewModel**: MVVM架构的ViewModel层
- **Repository**: 数据访问抽象层
- **UseCase**: 业务逻辑用例层
- **Clean Architecture**: 清洁架构模式

### 工具库

- **Timber**: 日志框架
- **UtilCode**: Android工具库
- **Material Design 3**: 设计系统

## 📱 界面预览

应用提供简洁直观的用户界面：

- **下载列表**: 显示所有下载任务及其状态
- **进度条**: 实时显示下载进度
- **控制按钮**: 暂停、恢复、取消操作
- **状态指示**: 清晰的任务状态显示
- **错误信息**: 详细的错误提示

## 🔍 调试功能

### 日志系统

项目使用Timber进行日志管理，支持：

- 详细的下载过程日志
- 网络状态变化日志
- 错误诊断信息
- 性能监控数据

### 调试信息

```kotlin
// 获取调试信息
val debugInfo = downloadManager.getDebugInfo()
println(debugInfo)
```

## 🧪 测试

### 单元测试

```bash
./gradlew test
```

### 集成测试

```bash
./gradlew connectedAndroidTest
```

## 📄 许可证

本项目采用 [MIT License](LICENSE) 许可证。

## 🤝 贡献

欢迎提交Issue和Pull Request来改进这个项目。

### 贡献指南

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 打开 Pull Request

## 📞 联系方式

如有问题或建议，请通过以下方式联系：

- 提交Issue
- 发送邮件
- 项目讨论区

---

**注意**: 请确保在使用本应用时遵守相关法律法规和网络使用政策。 