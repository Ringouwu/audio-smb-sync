package com.fde.audiosmbsync.storage

import com.fde.audiosmbsync.data.RecordedAtSource
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ResolvedRecordingTime(val epochMillis: Long, val source: RecordedAtSource)

/** Deterministic, offline filename parser. A custom regex must capture one 14-digit timestamp in group 1. */
object RecordingTimeResolver {
    private val compact = Regex("(?<!\\d)(20\\d{12})(?!\\d)")
    private val separated = Regex("(20\\d{2})[-_.](\\d{1,2})[-_.](\\d{1,2})[ _-]?(\\d{1,2})[-_.:]?(\\d{1,2})[-_.:]?(\\d{1,2})")
    private val chinese = Regex("(20\\d{2})年(\\d{1,2})月(\\d{1,2})日[ _-]?(\\d{1,2})[时:](\\d{1,2})[分:]?(\\d{1,2})")
    private val outputFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun resolve(sourceName: String?, fallbackModifiedAt: Long, customRegex: String, allowFallback: Boolean): ResolvedRecordingTime? {
        val name = sourceName.orEmpty()
        val candidate = runCatching {
            when {
                customRegex.isNotBlank() -> Regex(customRegex).find(name)?.groupValues?.getOrNull(1)
                else -> compact.find(name)?.groupValues?.get(1)
            }
        }.getOrNull()
        parseCompact(candidate)?.let { return ResolvedRecordingTime(it, RecordedAtSource.FILENAME) }
        parseParts(separated.find(name))?.let { return ResolvedRecordingTime(it, RecordedAtSource.FILENAME) }
        parseParts(chinese.find(name))?.let { return ResolvedRecordingTime(it, RecordedAtSource.FILENAME) }
        return if (allowFallback && fallbackModifiedAt > 0) ResolvedRecordingTime(fallbackModifiedAt, RecordedAtSource.MODIFIED_TIME) else null
    }

    fun formatForUpload(recordedAt: Long): String = outputFormatter.format(
        java.time.Instant.ofEpochMilli(recordedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
    )

    private fun parseCompact(value: String?): Long? = value?.takeIf { it.length == 14 }?.let {
        runCatching { LocalDateTime.parse(it, outputFormatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun parseParts(match: MatchResult?): Long? = match?.let {
        runCatching {
            LocalDateTime.of(
                it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(),
                it.groupValues[4].toInt(), it.groupValues[5].toInt(), it.groupValues[6].toInt()
            ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
