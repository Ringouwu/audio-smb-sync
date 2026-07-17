package com.fde.audiosmbsync.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AudioSyncApplication
    val config = app.database.configDao().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val recordings = app.database.recordingDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val syncRuns = app.database.syncLogDao().observeRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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

    fun save(config: AppConfig, password: String, treeUri: Uri?, onResult: (String) -> Unit) = viewModelScope.launch {
        val result = runCatching {
            require(config.smbHost.isNotBlank() && config.shareName.isNotBlank()) { "请选择服务器和目标共享文件夹" }
            if (config.renameOnUpload) require(config.salesPhoneNumber.matches(Regex("\\d{11}"))) { "销售手机号必须为 11 位数字" }
            if (config.authMode == SmbAuthMode.PASSWORD) require(config.username.isNotBlank()) { "请填写 SMB 用户名" }
            treeUri?.let { getApplication<Application>().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val old = withContext(Dispatchers.IO) { app.database.configDao().get() }
            val samePasswordTarget = old?.authMode == SmbAuthMode.PASSWORD &&
                old.smbHost == config.smbHost && old.shareName == config.shareName && old.username == config.username
            if (config.authMode == SmbAuthMode.PASSWORD && password.isBlank() && (!samePasswordTarget || old?.passwordCiphertext.isNullOrBlank())) {
                error("首次设置或更换服务器/账号时，请填写 SMB 密码")
            }
            val encrypted = if (config.authMode == SmbAuthMode.PASSWORD && password.isNotBlank()) PasswordCipher.encrypt(password) else null
            val saved = config.copy(
                recordingTreeUri = treeUri?.toString() ?: old?.recordingTreeUri.orEmpty(),
                passwordCiphertext = encrypted?.ciphertext ?: if (config.authMode == SmbAuthMode.GUEST) "" else old?.passwordCiphertext.orEmpty(),
                passwordIv = encrypted?.iv ?: if (config.authMode == SmbAuthMode.GUEST) "" else old?.passwordIv.orEmpty()
            )
            withContext(Dispatchers.IO) { app.database.configDao().save(saved) }
            SyncScheduler.schedule(WorkManager.getInstance(getApplication()), saved)
            "配置已保存"
        }.getOrElse { it.message ?: it.javaClass.simpleName }
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
        onResult(result)
    }

    fun loadDirectories(config: AppConfig, passwordInput: String, path: String, onResult: (String) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            _availableDirectories.value = SmbUploader(getApplication()).listDirectories(config, resolvePassword(config, passwordInput), path)
            "已读取子目录"
        }.getOrElse { "读取目录失败：${it.message ?: it.javaClass.simpleName}" } }
        onResult(result)
    }

    fun scanRecordingFolders(parentUri: Uri, onResult: (String) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(parentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val found = RecordingFolderScanner(getApplication()).scan(parentUri.toString())
            _folderCandidates.value = found
            if (found.isEmpty()) "未发现符合特征的录音文件夹" else "发现 ${found.size} 个候选录音文件夹，请选择"
        }.getOrElse { "扫描失败：${it.message ?: it.javaClass.simpleName}" } }
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

    fun syncNow() = SyncScheduler.scheduleOnce(WorkManager.getInstance(getApplication()))
    fun syncAfter(delayMinutes: Long) = SyncScheduler.scheduleOnce(WorkManager.getInstance(getApplication()), delayMinutes)
}
