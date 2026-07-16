package com.fde.audiosmbsync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fde.audiosmbsync.AudioSyncApplication
import com.fde.audiosmbsync.data.UploadStatus
import com.fde.audiosmbsync.security.PasswordCipher
import com.fde.audiosmbsync.smb.SmbUploader
import com.fde.audiosmbsync.storage.RecordingScanner

class DailySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AudioSyncApplication
        val config = app.database.configDao().get() ?: return Result.success()
        if (config.deviceCode.isBlank() || config.recordingTreeUri.isBlank() || config.smbHost.isBlank() || config.shareName.isBlank()) return Result.success()
        return try {
            RecordingScanner(applicationContext, app.database.recordingDao()).scan(config.recordingTreeUri, config.deviceCode)
            val password = PasswordCipher.decrypt(config.passwordCiphertext, config.passwordIv)
            val uploader = SmbUploader(applicationContext)
            app.database.recordingDao().pending().forEach { recording ->
                app.database.recordingDao().updateStatus(recording.id, UploadStatus.UPLOADING, recording.retryCount, null, null)
                try {
                    uploader.upload(config, password, recording.sourceUri, recording.targetName, recording.sizeBytes)
                    app.database.recordingDao().updateStatus(recording.id, UploadStatus.UPLOADED, recording.retryCount, null, System.currentTimeMillis())
                } catch (error: Exception) {
                    app.database.recordingDao().updateStatus(recording.id, UploadStatus.FAILED, recording.retryCount + 1, error.message ?: "上传失败", null)
                    throw error
                }
            }
            app.database.configDao().save(config.copy(lastSyncAt = System.currentTimeMillis()))
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}
