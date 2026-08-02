package com.fde.audiosmbsync.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fde.audiosmbsync.data.AppConfig
import com.fde.audiosmbsync.data.AppEvent
import com.fde.audiosmbsync.data.SmbAuthMode
import com.fde.audiosmbsync.data.SyncRange
import com.fde.audiosmbsync.data.SyncRun
import com.fde.audiosmbsync.data.SyncScheduleMode
import com.fde.audiosmbsync.data.VerificationMode
import com.fde.audiosmbsync.viewmodel.SyncViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

private enum class WizardStep(val title: String, val subtitle: String) {
    FOLDER("录音目录", "选择手机中存放录音的文件夹"),
    SCHEDULE("同步计划", "决定是否自动同步以及同步频率"),
    SMB("服务器目录", "连接 SMB 并验证目标目录可写"),
    RULES("上传规则", "确认命名、范围与文件复核方式"),
    READY("开始使用", "查看状态并手动发起同步")
}

private data class ConfirmedInfo(
    val title: String,
    val value: String
)

@Composable
fun SyncApp(viewModel: SyncViewModel) {
    val stored by viewModel.config.collectAsStateWithLifecycle()
    val hosts by viewModel.discoveredHosts.collectAsStateWithLifecycle()
    val shares by viewModel.availableShares.collectAsStateWithLifecycle()
    val dirs by viewModel.availableDirectories.collectAsStateWithLifecycle()
    val candidates by viewModel.folderCandidates.collectAsStateWithLifecycle()
    val runs by viewModel.syncRuns.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val scanning by viewModel.isScanningNetwork.collectAsStateWithLifecycle()

    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    var folderVerified by rememberSaveable { mutableStateOf(false) }
    var scheduleVerified by rememberSaveable { mutableStateOf(false) }
    var smbVerified by rememberSaveable { mutableStateOf(false) }
    var rulesSaved by rememberSaveable { mutableStateOf(false) }
    var busyAction by rememberSaveable { mutableStateOf<String?>(null) }
    var feedback by rememberSaveable { mutableStateOf("") }
    var feedbackError by rememberSaveable { mutableStateOf(false) }

    var deviceName by remember(stored) { mutableStateOf(stored?.deviceName.orEmpty()) }
    var host by remember(stored) { mutableStateOf(stored?.smbHost.orEmpty()) }
    var share by remember(stored) { mutableStateOf(stored?.shareName.orEmpty()) }
    var subPath by remember(stored) { mutableStateOf(stored?.smbSubPath.orEmpty()) }
    var auth by remember(stored) { mutableStateOf(stored?.authMode ?: SmbAuthMode.GUEST) }
    var user by remember(stored) { mutableStateOf(stored?.username.orEmpty()) }
    var pass by rememberSaveable { mutableStateOf("") }
    var recordingUri by remember(stored) { mutableStateOf(stored?.recordingTreeUri.orEmpty()) }
    var recordingRelativePath by remember(stored) { mutableStateOf(stored?.recordingRelativePath.orEmpty()) }
    var createFolder by remember(stored) { mutableStateOf(stored?.createDeviceSubfolder ?: false) }
    var rename by remember(stored) { mutableStateOf(stored?.renameOnUpload ?: true) }
    var verify by remember(stored) { mutableStateOf(stored?.verificationMode ?: VerificationMode.SIZE_ONLY) }
    var range by remember(stored) { mutableStateOf(stored?.syncRange ?: SyncRange.ALL) }
    var days by remember(stored) { mutableStateOf((stored?.recentDays ?: 7).toString()) }
    var startDate by remember(stored) {
        mutableStateOf(stored?.customStartAt?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        }.orEmpty())
    }
    var autoSync by remember(stored) { mutableStateOf(stored?.autoSyncEnabled ?: true) }
    var schedule by remember(stored) { mutableStateOf(stored?.syncScheduleMode ?: SyncScheduleMode.DAILY) }
    var hour by remember(stored) { mutableStateOf((stored?.dailySyncHour ?: 2).toString()) }
    var minute by remember(stored) { mutableStateOf((stored?.dailySyncMinute ?: 0).toString()) }
    var interval by remember(stored) { mutableStateOf((stored?.intervalMinutes ?: 1440).toString()) }
    var delayMinutes by rememberSaveable { mutableStateOf("30") }

    fun showFeedback(text: String, isError: Boolean = false) {
        feedback = text
        feedbackError = isError
    }

    fun config() = AppConfig(
        deviceName = deviceName.trim(),
        recordingTreeUri = recordingUri,
        recordingRelativePath = recordingRelativePath,
        smbHost = host.trim(),
        shareName = share,
        smbSubPath = subPath.trim('/'),
        authMode = auth,
        username = user.trim(),
        createDeviceSubfolder = createFolder,
        renameOnUpload = rename,
        verificationMode = verify,
        syncRange = range,
        recentDays = days.toIntOrNull() ?: 7,
        customStartAt = runCatching {
            LocalDate.parse(startDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull(),
        autoSyncEnabled = autoSync,
        syncScheduleMode = schedule,
        dailySyncHour = hour.toIntOrNull() ?: 2,
        dailySyncMinute = minute.toIntOrNull() ?: 0,
        intervalMinutes = interval.toLongOrNull() ?: 1440,
        lastSyncAt = stored?.lastSyncAt
    )

    val manualPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            recordingUri = it.toString()
            recordingRelativePath = ""
            folderVerified = false
            rulesSaved = false
            busyAction = "folder"
            viewModel.selectManualRecordingFolder(it) { permissionResult ->
                if (permissionResult.startsWith("已获得")) {
                    viewModel.validateRecordingFolder(recordingUri, recordingRelativePath) { result ->
                        busyAction = null
                        folderVerified = result.startsWith("录音文件夹可用")
                        showFeedback(result, !folderVerified)
                    }
                } else {
                    busyAction = null
                    showFeedback(permissionResult, true)
                }
            }
        }
    }
    val scanPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            busyAction = "folder-scan"
            viewModel.scanRecordingFolders(it) { result ->
                busyAction = null
                showFeedback(result, result.startsWith("扫描失败"))
            }
        }
    }

    val highestUnlocked = when {
        rulesSaved && folderVerified && scheduleVerified && smbVerified -> 4
        folderVerified && scheduleVerified && smbVerified -> 3
        folderVerified && scheduleVerified -> 2
        folderVerified -> 1
        else -> 0
    }

    val confirmedInformation = buildList {
        if (folderVerified) {
            add(
                ConfirmedInfo(
                    title = "录音目录",
                    value = displayRecordingPath(recordingUri, recordingRelativePath)
                )
            )
        }
        if (scheduleVerified) {
            val scheduleDescription = when {
                !autoSync -> "仅手动同步"
                schedule == SyncScheduleMode.DAILY ->
                    "每天 ${hour.toIntOrNull()?.toString()?.padStart(2, '0')}:${minute.toIntOrNull()?.toString()?.padStart(2, '0')} 自动同步"
                else -> "每隔 ${interval.toLongOrNull() ?: interval} 分钟自动同步"
            }
            add(ConfirmedInfo(title = "同步计划", value = scheduleDescription))
        }
        if (smbVerified) {
            val targetPath = listOf(share.trim('/'), subPath.trim('/'))
                .filter { it.isNotBlank() }
                .joinToString("/")
            val loginMode = if (auth == SmbAuthMode.GUEST) "访客模式" else "账号密码登录"
            add(
                ConfirmedInfo(
                    title = "SMB 输出目录",
                    value = "smb://${host.trim()}/$targetPath/ · $loginMode"
                )
            )
        }
        if (rulesSaved) {
            val rangeDescription = when (range) {
                SyncRange.ALL -> "全部文件"
                SyncRange.RECENT_DAYS -> "最近 ${days.toIntOrNull() ?: days} 天"
                SyncRange.CUSTOM_START -> "$startDate 起"
            }
            val ruleParts = buildList {
                add(if (rename) "统一命名" else "保留原文件名")
                add(rangeDescription)
                add(if (verify == VerificationMode.SHA256) "SHA-256 复核" else "文件大小复核")
                if (createFolder) add("设备目录：${deviceName.trim()}")
            }
            add(ConfirmedInfo(title = "上传规则", value = ruleParts.joinToString(" · ")))
        }
    }

    LaunchedEffect(currentStep) {
        feedback = ""
        feedbackError = false
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "录音同步设置",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "按顺序完成设置，验证通过后才能进入下一步。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                WizardProgress(
                    currentStep = currentStep,
                    highestUnlocked = highestUnlocked,
                    onStepClick = { step ->
                        if (step <= highestUnlocked) currentStep = step
                    }
                )
            }

            if (confirmedInformation.isNotEmpty()) {
                item {
                    ConfirmedInformation(confirmedInformation)
                }
            }

            item {
                AnimatedContent(targetState = currentStep, label = "wizard-step") { step ->
                    when (WizardStep.entries[step]) {
                        WizardStep.FOLDER -> FolderStep(
                            recordingUri = recordingUri,
                            relativePath = recordingRelativePath,
                            candidates = candidates,
                            busy = busyAction,
                            onManualPick = { manualPicker.launch(null) },
                            onScanPick = { scanPicker.launch(null) },
                            onCandidate = { candidate ->
                                recordingUri = candidate.treeUri
                                recordingRelativePath = candidate.relativePath
                                folderVerified = false
                                rulesSaved = false
                                busyAction = "folder"
                                viewModel.validateRecordingFolder(recordingUri, recordingRelativePath) { result ->
                                    busyAction = null
                                    folderVerified = result.startsWith("录音文件夹可用")
                                    showFeedback(result, !folderVerified)
                                    if (folderVerified) viewModel.recordEvent("已选择录音文件夹：${candidate.name}")
                                }
                            },
                            onContinue = {
                                if (recordingUri.isBlank()) {
                                    showFeedback("请先选择一个录音文件夹。", true)
                                } else {
                                    busyAction = "folder"
                                    viewModel.validateRecordingFolder(recordingUri, recordingRelativePath) { result ->
                                        busyAction = null
                                        folderVerified = result.startsWith("录音文件夹可用")
                                        showFeedback(result, !folderVerified)
                                        if (folderVerified) currentStep = 1
                                    }
                                }
                            }
                        )

                        WizardStep.SCHEDULE -> ScheduleStep(
                            autoSync = autoSync,
                            schedule = schedule,
                            hour = hour,
                            minute = minute,
                            interval = interval,
                            onAutoSyncChange = { autoSync = it; scheduleVerified = false; rulesSaved = false },
                            onScheduleChange = { schedule = it; scheduleVerified = false; rulesSaved = false },
                            onHourChange = { hour = it; scheduleVerified = false; rulesSaved = false },
                            onMinuteChange = { minute = it; scheduleVerified = false; rulesSaved = false },
                            onIntervalChange = { interval = it; scheduleVerified = false; rulesSaved = false },
                            onBack = { currentStep = 0 },
                            onContinue = {
                                val error = when {
                                    !autoSync -> null
                                    schedule == SyncScheduleMode.DAILY && hour.toIntOrNull() !in 0..23 ->
                                        "小时必须是 0–23。"
                                    schedule == SyncScheduleMode.DAILY && minute.toIntOrNull() !in 0..59 ->
                                        "分钟必须是 0–59。"
                                    schedule == SyncScheduleMode.INTERVAL && (interval.toLongOrNull() ?: 0) < 15 ->
                                        "自动同步间隔不能少于 15 分钟。"
                                    else -> null
                                }
                                if (error != null) {
                                    scheduleVerified = false
                                    showFeedback(error, true)
                                } else {
                                    scheduleVerified = true
                                    showFeedback(if (autoSync) "同步计划已确认。" else "已关闭自动同步，可随时手动同步。")
                                    currentStep = 2
                                }
                            }
                        )

                        WizardStep.SMB -> SmbStep(
                            host = host,
                            auth = auth,
                            username = user,
                            password = pass,
                            scanning = scanning,
                            busy = busyAction,
                            hosts = hosts,
                            shares = shares,
                            directories = dirs,
                            share = share,
                            subPath = subPath,
                            onHostChange = { host = it; share = ""; subPath = ""; smbVerified = false; rulesSaved = false },
                            onAuthChange = { auth = it; smbVerified = false; rulesSaved = false },
                            onUsernameChange = { user = it; smbVerified = false; rulesSaved = false },
                            onPasswordChange = { pass = it; smbVerified = false; rulesSaved = false },
                            onScanNetwork = {
                                showFeedback("正在扫描当前局域网的 SMB 服务…")
                                viewModel.scanLocalNetwork { result -> showFeedback(result, result.startsWith("扫描失败")) }
                            },
                            onHostSelect = { selected ->
                                host = selected
                                share = ""
                                subPath = ""
                                smbVerified = false
                                rulesSaved = false
                            },
                            onConnect = {
                                busyAction = "smb-connect"
                                viewModel.loadShares(config(), pass) { result ->
                                    busyAction = null
                                    showFeedback(result, result.startsWith("连接失败"))
                                }
                            },
                            onShareSelect = { selected ->
                                share = selected
                                subPath = ""
                                smbVerified = false
                                rulesSaved = false
                                busyAction = "directories"
                                viewModel.loadDirectories(config(), pass, "") { result ->
                                    busyAction = null
                                    showFeedback(result, result.startsWith("读取目录失败"))
                                }
                            },
                            onDirectoryUp = {
                                subPath = subPath.substringBeforeLast("/", "")
                                smbVerified = false
                                rulesSaved = false
                                busyAction = "directories"
                                viewModel.loadDirectories(config(), pass, subPath) { result ->
                                    busyAction = null
                                    showFeedback(result, result.startsWith("读取目录失败"))
                                }
                            },
                            onDirectoryEnter = { directory ->
                                subPath = listOf(subPath, directory).filter { it.isNotBlank() }.joinToString("/")
                                smbVerified = false
                                rulesSaved = false
                                busyAction = "directories"
                                viewModel.loadDirectories(config(), pass, subPath) { result ->
                                    busyAction = null
                                    showFeedback(result, result.startsWith("读取目录失败"))
                                }
                            },
                            onBack = { currentStep = 1 },
                            onContinue = {
                                if (host.isBlank()) {
                                    showFeedback("请填写或选择 SMB 服务器 IP。", true)
                                } else if (share.isBlank()) {
                                    showFeedback("请先连接服务器并选择共享文件夹。", true)
                                } else {
                                    busyAction = "smb-test"
                                    viewModel.testSelectedShare(config(), pass) { result ->
                                        busyAction = null
                                        smbVerified = result.startsWith("目标共享文件夹可写入")
                                        showFeedback(result, !smbVerified)
                                        if (smbVerified) currentStep = 3
                                    }
                                }
                            }
                        )

                        WizardStep.RULES -> RulesStep(
                            deviceName = deviceName,
                            createFolder = createFolder,
                            rename = rename,
                            range = range,
                            days = days,
                            startDate = startDate,
                            verify = verify,
                            busy = busyAction,
                            onDeviceNameChange = { deviceName = it; rulesSaved = false },
                            onCreateFolderChange = { createFolder = it; rulesSaved = false },
                            onRenameChange = { rename = it; rulesSaved = false },
                            onRangeChange = { range = it; rulesSaved = false },
                            onDaysChange = { days = it; rulesSaved = false },
                            onStartDateChange = { startDate = it; rulesSaved = false },
                            onVerifyChange = { verify = it; rulesSaved = false },
                            onBack = { currentStep = 2 },
                            onContinue = {
                                val error = when {
                                    createFolder && deviceName.isBlank() -> "开启设备文件夹后，必须填写设备名称。"
                                    range == SyncRange.RECENT_DAYS && (days.toIntOrNull() ?: 0) < 1 ->
                                        "最近天数必须大于 0。"
                                    range == SyncRange.CUSTOM_START && runCatching { LocalDate.parse(startDate) }.isFailure ->
                                        "开始日期格式应为 yyyy-MM-dd，例如 2026-07-30。"
                                    else -> null
                                }
                                if (error != null) {
                                    showFeedback(error, true)
                                } else {
                                    busyAction = "save"
                                    viewModel.save(config(), pass) { result ->
                                        busyAction = null
                                        rulesSaved = result == "配置已保存"
                                        showFeedback(result, !rulesSaved)
                                        if (rulesSaved) currentStep = 4
                                    }
                                }
                            }
                        )

                        WizardStep.READY -> ReadyStep(
                            config = config(),
                            delayMinutes = delayMinutes,
                            events = events,
                            runs = runs,
                            onDelayChange = { delayMinutes = it },
                            onSyncNow = {
                                viewModel.syncNow()
                                showFeedback("同步任务已加入队列，状态会在下方实时更新。")
                            },
                            onSyncLater = {
                                val minutes = delayMinutes.toLongOrNull()
                                if (minutes == null || minutes < 1) {
                                    showFeedback("请输入大于 0 的延后分钟数。", true)
                                } else {
                                    viewModel.syncAfter(minutes)
                                    showFeedback("已安排在 $minutes 分钟后同步。")
                                }
                            },
                            onEdit = { currentStep = 0 }
                        )
                    }
                }
            }

            if (feedback.isNotBlank()) {
                item { InlineFeedback(feedback, feedbackError) }
            }
        }
    }
}

