package com.fde.audiosmbsync.smb

import android.content.Context
import android.net.Uri
import com.fde.audiosmbsync.data.AppConfig
import com.fde.audiosmbsync.data.SmbAuthMode
import com.fde.audiosmbsync.data.VerificationMode
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.security.MessageDigest
import java.util.Properties

class SmbUploader(private val context: Context) {
    private fun smbContext(config: AppConfig, password: String): CIFSContext {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.soTimeout", "30000")
        }
        val credentials = if (config.authMode == SmbAuthMode.GUEST) {
            // Some Windows SMB servers grant write access to the Guest account but reject a null session.
            NtlmPasswordAuthenticator("guest", "")
        } else {
            NtlmPasswordAuthenticator(null, config.username, password)
        }
        return BaseContext(PropertyConfiguration(properties)).withCredentials(credentials)
    }

    // SmbFile performs SMB URL escaping itself. Pre-escaping Chinese names here turns '%' into '%25'.
    private fun pathSegment(value: String): String = value
    private fun shareUrl(config: AppConfig): String {
        val host = normalizedHost(config)
        require(host.isNotBlank() && !host.contains('/')) { "服务器地址只能填写 IP 或主机名" }
        require(config.shareName.isNotBlank()) { "请填写共享文件夹名" }
        return "smb://$host/${pathSegment(config.shareName.trim('/'))}/"
    }

    private fun normalizedHost(config: AppConfig): String = config.smbHost.trim().removePrefix("smb://").trimEnd('/')

    fun listShares(config: AppConfig, password: String): List<String> {
        val host = normalizedHost(config)
        require(host.isNotBlank() && !host.contains('/')) { "服务器地址只能填写 IP 或主机名" }
        val server = SmbFile("smb://$host/", smbContext(config, password))
        return server.listFiles().filter { it.isDirectory }.map { it.name.trimEnd('/') }.sorted()
    }

    fun listDirectories(config: AppConfig, password: String, relativePath: String = ""): List<String> {
        val root = SmbFile(shareUrl(config), smbContext(config, password))
        val directory = descend(root, relativePath)
        return directory.listFiles().filter { it.isDirectory }.map { it.name.trimEnd('/') }.sorted()
    }

    private fun descend(root: SmbFile, relativePath: String): SmbFile {
        var current = root
        relativePath.trim('/').split('/').filter { it.isNotBlank() }.forEach { current = SmbFile(current, "${pathSegment(it)}/") }
        return current
    }

    private fun destination(config: AppConfig, password: String, targetDirectory: String = ""): SmbFile {
        val root = SmbFile(shareUrl(config), smbContext(config, password))
        require(root.exists() && root.isDirectory) { "无法访问 SMB 共享目录" }
        val chosen = if (targetDirectory.isBlank()) config.smbSubPath else targetDirectory
        var child = descend(root, chosen)
        if (!child.exists()) child.mkdirs()
        if (!config.createDeviceSubfolder || targetDirectory.isNotBlank()) return child
        val name = config.deviceName.trim()
        require(name.isNotBlank() && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "设备名称不能为空，且不能含 / 或 \\" }
        child = SmbFile(child, "${pathSegment(name)}/")
        if (!child.exists()) child.mkdirs()
        require(child.isDirectory) { "无法创建或访问设备子文件夹" }
        return child
    }

    fun test(config: AppConfig, password: String) {
        val directory = destination(config, password)
        val probe = SmbFile(directory, ".audio-sync-probe-${System.currentTimeMillis()}")
        probe.outputStream.use { it.write("ok".toByteArray()) }
        require(probe.exists() && probe.length() == 2L) { "测试文件写入校验失败" }
        probe.delete()
    }

    fun upload(config: AppConfig, password: String, sourceUri: String, targetName: String, expectedSize: Long, expectedFingerprint: String, targetDirectory: String) {
        val directory = destination(config, password, targetDirectory)
        val finalFile = SmbFile(directory, pathSegment(targetName))
        if (finalFile.exists() && finalFile.length() == expectedSize && verified(finalFile, expectedFingerprint, config.verificationMode)) return
        val tempFile = SmbFile(directory, "${pathSegment(targetName)}.uploading")
        if (tempFile.exists()) tempFile.delete()
        context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
            tempFile.outputStream.use { output -> input.copyTo(output) }
        } ?: error("无法读取本地录音")
        require(tempFile.length() == expectedSize) { "远端临时文件大小校验失败" }
        if (finalFile.exists()) finalFile.delete()
        tempFile.renameTo(finalFile)
        require(finalFile.exists() && finalFile.length() == expectedSize) { "正式文件校验失败" }
        require(verified(finalFile, expectedFingerprint, config.verificationMode)) { "远端文件 SHA-256 校验失败" }
    }

    private fun verified(file: SmbFile, expectedFingerprint: String, mode: VerificationMode): Boolean {
        if (mode == VerificationMode.SIZE_ONLY) return true
        return sha256(file) == expectedFingerprint
    }

    private fun sha256(file: SmbFile): String = file.inputStream.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
