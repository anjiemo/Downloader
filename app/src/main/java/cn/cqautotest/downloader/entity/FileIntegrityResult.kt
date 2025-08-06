package cn.cqautotest.downloader.entity

/**
 * 文件完整性检查结果
 */
data class FileIntegrityResult(
    val isValid: Boolean,
    val actualSize: Long,
    val expectedSize: Long,
    val corruptionPoint: Long? = null, // 如果文件损坏，记录损坏位置
    val errorMessage: String? = null
)