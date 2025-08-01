package cn.cqautotest.downloader.util

import android.util.Base64
import java.security.MessageDigest

/**
 * 计算流数据的MD5
 */
class StreamingMd5(algorithm: String = "MD5") {

    private val md = MessageDigest.getInstance(algorithm)

    fun update(buffer: ByteArray, offset: Int, len: Int) = md.update(buffer, offset, len)

    fun digestBase64(): String = Base64.encodeToString(md.digest(), Base64.NO_WRAP)
}