package com.fde.audiosmbsync.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.fde.audiosmbsync.data.AppConfig
import com.fde.audiosmbsync.data.Recording
import com.fde.audiosmbsync.data.RecordingDao
import com.fde.audiosmbsync.data.UploadStatus
import com.fde.audiosmbsync.data.SyncRange
import java.security.MessageDigest

class RecordingScanner(private val context: Context, private val dao: RecordingDao) {
    companion object { private const val TAG = "AudioSmbSync.SCAN" }

    data class ScanSummary(val discovered: Int, val skipped: Int)

    suspend fun scan(config: AppConfig): ScanSummary {
        Log.i(TAG, "event=scan_start relative_path=${config.recordingRelativePath} range=${config.syncRange} rename=${config.renameOnUpload}")
        require(config.recordingTreeUri.isNotBlank()) { "请先选择录音目录" }
        var root = DocumentFile.fromTreeUri(context, Uri.parse(config.recordingTreeUri)) ?: error("无法打开录音目录")
        config.recordingRelativePath.trim('/').split('/').filter { it.isNotBlank() }.forEach { segment ->
            root = root.listFiles().firstOrNull { it.isDirectory && it.name == segment } ?: error("已选择的录音文件夹不存在：${config.recordingRelativePath}")
        }
        var discovered = 0; var skipped = 0
        val files = root.listFiles()
        Log.i(TAG, "event=scan_directory_opened child_count=${files.size} relative_path=${config.recordingRelativePath}")
        files.filter { it.isFile && inRange(it.lastModified(), config) }.forEach { file ->
            val sourceName = file.name ?: return@forEach
            val fingerprint = sha256(file.uri) ?: return@forEach
            if (dao.byFingerprint(fingerprint) != null) { skipped++; Log.d(TAG, "event=scan_skip_duplicate source=$sourceName"); return@forEach }
            val extension = extensionOf(sourceName)
            val inheritedPhone = Regex("(?<!\\d)(1\\d{10})(?!\\d)").find(sourceName)?.groupValues?.get(1)
            val recordedAt = file.lastModified()
            val extensionSuffix = extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
            val target = if (config.renameOnUpload && inheritedPhone != null) "${inheritedPhone}_${RecordingTimeResolver.formatForUpload(recordedAt)}$extensionSuffix" else sourceName
            val baseDirectory = config.smbSubPath.trim('/')
            val targetDirectory = listOf(baseDirectory, if (config.createDeviceSubfolder) config.deviceName.trim() else "").filter { it.isNotBlank() }.joinToString("/")
            if (dao.byTargetName(target, targetDirectory) != null) {
                dao.insert(Recording(
                    sourceUri = file.uri.toString(), sourceName = sourceName, fingerprint = fingerprint, sizeBytes = file.length(),
                    recordedAt = recordedAt, recordedAtSource = if (config.renameOnUpload) com.fde.audiosmbsync.data.RecordedAtSource.MODIFIED_TIME else com.fde.audiosmbsync.data.RecordedAtSource.NOT_REQUIRED,
                    targetName = target, targetDirectory = targetDirectory, status = UploadStatus.FAILED,
                    lastError = "命名冲突：同一目标目录已有同名录音，请人工处理"
                ))
                discovered++
                Log.w(TAG, "event=scan_name_conflict source=$sourceName target=$target directory=$targetDirectory")
                return@forEach
            }
            dao.insert(Recording(
                sourceUri = file.uri.toString(), sourceName = sourceName, fingerprint = fingerprint, sizeBytes = file.length(),
                recordedAt = recordedAt, recordedAtSource = if (config.renameOnUpload) com.fde.audiosmbsync.data.RecordedAtSource.MODIFIED_TIME else com.fde.audiosmbsync.data.RecordedAtSource.NOT_REQUIRED,
                targetName = target, targetDirectory = targetDirectory
            ))
            discovered++
            Log.d(TAG, "event=scan_record_queued source=$sourceName target=$target bytes=${file.length()}")
        }
        Log.i(TAG, "event=scan_complete discovered=$discovered skipped=$skipped")
        return ScanSummary(discovered, skipped)
    }

    private fun extensionOf(name: String?): String = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    private fun inRange(modifiedAt: Long, config: AppConfig): Boolean = when (config.syncRange) {
        SyncRange.ALL -> true
        SyncRange.RECENT_DAYS -> modifiedAt >= System.currentTimeMillis() - config.recentDays.coerceAtLeast(1) * 86_400_000L
        SyncRange.CUSTOM_START -> modifiedAt >= (config.customStartAt ?: Long.MAX_VALUE)
    }
    private fun sha256(uri: Uri): String? = context.contentResolver.openInputStream(uri)?.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
