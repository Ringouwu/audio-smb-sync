package com.fde.audiosmbsync.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.fde.audiosmbsync.data.Recording
import com.fde.audiosmbsync.data.RecordingDao
import java.security.MessageDigest

class RecordingScanner(private val context: Context, private val dao: RecordingDao) {
    suspend fun scan(treeUri: String, deviceCode: String): Int {
        require(treeUri.isNotBlank() && deviceCode.isNotBlank()) { "请先完成设备编码和录音目录设置" }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: error("无法打开录音目录")
        var discovered = 0
        root.listFiles().filter { it.isFile && it.name?.endsWith(".mp3", true) == true }.forEach { file ->
            val fingerprint = context.contentResolver.openInputStream(file.uri)?.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().joinToString("") { "%02x".format(it) }
            } ?: return@forEach
            if (dao.byFingerprint(fingerprint) != null) return@forEach
            val recordedAt = RecordingTimeResolver.resolve(file.name, file.lastModified())
            val timestamp = RecordingTimeResolver.formatForUpload(recordedAt)
            val target = "$deviceCode$timestamp.mp3"
            if (dao.byTargetName(target) != null) {
                dao.insert(Recording(
                    sourceUri = file.uri.toString(), sourceName = file.name ?: "unknown.mp3", fingerprint = fingerprint,
                    sizeBytes = file.length(), recordedAt = recordedAt, targetName = target,
                    status = com.fde.audiosmbsync.data.UploadStatus.FAILED,
                    lastError = "命名冲突：同一设备同一秒存在多份录音，请人工处理"
                ))
                discovered++
                return@forEach
            }
            dao.insert(Recording(sourceUri = file.uri.toString(), sourceName = file.name ?: "unknown.mp3", fingerprint = fingerprint, sizeBytes = file.length(), recordedAt = recordedAt, targetName = target))
            discovered++
        }
        return discovered
    }
}
