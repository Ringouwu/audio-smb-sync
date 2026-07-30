package com.fde.audiosmbsync.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

data class FolderCandidate(val treeUri: String, val relativePath: String, val name: String, val fileCount: Int, val latestModifiedAt: Long)

class RecordingFolderScanner(private val context: Context) {
    companion object { private const val TAG = "AudioSmbSync.SCAN" }
    private val keywords = listOf("录音", "通话", "call", "record", "sound_recorder")

    fun scan(parentUri: String): List<FolderCandidate> {
        Log.i(TAG, "event=candidate_scan_start")
        val root = DocumentFile.fromTreeUri(context, Uri.parse(parentUri)) ?: error("无法打开扫描目录")
        val candidates = mutableListOf<FolderCandidate>()
        fun inspect(folder: DocumentFile, depth: Int, relativePath: String) {
            val children = folder.listFiles()
            val files = children.filter { it.isFile }
            val keyword = keywords.any { folder.name.orEmpty().lowercase().contains(it) }
            if (files.isNotEmpty() && (keyword || files.size >= 2)) {
                candidates += FolderCandidate(root.uri.toString(), relativePath, folder.name ?: "录音目录", files.size, files.maxOfOrNull { it.lastModified() } ?: 0)
            }
            if (depth < 2) children.filter { it.isDirectory }.forEach { child -> inspect(child, depth + 1, listOf(relativePath, child.name.orEmpty()).filter { it.isNotBlank() }.joinToString("/")) }
        }
        inspect(root, 0, "")
        return candidates.distinctBy { "${it.treeUri}/${it.relativePath}" }.sortedByDescending { it.latestModifiedAt }.also {
            Log.i(TAG, "event=candidate_scan_complete candidates=${it.size}")
        }
    }
}
