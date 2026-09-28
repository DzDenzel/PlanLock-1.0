package com.planlock.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.planlock.app.api.ApiClient
import com.planlock.app.api.planLockPreferences
import com.planlock.app.engine.executionHaptic
import com.planlock.app.engine.ExecutionEngine
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.StrategySnapshotEntity
import com.planlock.app.security.BackupPasswordStore
import com.planlock.design.ExecutionStatusCard
import com.planlock.design.PlanCard
import com.planlock.design.PlanLockColors
import com.planlock.design.PlanVisualState
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import kotlinx.coroutines.launch
import java.util.Calendar
import com.planlock.app.record.PlanOccurrence

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDetailScreen(planId: Long, onEdit: () -> Unit = {}, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val actionGate = remember { PlanActionGate(store, api, BackupPasswordStore(context.planLockPreferences)) }
    val scope = rememberCoroutineScope()
    val engineVersion by ExecutionEngine.stateVersion.collectAsState()
    var plan by remember { mutableStateOf<PlanSnapshotEntity?>(null) }
    var strategy by remember { mutableStateOf<StrategySnapshotEntity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showDelete by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingPlanAction?>(null) }

    PlanActionDialogHost(actionGate, pendingAction, onDismiss = { pendingAction = null })

    suspend fun refresh() {
        plan = store.plan(planId)
        strategy = store.strategy(planId)
        loading = false
    }

    fun requestAction(value: PlanSnapshotEntity, action: ProtectedPlanAction, after: () -> Unit = {}) {
        scope.launch {
            requestOrRunPlanAction(
                actionGate, value, action,
                onPrompt = { pendingAction = it },
                onCompleted = {
                    executionHaptic(context)
                    after()
                    scope.launch { refresh() }
                },
            )
        }
    }

    LaunchedEffect(planId) { refresh() }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("确认删除计划？") },
            text = { Text("删除后无法恢复，执行记录仍会保留。") },
            confirmButton = {
                Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), onClick = {
                    showDelete = false
                    plan?.let { requestAction(it, ProtectedPlanAction.DELETE, onBack) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
        TopAppBar(
            title = { Text("计划详情") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = { IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "编辑") } },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
    }) { padding ->
        when {
            loading -> UIStatePanel(UiState.LOADING, modifier = Modifier.padding(padding))
            plan == null -> UIStatePanel(UiState.ERROR, "计划不存在或已删除", onBack, Modifier.padding(padding))
            else -> {
                val value = plan!!
                val clock = Calendar.getInstance()
                val now = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
                val active = PlanTime.isActive(value, now, PlanOccurrence.isoDayOfWeek(clock.get(Calendar.DAY_OF_WEEK)))
                LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        if (active) {
                            val remaining = PlanTime.remainingMinutes(value, now)
                            ExecutionStatusCard(
                                planName = value.name,
                                remainingText = "剩余 %02d:%02d".format(remaining / 60, remaining % 60),
                                endsAtText = "${PlanTime.format(value.endTime)} 结束 · 已完成 ${(PlanTime.progress(value, now) * 100).toInt()}%",
                                progress = PlanTime.progress(value, now),
                                paused = value.status != 1,
                                onTogglePause = { requestAction(value, ProtectedPlanAction.PAUSE) },
                                sharedKey = "plan-${value.id}"
                            )
                        } else {
                            PlanCard(value.toCardData(if (value.status == 1) PlanVisualState.UPCOMING else PlanVisualState.PAUSED), onClick = onEdit, sharedKey = "plan-${value.id}")
                        }
                    }
                    item { DetailSection("基本信息", listOf("类型" to value.type, "重复" to repeatLabel(value.repeatRule), "提醒" to "提前 ${value.remindAdvanceMin ?: 15} 分钟", "跨天" to if ((PlanTime.minutesOfDay(value.endTime) ?: 0) <= (PlanTime.minutesOfDay(value.startTime) ?: 0)) "是" else "否")) }
                    item { StrategySection(strategy, engineVersion) }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onClick = { requestAction(value, ProtectedPlanAction.PAUSE) }, modifier = Modifier.weight(1f)) { Text(if (value.status == 1) "暂停" else "恢复") }
                            FilledTonalButton(onClick = { showDelete = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Delete, null); Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategySection(strategy: StrategySnapshotEntity?, engineVersion: Long) {
    val blacklist = parseAppReferences(strategy?.blacklistApps)
    val whitelist = parseAppReferences(strategy?.whitelistApps)
    val strictMode = strategy?.strictMode == true
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("执行策略", style = MaterialTheme.typography.titleMedium)
            Surface(shape = MaterialTheme.shapes.small, color = if (strictMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                Text(if (strictMode) "严格模式 · 已开启" else "严格模式 · 已关闭", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
            if (strictMode) {
                Text("系统必要功能自动放行，基础工具按设置放行", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("临时使用未放行应用请暂停计划", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!strictMode && blacklist.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                blacklist.forEach { app ->
                    val locked = remember(engineVersion, app.id) {
                        ExecutionEngine.inspect(app.id)?.shouldBlock == true
                    }
                    LockedAppChip(app, locked)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("免打扰", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (strategy?.autoDnd == true) "已开启" else "未开启")
            }
            Row(Modifier.fillMaxWidth()) {
                Text("通知静默", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (strategy?.notifySilence == true) "已开启" else "未开启")
            }
            if (whitelist.isNotEmpty()) Text("放行：${whitelist.joinToString("、") { it.name }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LockedAppChip(app: AppReference, locked: Boolean) {
    AssistChip(
        onClick = {},
        modifier = Modifier.alpha(if (locked) .48f else 1f),
        label = { Text(app.name) },
        leadingIcon = if (locked) { { Icon(Icons.Filled.Lock, "已锁定") } } else null,
    )
}

@Composable
private fun DetailSection(title: String, rows: List<Pair<String, String>>) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, Modifier.weight(2f))
                }
            }
        }
    }
}

internal data class AppReference(val id: String, val name: String)

internal fun parseAppReferences(raw: String?): List<AppReference> {
    if (raw.isNullOrBlank()) return emptyList()
    // Android ICU requires the closing brace to be escaped as well.
    val jsonEntries = Regex("\\{[^}]*\\}").findAll(raw).mapNotNull { entry ->
        val name = Regex("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(entry.value)?.groupValues?.get(1)
        val packageName = Regex("\\\"packageName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(entry.value)?.groupValues?.get(1)
        name?.let { AppReference(packageName ?: it, it) }
    }.toList()
    if (jsonEntries.isNotEmpty()) return jsonEntries
    return raw.trim('[', ']').split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.map { AppReference(it, it) }
}

internal fun parseAppNames(raw: String?): List<String> = parseAppReferences(raw).map { it.name }
