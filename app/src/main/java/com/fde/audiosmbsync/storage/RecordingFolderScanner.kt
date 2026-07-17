package com.fde.audiosmbsync.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class FolderCandidate(val uri: String, val name: String, val audioCount: Int, val latestModifiedAt: Long)

class RecordingFolderScanner(private val context: Context) {
    private val extensions = setOf("mp3", "m4a", "amr", "aac", "wav", "ogg")
    private val keywords = listOf("录音", "通话", "call", "record", "sound_recorder")

    fun scan(parentUri: String): List<FolderCandidate> {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(parentUri)) ?: error("无法打开扫描目录")
        val candidates = mutableListOf<FolderCandidate>()
        fun inspect(folder: DocumentFile, depth: Int) {
            val children = folder.listFiles()
            val audio = children.filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in extensions }
            val keyword = keywords.any { folder.name.orEmpty().lowercase().contains(it) }
            if (audio.isNotEmpty() && (keyword || audio.size >= 2)) {
                candidates += FolderCandidate(folder.uri.toString(), folder.name ?: "录音目录", audio.size, audio.maxOfOrNull { it.lastModified() } ?: 0)
            }
            if (depth < 2) children.filter { it.isDirectory }.forEach { inspect(it, depth + 1) }
        }
        inspect(root, 0)
        return candidates.distinctBy { it.uri }.sortedByDescending { it.latestModifiedAt }
    }
}
