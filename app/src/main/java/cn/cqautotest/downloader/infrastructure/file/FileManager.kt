package cn.cqautotest.downloader.infrastructure.file

import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

class FileManager {

    /**
     * 确保目录存在，如果不存在则创建
     */
    fun ensureDirectoryExists(dirPath: String): Boolean =
        File(dirPath).let { it.exists() || it.mkdirs() }

    /**
     * 删除文件
     */
    fun deleteFile(filePath: String): Boolean {
        val file = File(filePath)
        return deleteFile(file)
    }

    /**
     * 删除文件
     */
    fun deleteFile(file: File): Boolean {
        return if (file.exists()) file.delete() else true
    }

    fun resolveFileName(
        url: String,
        responseHeaders: Map<String, String>,
        customFileName: String?,
        useCustomFileName: Boolean
    ): String {
        // 如果明确使用自定义文件名，则直接返回自定义文件名
        if (useCustomFileName) {
            return customFileName ?: "download"
        }

        // 尝试从 Content-Disposition 提取文件名
        val disposition = responseHeaders["Content-Disposition"].orEmpty()
        val dispositionName = disposition
            .substringAfter("filename=", "")
            .removeSurrounding("\"")
            .substringBefore(';')
            .takeIf { it.isNotBlank() }

        // 尝试从 URL 提取文件名
        val uri = try {
            url.trim().toUri()
        } catch (e: Exception) {
            Timber.w(e, "非法 URI 格式: $url")
            null
        }
        val uriFileName = uri?.lastPathSegment?.takeIf { it.isNotBlank() }

        // 尝试从 MIME 类型推断扩展名
        val mimeType = responseHeaders["Content-Type"]?.lowercase()?.substringBefore(';')?.trim()
        val inferredExt = mimeType?.let {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(it)?.let { ext -> ".$ext" }
        }

        // 如果 Content-Disposition 提取成功，直接使用
        if (dispositionName != null) {
            return dispositionName
        }

        // 如果 URI 提取成功，直接使用
        if (uriFileName != null) {
            return uriFileName
        }

        // 如果 MIME 类型推断成功，尝试使用
        if (inferredExt != null) {
            return "download$inferredExt"
        }

        // 如果所有自动推断都失败，使用外部传入的文件名
        return customFileName ?: "download.bin"
    }

    fun calculateFileMd5(file: File): String {
        return file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            val md = MessageDigest.getInstance("MD5")
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
            Base64.encodeToString(md.digest(), Base64.NO_WRAP)
        }
    }
}