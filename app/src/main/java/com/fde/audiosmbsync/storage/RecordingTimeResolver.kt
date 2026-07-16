package com.fde.audiosmbsync.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音时间绝不取同步时间。优先识别厂商文件名中的时间；未适配的机型才回退文件最后修改时间。
 * 后续接入特定厂商时，只需在 FILE_NAME_FORMATS 中追加其录音文件名格式。
 */
object RecordingTimeResolver {
    private val fileNameFormats = listOf(
        Regex("(20\\d{6})[_-]?(\\d{6})"), // 20260716_020530 / 20260716-020530
        Regex("(20\\d{2})[-_.](\\d{2})[-_.](\\d{2})[ _-]?(\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})")
    )

    fun resolve(sourceName: String?, fallbackModifiedAt: Long): Long {
        val name = sourceName.orEmpty()
        fileNameFormats.firstOrNull { it.containsMatchIn(name) }?.find(name)?.let { match ->
            val compact = match.groupValues.drop(1).joinToString("")
            val pattern = if (compact.length == 14) "yyyyMMddHHmmss" else ""
            if (pattern.isNotEmpty()) {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(compact)?.time?.let { return it }
            }
        }
        return fallbackModifiedAt
    }

    fun formatForUpload(recordedAt: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(recordedAt))
}
