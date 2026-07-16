package com.fde.audiosmbsync.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fde.audiosmbsync.AudioSyncApplication
import com.fde.audiosmbsync.data.AppConfig
import com.fde.audiosmbsync.data.Recording
import com.fde.audiosmbsync.security.PasswordCipher
import com.fde.audiosmbsync.smb.SmbUploader
import com.fde.audiosmbsync.worker.DailySyncWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AudioSyncApplication
    val config = app.database.configDao().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val recordings = app.database.recordingDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(deviceCode: String, treeUri: Uri?, host: String, share: String, username: String, password: String) = viewModelScope.launch {
        treeUri?.let { getApplication<Application>().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val old = app.database.configDao().get()
        val encrypted = if (password.isNotBlank()) PasswordCipher.encrypt(password) else null
        app.database.configDao().save(AppConfig(
            deviceCode = deviceCode.trim(), recordingTreeUri = treeUri?.toString() ?: old?.recordingTreeUri.orEmpty(),
            smbHost = host.trim(), shareName = share.trim(), username = username.trim(),
            passwordCiphertext = encrypted?.ciphertext ?: old?.passwordCiphertext.orEmpty(), passwordIv = encrypted?.iv ?: old?.passwordIv.orEmpty(),
            lastSyncAt = old?.lastSyncAt
        ))
    }

    fun testConnection(onResult: (String) -> Unit) = viewModelScope.launch {
        val config = app.database.configDao().get()
        try {
            requireNotNull(config) { "请先保存配置" }
            require(config.passwordCiphertext.isNotBlank()) { "请填写 SMB 密码" }
            SmbUploader(getApplication()).test(config, PasswordCipher.decrypt(config.passwordCiphertext, config.passwordIv))
            onResult("连接成功：MacBook 共享目录可写入")
        } catch (e: Exception) { onResult("连接失败：${e.message ?: "请检查局域网、地址、账号和权限"}") }
    }

    fun syncNow() {
        WorkManager.getInstance(getApplication()).enqueue(OneTimeWorkRequestBuilder<DailySyncWorker>().build())
    }
}
