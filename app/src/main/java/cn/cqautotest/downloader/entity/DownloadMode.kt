package cn.cqautotest.downloader.entity

enum class DownloadMode {
    SINGLE,        // 单线程下载
    CHUNKED        // 分片下载
}