@Composable
private fun ConfirmedInformation(items: List<ConfirmedInfo>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "已确认信息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = item.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun WizardProgress(currentStep: Int, highestUnlocked: Int, onStepClick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            WizardStep.entries.forEachIndexed { index, step ->
                val isCurrent = index == currentStep
                val isComplete = index < highestUnlocked || (index == 4 && highestUnlocked == 4)
                val isUnlocked = index <= highestUnlocked
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = isUnlocked) { onStepClick(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                color = when {
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    isComplete -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isComplete && !isCurrent) "✓" else "${index + 1}",
                            color = when {
                                isCurrent -> MaterialTheme.colorScheme.onPrimary
                                isComplete -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        Text(
            text = "第 ${currentStep + 1} 步，共 ${WizardStep.entries.size} 步",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun displayRecordingPath(treeUri: String, relativePath: String): String {
    val documentId = runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
    }.getOrNull()
    val basePath = when {
        documentId.isNullOrBlank() -> "已授权录音目录"
        documentId.startsWith("primary:") -> {
            val path = Uri.decode(documentId.removePrefix("primary:")).trim('/')
            if (path.isBlank()) "/内部存储" else "/内部存储/$path"
        }
        ':' in documentId -> {
            val volume = documentId.substringBefore(':')
            val path = Uri.decode(documentId.substringAfter(':')).trim('/')
            if (path.isBlank()) "/$volume" else "/$volume/$path"
        }
        else -> Uri.decode(documentId)
    }
    val childPath = relativePath.trim('/')
    return if (childPath.isBlank()) basePath else "${basePath.trimEnd('/')}/$childPath"
}

@Composable
private fun StepFrame(
    step: WizardStep,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(step.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun FolderStep(
    recordingUri: String,
    relativePath: String,
    candidates: List<com.fde.audiosmbsync.storage.FolderCandidate>,
    busy: String?,
    onManualPick: () -> Unit,
    onScanPick: () -> Unit,
    onCandidate: (com.fde.audiosmbsync.storage.FolderCandidate) -> Unit,
    onContinue: () -> Unit
) {
    StepFrame(WizardStep.FOLDER) {
        if (recordingUri.isNotBlank()) {
            StatusSummary(
                title = "已选择录音目录",
                detail = relativePath.ifBlank { "系统授权目录" },
                positive = true
            )
        } else {
            StatusSummary("尚未选择目录", "先选择直接存放录音文件的文件夹。", false)
        }
        Button(onClick = onManualPick, modifier = Modifier.fillMaxWidth(), enabled = busy == null) {
            Text(if (recordingUri.isBlank()) "选择录音文件夹" else "重新选择录音文件夹")
        }
        OutlinedButton(onClick = onScanPick, modifier = Modifier.fillMaxWidth(), enabled = busy == null) {
            if (busy == "folder-scan") {
                SmallProgress()
                Text("正在扫描…")
            } else {
                Text("从父目录扫描候选文件夹")
            }
        }
        candidates.forEach { candidate ->
            OutlinedButton(
                onClick = { onCandidate(candidate) },
                modifier = Modifier.fillMaxWidth(),
                enabled = busy == null
            ) {
                Text("使用 ${candidate.name}（${candidate.fileCount} 个文件）")
            }
        }
        PrimaryAction(
            text = "验证目录并继续",
            loadingText = "正在验证目录…",
            loading = busy == "folder",
            onClick = onContinue
        )
    }
}

@Composable
private fun ScheduleStep(
    autoSync: Boolean,
    schedule: SyncScheduleMode,
    hour: String,
    minute: String,
    interval: String,
    onAutoSyncChange: (Boolean) -> Unit,
    onScheduleChange: (SyncScheduleMode) -> Unit,
    onHourChange: (String) -> Unit,
    onMinuteChange: (String) -> Unit,
    onIntervalChange: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    StepFrame(WizardStep.SCHEDULE) {
        ToggleRow("开启自动同步", autoSync, onAutoSyncChange)
        if (autoSync) {
            ChoiceRow(
                options = listOf(
                    "每天固定时间" to (schedule == SyncScheduleMode.DAILY),
                    "按间隔同步" to (schedule == SyncScheduleMode.INTERVAL)
                ),
                onChoose = { index ->
                    onScheduleChange(if (index == 0) SyncScheduleMode.DAILY else SyncScheduleMode.INTERVAL)
                }
            )
            if (schedule == SyncScheduleMode.DAILY) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = hour,
                        onValueChange = onHourChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("小时（0–23）") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = minute,
                        onValueChange = onMinuteChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("分钟（0–59）") },
                        singleLine = true
                    )
                }
            } else {
                OutlinedTextField(
                    value = interval,
                    onValueChange = onIntervalChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("间隔分钟") },
                    supportingText = { Text("Android 后台任务最短间隔为 15 分钟。") },
                    singleLine = true
                )
            }
        } else {
            StatusSummary("仅手动同步", "设置完成后，可在首页随时点击“立即同步”。", true)
        }
        NavigationActions(onBack = onBack, continueText = "确认同步计划", onContinue = onContinue)
    }
}

