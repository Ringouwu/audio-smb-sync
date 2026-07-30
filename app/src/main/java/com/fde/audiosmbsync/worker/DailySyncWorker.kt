package com.fde.audiosmbsync.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fde.audiosmbsync.AudioSyncApplication
import com.fde.audiosmbsync.data.SmbAuthMode
import com.fde.audiosmbsync.data.UploadStatus
import com.fde.audiosmbsync.data.SyncRun
import com.fde.audiosmbsync.data.SyncRunItem
import com.fde.audiosmbsync.data.SyncRunStatus
import com.fde.audiosmbsync.data.SyncItemResult
import com.fde.audiosmbsync.data.AppEvent
import com.fde.audiosmbsync.security.PasswordCipher
import com.fde.audiosmbsync.smb.SmbUploader
import com.fde.audiosmbsync.storage.RecordingScanner

class DailySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { private const val TAG = "AudioSmbSync.WORKER" }
    override suspend fun doWork(): Result {
        Log.i(TAG, "event=worker_start id=$id run_attempt=$runAttemptCount")
        val app = applicationContext as AudioSyncApplication
        val logDao = app.database.syncLogDao()
        val eventDao = app.database.appEventDao()
        val config = app.database.configDao().get()
        if (config == null) {
            Log.e(TAG, "event=worker_configuration_missing reason=no_config")
            eventDao.add(AppEvent(message = "同步未开始：尚未保存同步配置", level = "ERROR"))
            return Result.failure()
        }
        val missing = buildList {
            if (config.recordingTreeUri.isBlank()) add("本地录音目录")
            if (config.smbHost.isBlank()) add("SMB 服务器")
            if (config.shareName.isBlank()) add("SMB 输出目录")
            if (config.authMode == SmbAuthMode.PASSWORD && config.passwordCiphertext.isBlank()) add("SMB 密码")
        }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "event=worker_configuration_missing fields=${missing.joinToString(",")}")
            eventDao.add(AppEvent(message = "同步未开始：请先选择并保存${missing.joinToString("、")}", level = "ERROR"))
            return Result.failure()
        }
        val runId = logDao.start(SyncRun(startedAt = System.currentTimeMillis(), range = config.syncRange))
        eventDao.add(AppEvent(message = "同步任务已启动：正在扫描录音文件夹"))
        var scanned = 0; var skipped = 0; var uploaded = 0; var failed = 0
        return try {
            val scanSummary = RecordingScanner(applicationContext, app.database.recordingDao()).scan(config)
            scanned = scanSummary.discovered
            skipped = scanSummary.skipped
            eventDao.add(AppEvent(message = "扫描完成：新增 $scanned 个，已跳过 $skipped 个"))
            if (scanned == 0 && skipped == 0) eventDao.add(AppEvent(message = "没有发现符合当前范围的待上传录音"))
            val password = if (config.authMode == SmbAuthMode.GUEST) "" else PasswordCipher.decrypt(config.passwordCiphertext, config.passwordIv)
            val uploader = SmbUploader(applicationContext)
            Log.i(TAG, "event=worker_upload_phase_start pending=${app.database.recordingDao().pending().size}")
            app.database.recordingDao().pending().forEach { recording ->
                Log.i(TAG, "event=worker_upload_item_start recording_id=${recording.id} target=${recording.targetName} retry=${recording.retryCount}")
                app.database.recordingDao().updateStatus(recording.id, UploadStatus.UPLOADING, recording.retryCount, null, null)
                eventDao.add(AppEvent(message = "开始上传：${recording.targetName}"))
                try {
                    var lastProgress = -1
                    uploader.upload(config, password, recording.sourceUri, recording.targetName, recording.sizeBytes, recording.fingerprint, recording.targetDirectory) { progress ->
                        if (progress >= lastProgress + 25 || progress == 100) {
                            lastProgress = progress
                            eventDao.add(AppEvent(message = "上传中：${recording.targetName} $progress%"))
                        }
                    }
                    app.database.recordingDao().updateStatus(recording.id, UploadStatus.UPLOADED, recording.retryCount, null, System.currentTimeMillis())
                    logDao.addItem(SyncRunItem(runId=runId,recordingId=recording.id,sourceName=recording.sourceName,targetName=recording.targetName,result=SyncItemResult.UPLOADED,processedAt=System.currentTimeMillis()))
                    uploaded++
                    Log.i(TAG, "event=worker_upload_item_success recording_id=${recording.id} target=${recording.targetName}")
                    eventDao.add(AppEvent(message = "上传成功：${recording.targetName}"))
                } catch (error: Exception) {
                    app.database.recordingDao().updateStatus(recording.id, UploadStatus.FAILED, recording.retryCount + 1, error.message ?: error.javaClass.simpleName, null)
                    logDao.addItem(SyncRunItem(runId=runId,recordingId=recording.id,sourceName=recording.sourceName,targetName=recording.targetName,result=SyncItemResult.FAILED,reason=error.message,processedAt=System.currentTimeMillis()))
                    failed++
                    Log.e(TAG, "event=worker_upload_item_failed recording_id=${recording.id} target=${recording.targetName} type=${error.javaClass.simpleName}", error)
                    eventDao.add(AppEvent(message = "上传失败：${recording.targetName}（${error.message ?: error.javaClass.simpleName}）", level = "ERROR"))
                    throw error
                }
            }
            app.database.configDao().save(config.copy(lastSyncAt = System.currentTimeMillis()))
            logDao.finish(runId,System.currentTimeMillis(),scanned,skipped,uploaded,failed,if(failed>0) SyncRunStatus.PARTIAL_FAILURE else SyncRunStatus.SUCCESS,null)
            logDao.pruneRuns(System.currentTimeMillis()-90L*86_400_000L)
            logDao.pruneCount()
            eventDao.add(AppEvent(message = "同步完成：成功 $uploaded 个，失败 $failed 个"))
            eventDao.prune()
            Log.i(TAG, "event=worker_success run_id=$runId scanned=$scanned skipped=$skipped uploaded=$uploaded failed=$failed")
            Result.success()
        } catch (e: Exception) { Log.e(TAG, "event=worker_failed run_id=$runId scanned=$scanned skipped=$skipped uploaded=$uploaded failed=$failed type=${e.javaClass.simpleName}", e); logDao.finish(runId,System.currentTimeMillis(),scanned,skipped,uploaded,failed+1,SyncRunStatus.FAILED,e.message); eventDao.add(AppEvent(message = "同步异常：${e.message ?: e.javaClass.simpleName}，将自动重试", level = "ERROR")); Result.retry() }
    }
}
