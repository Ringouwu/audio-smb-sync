package com.fde.audiosmbsync.smb

import android.content.Context
import android.net.Uri
import com.fde.audiosmbsync.data.AppConfig
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

class SmbUploader(private val context: Context) {
    private fun smbContext(config: AppConfig, password: String): CIFSContext {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.soTimeout", "30000")
        }
        return BaseContext(PropertyConfiguration(properties)).withCredentials(NtlmPasswordAuthenticator(null, config.username, password))
    }
    private fun shareUrl(config: AppConfig) = "smb://${config.smbHost.trimEnd('/')}/${config.shareName.trim('/')}/"
    fun test(config: AppConfig, password: String) {
        val root = SmbFile(shareUrl(config), smbContext(config, password))
        require(root.exists() && root.isDirectory) { "无法访问 SMB 共享目录" }
        val probe = SmbFile(root, ".audio-sync-probe-${System.currentTimeMillis()}")
        probe.outputStream.use { it.write("ok".toByteArray()) }
        probe.delete()
    }
    fun upload(config: AppConfig, password: String, sourceUri: String, targetName: String, expectedSize: Long) {
        val root = SmbFile(shareUrl(config), smbContext(config, password))
        require(root.exists() && root.isDirectory) { "无法访问 SMB 共享目录" }
        val finalFile = SmbFile(root, targetName)
        if (finalFile.exists() && finalFile.length() == expectedSize) return
        val tempFile = SmbFile(root, "$targetName.uploading")
        if (tempFile.exists()) tempFile.delete()
        context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input -> tempFile.outputStream.use { output -> input.copyTo(output) } } ?: error("无法读取本地录音")
        require(tempFile.length() == expectedSize) { "远端文件大小校验失败" }
        if (finalFile.exists()) finalFile.delete()
        tempFile.renameTo(finalFile)
    }
}
