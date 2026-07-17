package com.fde.audiosmbsync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fde.audiosmbsync.AudioSyncApplication
import com.fde.audiosmbsync.data.SmbAuthMode
import com.fde.audiosmbsync.data.UploadStatus
import com.fde.audiosmbsync.data.SyncRun
import com.fde.audiosmbsync.data.SyncRunItem
import com.fde.audiosmbsync.data.SyncRunStatus
import com.fde.audiosmbsync.data.SyncItemResult
import com.fde.audiosmbsync.security.PasswordCipher
import com.fde.audiosmbsync.smb.SmbUploader
import com.fde.audiosmbsync.storage.RecordingScanner

class DailySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AudioSyncApplication
        val config = app.database.configDao().get() ?: return Result.success()
        if (config.recordingTreeUri.isBlank() || config.smbHost.isBlank() || config.shareName.isBlank()) return Result.success()
        if (config.authMode == SmbAuthMode.PASSWORD && config.passwordCiphertext.isBlank()) return Result.failure()
        val logDao = app.database.syncLogDao()
        val runId = logDao.start(SyncRun(startedAt = System.currentTimeMillis(), range = config.syncRange))
        var scanned = 0; var skipped = 0; var uploaded = 0; var failed = 0
        return try {
            val scanSummary = RecordingScanner(applicationContext, app.database.recordingDao()).scan(config)
            scanned = scanSummary.discovered
            skipped = scanSummary.skipped
            val password = if (config.authMode == SmbAuthMode.GUEST) "" else PasswordCipher.decrypt(config.passwordCiphertext, config.passwordIv)
            val uploader = SmbUploader(applicationContext)
            app.database.recordingDao().pending().forEach { recording ->
                app.database.recordingDao().updateStatus(recording.id, UploadStatus.UPLOADING, recording.retryCount, null, null)
                try {
                    uploader.upload(config, password, recording.sourceUri, recording.targetName, recording.sizeBytes, recording.fingerprint, recording.targetDirectory)
                    app.database.recordingDao().updateStatus(recording.id, UploadStatus.UPLOADED, recording.retryCount, null, System.currentTimeMillis())
                    logDao.addItem(SyncRunItem(runId=runId,recordingId=recording.id,sourceName=recording.sourceName,targetName=recording.targetName,result=SyncItemResult.UPLOADED,processedAt=System.currentTimeMillis()))
                    uploaded++
                } catch (error: Exception) {
                    app.database.recordingDao().updateStatus(recording.id, UploadStatus.FAILED, recording.retryCount + 1, error.message ?: error.javaClass.simpleName, null)
                    logDao.addItem(SyncRunItem(runId=runId,recordingId=recording.id,sourceName=recording.sourceName,targetName=recording.targetName,result=SyncItemResult.FAILED,reason=error.message,processedAt=System.currentTimeMillis()))
                    failed++
                    throw error
                }
            }
            app.database.configDao().save(config.copy(lastSyncAt = System.currentTimeMillis()))
            logDao.finish(runId,System.currentTimeMillis(),scanned,skipped,uploaded,failed,if(failed>0) SyncRunStatus.PARTIAL_FAILURE else SyncRunStatus.SUCCESS,null)
            logDao.pruneRuns(System.currentTimeMillis()-90L*86_400_000L)
            logDao.pruneCount()
            Result.success()
        } catch (e: Exception) { logDao.finish(runId,System.currentTimeMillis(),scanned,skipped,uploaded,failed+1,SyncRunStatus.FAILED,e.message); Result.retry() }
    }
}