@Composable
private fun SmbStep(
    host: String,
    auth: SmbAuthMode,
    username: String,
    password: String,
    scanning: Boolean,
    busy: String?,
    hosts: List<String>,
    shares: List<String>,
    directories: List<String>,
    share: String,
    subPath: String,
    onHostChange: (String) -> Unit,
    onAuthChange: (SmbAuthMode) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onScanNetwork: () -> Unit,
    onHostSelect: (String) -> Unit,
    onConnect: () -> Unit,
    onShareSelect: (String) -> Unit,
    onDirectoryUp: () -> Unit,
    onDirectoryEnter: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    StepFrame(WizardStep.SMB) {
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器 IP 或主机名") },
            supportingText = { Text("示例：192.168.1.85") },
            singleLine = true
        )
        OutlinedButton(onClick = onScanNetwork, modifier = Modifier.fillMaxWidth(), enabled = !scanning && busy == null) {
            if (scanning) {
                SmallProgress()
                Text("正在扫描局域网…")
            } else {
                Text("扫描同网段 SMB 服务")
            }
        }
        hosts.forEach { ip ->
            OutlinedButton(onClick = { onHostSelect(ip) }, modifier = Modifier.fillMaxWidth()) {
                Text("使用服务器 $ip")
            }
        }
        Text("登录方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        ChoiceRow(
            options = listOf(
                "访客模式" to (auth == SmbAuthMode.GUEST),
                "账号密码" to (auth == SmbAuthMode.PASSWORD)
            ),
            onChoose = { index -> onAuthChange(if (index == 0) SmbAuthMode.GUEST else SmbAuthMode.PASSWORD) }
        )
        if (auth == SmbAuthMode.PASSWORD) {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("SMB 用户名") },
                singleLine = true
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("SMB 密码") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        PrimaryAction(
            text = "连接并获取共享文件夹",
            loadingText = "正在连接服务器…",
            loading = busy == "smb-connect",
            onClick = onConnect
        )
        if (shares.isNotEmpty()) {
            Text("选择共享文件夹", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            shares.forEach { item ->
                SelectButton(
                    text = item,
                    selected = item == share,
                    onClick = { onShareSelect(item) }
                )
            }
        }
        if (share.isNotBlank()) {
            StatusSummary(
                title = "当前输出目录",
                detail = "/$share/${subPath}".trimEnd('/'),
                positive = true
            )
            if (subPath.isNotBlank()) {
                OutlinedButton(onClick = onDirectoryUp, modifier = Modifier.fillMaxWidth(), enabled = busy == null) {
                    Text("返回上级目录")
                }
            }
            directories.forEach { directory ->
                OutlinedButton(
                    onClick = { onDirectoryEnter(directory) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = busy == null
                ) {
                    Text("进入文件夹：$directory")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f), enabled = busy == null) { Text("上一步") }
            Button(
                onClick = onContinue,
                modifier = Modifier.weight(2f),
                enabled = busy == null
            ) {
                if (busy == "smb-test") {
                    SmallProgress()
                    Text("正在验证写入…")
                } else {
                    Text("验证目录并继续")
                }
            }
        }
    }
}

@Composable
private fun RulesStep(
    deviceName: String,
    createFolder: Boolean,
    rename: Boolean,
    range: SyncRange,
    days: String,
    startDate: String,
    verify: VerificationMode,
    busy: String?,
    onDeviceNameChange: (String) -> Unit,
    onCreateFolderChange: (Boolean) -> Unit,
    onRenameChange: (Boolean) -> Unit,
    onRangeChange: (SyncRange) -> Unit,
    onDaysChange: (String) -> Unit,
    onStartDateChange: (String) -> Unit,
    onVerifyChange: (VerificationMode) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    StepFrame(WizardStep.RULES) {
        ToggleRow("在输出目录创建设备文件夹", createFolder, onCreateFolderChange)
        if (createFolder) {
            OutlinedTextField(
                value = deviceName,
                onValueChange = onDeviceNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("设备名称") },
                supportingText = { Text("该名称会作为服务器上的子文件夹名。") },
                singleLine = true
            )
        }
        ToggleRow("统一命名上传", rename, onRenameChange)
        Text(
            text = "文件名中有 11 位手机号时，使用“手机号_文件修改时间”；否则保留原文件名。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("同步文件范围", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RangeChoice("全部文件", range == SyncRange.ALL) { onRangeChange(SyncRange.ALL) }
            RangeChoice("最近若干天", range == SyncRange.RECENT_DAYS) { onRangeChange(SyncRange.RECENT_DAYS) }
            RangeChoice("指定日期之后", range == SyncRange.CUSTOM_START) { onRangeChange(SyncRange.CUSTOM_START) }
        }
        if (range == SyncRange.RECENT_DAYS) {
            OutlinedTextField(
                value = days,
                onValueChange = onDaysChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("最近天数") },
                singleLine = true
            )
        }
        if (range == SyncRange.CUSTOM_START) {
            OutlinedTextField(
                value = startDate,
                onValueChange = onStartDateChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("开始日期") },
                supportingText = { Text("格式：yyyy-MM-dd") },
                singleLine = true
            )
        }
        ToggleRow(
            "严格复核（SHA-256）",
            verify == VerificationMode.SHA256
        ) { enabled ->
            onVerifyChange(if (enabled) VerificationMode.SHA256 else VerificationMode.SIZE_ONLY)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f), enabled = busy == null) { Text("上一步") }
            Button(onClick = onContinue, modifier = Modifier.weight(2f), enabled = busy == null) {
                if (busy == "save") {
                    SmallProgress()
                    Text("正在保存…")
                } else {
                    Text("保存配置并完成")
                }
            }
        }
    }
}

