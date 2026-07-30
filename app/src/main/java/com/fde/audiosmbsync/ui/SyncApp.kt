package com.fde.audiosmbsync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fde.audiosmbsync.data.*
import com.fde.audiosmbsync.viewmodel.SyncViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.text.DateFormat
import java.util.Date

@Composable fun SyncApp(viewModel: SyncViewModel) {
    val stored by viewModel.config.collectAsState(); val hosts by viewModel.discoveredHosts.collectAsState(); val shares by viewModel.availableShares.collectAsState(); val dirs by viewModel.availableDirectories.collectAsState(); val candidates by viewModel.folderCandidates.collectAsState(); val runs by viewModel.syncRuns.collectAsState(); val events by viewModel.events.collectAsState(); val scanning by viewModel.isScanningNetwork.collectAsState()
    var deviceName by remember(stored){mutableStateOf(stored?.deviceName.orEmpty())}; var host by remember(stored){mutableStateOf(stored?.smbHost.orEmpty())}; var share by remember(stored){mutableStateOf(stored?.shareName.orEmpty())}; var subPath by remember(stored){mutableStateOf(stored?.smbSubPath.orEmpty())}; var auth by remember(stored){mutableStateOf(stored?.authMode?:SmbAuthMode.GUEST)}; var user by remember(stored){mutableStateOf(stored?.username.orEmpty())}; var pass by remember{mutableStateOf("")}; var recordingUri by remember(stored){mutableStateOf(stored?.recordingTreeUri.orEmpty())}; var recordingRelativePath by remember(stored){mutableStateOf(stored?.recordingRelativePath.orEmpty())}; var createFolder by remember(stored){mutableStateOf(stored?.createDeviceSubfolder?:false)}; var rename by remember(stored){mutableStateOf(stored?.renameOnUpload?:true)}; var verify by remember(stored){mutableStateOf(stored?.verificationMode?:VerificationMode.SIZE_ONLY)}; var range by remember(stored){mutableStateOf(stored?.syncRange?:SyncRange.ALL)}; var days by remember(stored){mutableStateOf((stored?.recentDays?:7).toString())}; var startDate by remember{mutableStateOf("")}; var autoSync by remember(stored){mutableStateOf(stored?.autoSyncEnabled?:true)}; var schedule by remember(stored){mutableStateOf(stored?.syncScheduleMode?:SyncScheduleMode.DAILY)}; var hour by remember(stored){mutableStateOf((stored?.dailySyncHour?:2).toString())}; var minute by remember(stored){mutableStateOf((stored?.dailySyncMinute?:0).toString())}; var interval by remember(stored){mutableStateOf((stored?.intervalMinutes?:1440).toString())}; var delay by remember{mutableStateOf("30")}; var message by remember{mutableStateOf("")}
    fun config()=AppConfig(deviceName=deviceName.trim(),recordingTreeUri=recordingUri,recordingRelativePath=recordingRelativePath,smbHost=host.trim(),shareName=share,smbSubPath=subPath.trim('/'),authMode=auth,username=user.trim(),createDeviceSubfolder=createFolder,renameOnUpload=rename,verificationMode=verify,syncRange=range,recentDays=days.toIntOrNull()?:7,customStartAt=runCatching{LocalDate.parse(startDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()}.getOrNull(),autoSyncEnabled=autoSync,syncScheduleMode=schedule,dailySyncHour=hour.toIntOrNull()?:2,dailySyncMinute=minute.toIntOrNull()?:0,intervalMinutes=interval.toLongOrNull()?:1440,lastSyncAt=stored?.lastSyncAt)
    val manualPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){it?.let{recordingUri=it.toString();recordingRelativePath="";viewModel.selectManualRecordingFolder(it){result->message=result}}}
    val scanPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){it?.let{viewModel.scanRecordingFolders(it){m->message=m}}}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { Text("录音 SMB 同步",style=MaterialTheme.typography.headlineSmall) }
        item { Card { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("本地录音目录",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={manualPicker.launch(null)}){Text(if(recordingUri.isBlank())"手动选择" else "已选择")};Button(onClick={scanPicker.launch(null)}){Text("扫描录音文件夹")}}
            candidates.forEach { c->Button(onClick={recordingUri=c.treeUri;recordingRelativePath=c.relativePath;message="已使用：${c.name}";viewModel.recordEvent("已选择录音文件夹：${c.name}")},modifier=Modifier.fillMaxWidth()){Text("使用 ${c.name}（${c.fileCount} 个文件）")}}
        }}}
        item { Card { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text("自动同步",style=MaterialTheme.typography.titleMedium);Toggle("开启自动同步",autoSync){autoSync=it};if(autoSync){Row{RadioButton(schedule==SyncScheduleMode.DAILY,{schedule=SyncScheduleMode.DAILY});Text("每天");RadioButton(schedule==SyncScheduleMode.INTERVAL,{schedule=SyncScheduleMode.INTERVAL});Text("每隔")};if(schedule==SyncScheduleMode.DAILY){OutlinedTextField(hour,{hour=it},label={Text("时")});OutlinedTextField(minute,{minute=it},label={Text("分")})}else OutlinedTextField(interval,{interval=it},label={Text("间隔分钟，至少15")})};OutlinedTextField(delay,{delay=it},label={Text("延后同步分钟")});Button(onClick={viewModel.syncAfter(delay.toLongOrNull()?:0);message="已加入延后同步"}){Text("延后同步")}}}}
        item { Card { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("SMB 服务器与目录",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(host,{host=it},label={Text("服务器 IP（可扫描或手动填写）")},modifier=Modifier.fillMaxWidth())
            Button(onClick={viewModel.scanLocalNetwork{message=it}},enabled=!scanning){Text(if(scanning)"正在扫描…" else "扫描同网段 SMB 服务")}
            hosts.forEach{ip->OutlinedButton(onClick={host=ip}){Text("选择 $ip")}}
            Row(verticalAlignment=Alignment.CenterVertically){RadioButton(auth==SmbAuthMode.GUEST,{auth=SmbAuthMode.GUEST});Text("访客模式") ; RadioButton(auth==SmbAuthMode.PASSWORD,{auth=SmbAuthMode.PASSWORD});Text("账号密码")}
            if(auth==SmbAuthMode.PASSWORD){OutlinedTextField(user,{user=it},label={Text("用户名")},modifier=Modifier.fillMaxWidth());OutlinedTextField(pass,{pass=it},label={Text("密码")},modifier=Modifier.fillMaxWidth())}
            Button(onClick={viewModel.loadShares(config(),pass){message=it}}){Text("连接并获取共享文件夹")}
            if(shares.isNotEmpty()){Text("选择共享文件夹");shares.forEach{s->OutlinedButton(onClick={share=s;subPath="";viewModel.loadDirectories(config(),pass,""){message=it}}){Text(s)}}}
            if(share.isNotBlank()){Text("当前输出目录：/$share/${subPath}");if(subPath.isNotBlank())OutlinedButton(onClick={subPath=subPath.substringBeforeLast("/","");viewModel.loadDirectories(config(),pass,subPath){message=it}}){Text("返回上级目录")}; dirs.forEach{d->OutlinedButton(onClick={subPath=listOf(subPath,d).filter{it.isNotBlank()}.joinToString("/");viewModel.loadDirectories(config(),pass,subPath){message=it}}){Text("进入 $d")}};Button(onClick={viewModel.testSelectedShare(config(),pass){message=it}}){Text("验证当前输出目录可写")}}
        }}}
        item { Card { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("命名与同步范围",style=MaterialTheme.typography.titleMedium);OutlinedTextField(deviceName,{deviceName=it},label={Text("设备名称（可选子文件夹名）")},modifier=Modifier.fillMaxWidth());Toggle("在输出目录创建设备名称文件夹",createFolder){createFolder=it};Toggle("统一命名上传",rename){rename=it};Text("统一命名：原文件名有唯一11位手机号时，使用 手机号_文件修改时间；否则保留原文件名")
            Row{RadioButton(range==SyncRange.ALL,{range=SyncRange.ALL});Text("全部");RadioButton(range==SyncRange.RECENT_DAYS,{range=SyncRange.RECENT_DAYS});Text("最近天数");RadioButton(range==SyncRange.CUSTOM_START,{range=SyncRange.CUSTOM_START});Text("开始日期")}
            if(range==SyncRange.RECENT_DAYS)OutlinedTextField(days,{days=it},label={Text("最近 N 天")},modifier=Modifier.fillMaxWidth());if(range==SyncRange.CUSTOM_START)OutlinedTextField(startDate,{startDate=it},label={Text("开始日期 yyyy-MM-dd")},modifier=Modifier.fillMaxWidth())
            Toggle("严格复核（SHA-256）",verify==VerificationMode.SHA256){verify=if(it)VerificationMode.SHA256 else VerificationMode.SIZE_ONLY}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={viewModel.save(config(),pass){message=it}}){Text("保存")};Button(onClick={viewModel.syncNow();message="已加入同步队列"}){Text("立即同步")}}
        }}}
        item { if(message.isNotBlank()) Text(message) }
        item { Text("实时状态",style=MaterialTheme.typography.titleMedium) }
        items(events, key={it.id}){event->Text("${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.createdAt))}　${event.message}",color=if(event.level=="ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.bodySmall)}
        item { Text("最近同步日志",style=MaterialTheme.typography.titleMedium) }
        items(runs){r->Text("${r.status}｜扫描${r.scannedCount} 上传${r.uploadedCount} 失败${r.failedCount}",style=MaterialTheme.typography.bodySmall)}
    }
}
@Composable private fun Toggle(label:String,checked:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(checked,onChange)}}
