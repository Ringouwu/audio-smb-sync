package com.fde.audiosmbsync.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.fde.audiosmbsync.AudioSyncApplication
import com.fde.audiosmbsync.data.AppConfig
import com.fde.audiosmbsync.data.SmbAuthMode
import com.fde.audiosmbsync.security.PasswordCipher
import com.fde.audiosmbsync.smb.SmbUploader
import com.fde.audiosmbsync.storage.FolderCandidate
import com.fde.audiosmbsync.storage.RecordingFolderScanner
import com.fde.audiosmbsync.worker.SyncScheduler
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    companion object { private const val TAG = "AudioSmbSync.WORKER" }
    private val app = application as AudioSyncApplication
    val config = app.database.configDao().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val recordings = app.database.recordingDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val syncRuns = app.database.syncLogDao().observeRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events = app.database.appEventDao().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _availableShares = MutableStateFlow<List<String>>(emptyList())
    val availableShares = _availableShares
    private val _discoveredHosts = MutableStateFlow<List<String>>(emptyList())
    val discoveredHosts = _discoveredHosts
    private val _isScanningNetwork = MutableStateFlow(false)
    val isScanningNetwork = _isScanningNetwork
    private val _availableDirectories = MutableStateFlow<List<String>>(emptyList())
    val availableDirectories = _availableDirectories
    private val _folderCandidates = MutableStateFlow<List<FolderCandidate>>(emptyList())
    val folderCandidates = _folderCandidates

    fun save(config: AppConfig, password: String, onResult: (String) -> Unit) = viewModelScope.launch {
        val result = runCatching {
            require(config.smbHost.isNotBlank() && config.shareName.isNotBlank()) { "请选择服务器和目标共享文件夹" }
            if (config.authMode == SmbAuthMode.PASSWORD) require(config.username.isNotBlank()) { "请填写 SMB 用户名" }
            val old = withContext(Dispatchers.IO) { app.database.configDao().get() }
            val samePasswordTarget = old?.authMode == SmbAuthMode.PASSWORD &&
                old.smbHost == config.smbHost && old.shareName == config.shareName && old.username == config.username
            if (config.authMode == SmbAuthMode.PASSWORD && password.isBlank() && (!samePasswordTarget || old?.passwordCiphertext.isNullOrBlank())) {
                error("首次设置或更换服务器/账号时，请填写 SMB 密码")
            }
            val encrypted = if (config.authMode == SmbAuthMode.PASSWORD && password.isNotBlank()) PasswordCipher.encrypt(password) else null
            val saved = config.copy(
                passwordCiphertext = encrypted?.ciphertext ?: if (config.authMode == SmbAuthMode.GUEST) "" else old?.passwordCiphertext.orEmpty(),
                passwordIv = encrypted?.iv ?: if (config.authMode == SmbAuthMode.GUEST) "" else old?.passwordIv.orEmpty()
            )
            withContext(Dispatchers.IO) { app.database.configDao().save(saved) }
            SyncScheduler.schedule(WorkManager.getInstance(getApplication()), saved)
            "配置已保存"
        }.getOrElse { it.message ?: it.javaClass.simpleName }
        recordEvent(if (result == "配置已保存") "配置已保存" else "保存配置失败：$result", if (result == "配置已保存") "INFO" else "ERROR")
        onResult(result)
    }

    fun selectManualRecordingFolder(uri: Uri, onResult: (String) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            "已获得录音文件夹访问权限"
        }.getOrElse { "录音文件夹授权失败：${it.message ?: it.javaClass.simpleName}" } }
        recordEvent(result, if (result.startsWith("已获得")) "INFO" else "ERROR")
        onResult(result)
    }

    fun validateRecordingFolder(treeUri: String, relativePath: String, onResult: (String) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                require(treeUri.isNotBlank()) { "请先选择录音文件夹" }
                var folder = DocumentFile.fromTreeUri(getApplication(), Uri.parse(treeUri))
                    ?: error("无法打开所选文件夹，请重新选择")
                relativePath.trim('/').split('/').filter { it.isNotBlank() }.forEach { segment ->
                    folder = folder.listFiles().firstOrNull { it.isDirectory && it.name == segment }
                        ?: error("已选择的录音子目录不存在，请重新选择")
                }
                require(folder.canRead()) { "App 没有该文件夹的读取权限，请重新授权" }
                val fileCount = folder.listFiles().count { it.isFile }
                "录音文件夹可用：发现 $fileCount 个文件"
            }.getOrElse { "录音文件夹验证失败：${it.message ?: it.javaClass.simpleName}" }
        }
        recordEvent(result, if (result.startsWith("录音文件夹可用")) "INFO" else "ERROR")
        onResult(result)
    }

    fun loadShares(config: AppConfig, passwordInput: String, onResult: (String) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val password = resolvePassword(config, passwordInput)
                val shares = SmbUploader(getApplication()).listShares(config, password)
                _availableShares.value = shares
                if (shares.isEmpty()) "连接成功，但未发现可访问的共享文件夹" else "连接成功：请选择目标共享文件夹"
            }.getOrElse { "连接失败：${it.message ?: it.javaClass.simpleName}" }
        }
        recordEvent(if (result.startsWith("连接成功")) "SMB 服务器已连接：${config.smbHost}" else result, if (result.startsWith("连接成功")) "INFO" else "ERROR")
        onResult(result)
    }

    fun testSelectedShare(config: AppConfig, passwordInput: String, onResult: (String) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                require(config.shareName.isNotBlank()) { "请先从列表选择目标共享文件夹" }
                SmbUploader(getApplication()).test(config, resolvePassword(config, passwordInput))
                "目标共享文件夹可写入"
            }.getOrElse { "连接失败：${it.message ?: it.javaClass.simpleName}" }
        }
        recordEvent(if (result.startsWith("目标共享")) "SMB 输出目录已验证可写：/${config.shareName}/${config.smbSubPath}" else result, if (result.startsWith("目标共享")) "INFO" else "ERROR")
        onResult(result)
    }

    fun loadDirectories(config: AppConfig, passwordInput: String, path: String, onResult: (String) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            _availableDirectories.value = SmbUploader(getApplication()).listDirectories(config, resolvePassword(config, passwordInput), path)
            "已读取子目录"
        }.getOrElse { "读取目录失败：${it.message ?: it.javaClass.simpleName}" } }
        recordEvent(result, if (result.startsWith("已读取")) "INFO" else "ERROR")
        onResult(result)
    }

    fun scanRecordingFolders(parentUri: Uri, onResult: (String) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(parentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val found = RecordingFolderScanner(getApplication()).scan(parentUri.toString())
            _folderCandidates.value = found
            if (found.isEmpty()) "未发现符合特征的录音文件夹" else "发现 ${found.size} 个候选录音文件夹，请选择"
        }.getOrElse { "扫描失败：${it.message ?: it.javaClass.simpleName}" } }
        recordEvent(result, if (result.startsWith("发现") || result.startsWith("未发现")) "INFO" else "ERROR")
        onResult(result)
    }

    fun scanLocalNetwork(onResult: (String) -> Unit) = viewModelScope.launch {
        _isScanningNetwork.value = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val localIp = NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress && !it.isLinkLocalAddress }
                    ?: error("未找到当前局域网 IPv4 地址")
                val prefix = localIp.hostAddress.substringBeforeLast('.')
                val semaphore = Semaphore(24)
                val hosts = coroutineScope {
                    (1..254).map { last -> async {
                        semaphore.withPermit {
                            val host = "$prefix.$last"
                            val reachable = runCatching { Socket().use { it.connect(InetSocketAddress(host, 445), 450) } }.isSuccess
                            host.takeIf { reachable }
                        }
                    } }.awaitAll().filterNotNull()
                }
                _discoveredHosts.value = hosts
                if (hosts.isEmpty()) "扫描完成：未发现 SMB 服务" else "扫描完成：发现 ${hosts.size} 个 SMB 服务，请选择服务器"
            }.getOrElse { "扫描失败：${it.message ?: it.javaClass.simpleName}" }
        }
        _isScanningNetwork.value = false
        recordEvent(result, if (result.startsWith("扫描完成")) "INFO" else "ERROR")
        onResult(result)
    }

    private suspend fun resolvePassword(config: AppConfig, passwordInput: String): String {
        if (config.authMode == SmbAuthMode.GUEST) return ""
        if (passwordInput.isNotBlank()) return passwordInput
        val old = app.database.configDao().get()
        val sameTarget = old?.authMode == SmbAuthMode.PASSWORD && old.smbHost == config.smbHost && old.username == config.username
        require(sameTarget && !old?.passwordCiphertext.isNullOrBlank()) { "请填写 SMB 密码" }
        return PasswordCipher.decrypt(old!!.passwordCiphertext, old.passwordIv)
    }

    fun recordEvent(message: String, level: String = "INFO") = viewModelScope.launch(Dispatchers.IO) {
        app.database.appEventDao().add(com.fde.audiosmbsync.data.AppEvent(message = message, level = level))
        app.database.appEventDao().prune()
    }

    fun syncNow() {
        val workManager = WorkManager.getInstance(getApplication())
        val workId = SyncScheduler.scheduleOnce(workManager)
        Log.i(TAG, "event=manual_sync_enqueued work_id=$workId")
        recordEvent("同步任务已加入队列，等待开始")
        viewModelScope.launch {
            delay(20_000)
            val state = withContext(Dispatchers.IO) { workManager.getWorkInfoById(workId).get()?.state }
            when (state) {
                WorkInfo.State.ENQUEUED -> { Log.w(TAG, "event=manual_sync_still_enqueued work_id=$workId"); recordEvent("同步仍在等待：请检查手机网络是否可用，或稍后再试", "ERROR") }
                WorkInfo.State.FAILED -> { Log.e(TAG, "event=manual_sync_failed work_id=$workId"); recordEvent("同步任务未能启动：请检查实时状态中的配置错误", "ERROR") }
                WorkInfo.State.CANCELLED -> { Log.e(TAG, "event=manual_sync_cancelled work_id=$workId"); recordEvent("同步任务已被系统取消", "ERROR") }
                else -> Unit
            }
        }
    }
    fun syncAfter(delayMinutes: Long) { SyncScheduler.scheduleOnce(WorkManager.getInstance(getApplication()), delayMinutes); recordEvent("延后 $delayMinutes 分钟同步已加入队列") }
}
