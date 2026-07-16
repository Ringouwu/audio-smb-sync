package com.fde.audiosmbsync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.fde.audiosmbsync.data.UploadStatus
import com.fde.audiosmbsync.viewmodel.SyncViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun SyncApp(viewModel: SyncViewModel) {
    val config by viewModel.config.collectAsState()
    val recordings by viewModel.recordings.collectAsState()
    var deviceCode by remember(config) { mutableStateOf(config?.deviceCode.orEmpty()) }
    var host by remember(config) { mutableStateOf(config?.smbHost.orEmpty()) }
    var share by remember(config) { mutableStateOf(config?.shareName.orEmpty()) }
    var username by remember(config) { mutableStateOf(config?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var selectedTree by remember(config) { mutableStateOf(config?.recordingTreeUri.orEmpty()) }
    var message by remember { mutableStateOf("") }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { selectedTree = it.toString() }
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("录音 SMB 同步", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("首次设置")
                OutlinedTextField(deviceCode, { deviceCode = it }, label = { Text("设备编码，例如 XS001") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { folderPicker.launch(null) }) { Text(if (selectedTree.isBlank()) "选择录音文件夹" else "已选择录音文件夹") }
                OutlinedTextField(host, { host = it }, label = { Text("MacBook IP，例如 192.168.1.20") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(share, { share = it }, label = { Text("共享文件夹名") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text("SMB 用户名") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("SMB 密码（留空不修改）") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.save(deviceCode, selectedTree.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse), host, share, username, password); message = "配置已保存" }) { Text("保存") }
                    Button(onClick = { viewModel.testConnection { message = it } }) { Text("测试连接") }
                }
                if (message.isNotBlank()) Text(message)
            } }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val uploaded = recordings.count { it.status == UploadStatus.UPLOADED }
                val pending = recordings.count { it.status != UploadStatus.UPLOADED }
                Text("同步状态")
                Text("已上传 $uploaded 个，待处理/失败 $pending 个")
                Text("最近同步：${config?.lastSyncAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "尚未执行"}")
                Button(onClick = { viewModel.syncNow(); message = "已加入同步队列" }) { Text("立即同步") }
            } }
        }
        item { Text("录音队列") }
        items(recordings, key = { it.id }) { recording ->
            Column(Modifier.fillMaxWidth()) {
                Text(recording.targetName)
                Text("${recording.status}  ${recording.lastError ?: ""}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

/** Android Studio Design 预览入口：不访问 SMB、数据库或手机录音目录。 */
@Preview(showBackground = true, widthDp = 390, heightDp = 840)
@Composable
private fun SyncAppPreview() {
    androidx.compose.material3.MaterialTheme {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("录音 SMB 同步", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("首次设置")
                        OutlinedTextField("XS001", {}, label = { Text("设备编码") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {}) { Text("已选择录音文件夹") }
                        OutlinedTextField("192.168.1.20", {}, label = { Text("MacBook IP") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField("AudioUploads", {}, label = { Text("共享文件夹名") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("同步状态")
                        Text("已上传 12 个，待处理/失败 1 个")
                        Text("最近同步：2026/7/16 02:05")
                        Button(onClick = {}) { Text("立即同步") }
                    }
                }
            }
            item { Text("录音队列") }
            item {
                Column(Modifier.fillMaxWidth()) {
                    Text("XS00120260716_020530.mp3")
                    Text("UPLOADED", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