@Composable
private fun ReadyStep(
    config: AppConfig,
    delayMinutes: String,
    events: List<AppEvent>,
    runs: List<SyncRun>,
    onDelayChange: (String) -> Unit,
    onSyncNow: () -> Unit,
    onSyncLater: () -> Unit,
    onEdit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepFrame(WizardStep.READY) {
            StatusSummary(
                title = "设置完成，可以开始同步",
                detail = "目标：${config.smbHost}/${config.shareName}/${config.smbSubPath}".trimEnd('/'),
                positive = true
            )
            Button(
                onClick = onSyncNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text("立即同步")
            }
            OutlinedTextField(
                value = delayMinutes,
                onValueChange = onDelayChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("延后同步分钟") },
                singleLine = true
            )
            OutlinedButton(onClick = onSyncLater, modifier = Modifier.fillMaxWidth()) {
                Text("安排延后同步")
            }
            TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Text("重新检查或修改配置")
            }
        }
        EventPanel(events)
        RunPanel(runs)
    }
}

@Composable
private fun EventPanel(events: List<AppEvent>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("实时状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (events.isEmpty()) {
                Text("还没有同步事件。点击“立即同步”后，进度会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                events.take(8).forEachIndexed { index, event ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.createdAt))}　${event.message}",
                        color = if (event.level == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun RunPanel(runs: List<SyncRun>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("最近同步记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (runs.isEmpty()) {
                Text("完成第一次同步后，这里会显示结果。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                runs.take(5).forEachIndexed { index, run ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "${run.status}｜扫描 ${run.scannedCount}｜上传 ${run.uploadedCount}｜失败 ${run.failedCount}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusSummary(title: String, detail: String, positive: Boolean) {
    val container = if (positive) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (positive) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(14.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InlineFeedback(message: String, isError: Boolean) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(14.dp)) {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PrimaryAction(
    text: String,
    loadingText: String,
    loading: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = !loading
    ) {
        if (loading) {
            SmallProgress()
            Text(loadingText)
        } else {
            Text(text)
        }
    }
}

@Composable
private fun NavigationActions(onBack: () -> Unit, continueText: String, onContinue: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("上一步") }
        Button(onClick = onContinue, modifier = Modifier.weight(2f)) { Text(continueText) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<String, Boolean>>, onChoose: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { index, option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChoose(index) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = option.second, onClick = { onChoose(index) })
                Text(option.first)
            }
        }
    }
}

@Composable
private fun RangeChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text)
    }
}

@Composable
private fun SelectButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("已选择：$text") }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
    }
}

@Composable
private fun SmallProgress() {
    CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp,
        color = LocalContentColor.current
    )
    Spacer(Modifier.width(8.dp))
}
