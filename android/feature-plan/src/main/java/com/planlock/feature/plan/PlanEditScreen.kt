package com.planlock.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.planlock.app.api.ApiClient
import com.planlock.app.api.planLockPreferences
import com.planlock.app.api.dto.AppItem
import com.planlock.app.api.dto.PlanUpsertRequest
import com.planlock.app.api.dto.RepeatRule
import com.planlock.app.api.dto.StrategyUpsertRequest
import com.planlock.app.engine.AppPackageResolver
import com.planlock.app.engine.ExecutionEngine
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.StrategySnapshotEntity
import com.planlock.app.security.BackupPasswordStore
import com.planlock.design.AppOption
import com.planlock.design.AppPickerSheet
import com.planlock.design.PlanTimePicker
import com.planlock.design.PlanTypeSegmented
import com.planlock.design.ReminderStepper
import com.planlock.design.RepeatPreset
import com.planlock.design.RepeatRuleSelector
import com.planlock.design.SettingSwitch
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AppListKind { BLACK, WHITE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditScreen(planId: Long?, onDone: () -> Unit = {}, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val actionGate = remember { PlanActionGate(store, api, BackupPasswordStore(context.planLockPreferences)) }
    val resolver = remember { AppPackageResolver(context) }
    val scope = rememberCoroutineScope()
    val engineVersion by ExecutionEngine.stateVersion.collectAsState()
    var installed by remember { mutableStateOf<List<AppOption>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(true) }

    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("学习") }
    var startTime by remember { mutableStateOf("21:00") }
    var endTime by remember { mutableStateOf("23:00") }
    var reminder by remember { mutableIntStateOf(15) }
    var preset by remember { mutableStateOf(RepeatPreset.WEEKDAY) }
    var weekdays by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var autoDnd by remember { mutableStateOf(true) }
    var notifySilence by remember { mutableStateOf(true) }
    var strictMode by remember { mutableStateOf(false) }
    var blackIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var whiteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allApps by remember { mutableStateOf<List<AppOption>>(emptyList()) }
    var picker by remember { mutableStateOf<AppListKind?>(null) }
    var timePicker by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<PendingPlanAction?>(null) }
    val lockedIds = remember(allApps, engineVersion) {
        allApps.asSequence()
            .filter { ExecutionEngine.inspect(it.id)?.shouldBlock == true }
            .map { it.id }
            .toSet()
    }

    PlanActionDialogHost(actionGate, pendingAction, onDismiss = { pendingAction = null })

    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) {
            resolver.installedApps().map { app -> AppOption(app.packageName, app.name, app.category, app.icon?.toBitmap(64, 64)?.asImageBitmap()) }
        }
        val extras = (blackIds + whiteIds).filter { saved -> installed.none { it.name == saved || it.id == saved } }
            .map { saved -> allApps.firstOrNull { it.id == saved } ?: AppOption(saved, saved, "工具") }
        allApps = installed + extras
        blackIds = resolveSavedIds(blackIds.toList(), allApps)
        whiteIds = resolveSavedIds(whiteIds.toList(), allApps)
        appsLoading = false
    }

    LaunchedEffect(planId) {
        if (planId != null) {
            store.plan(planId)?.let { plan ->
                name = plan.name
                type = plan.type.takeIf { it in listOf("学习", "工作", "运动", "睡眠") } ?: "学习"
                startTime = PlanTime.format(plan.startTime)
                endTime = PlanTime.format(plan.endTime)
                reminder = plan.remindAdvanceMin ?: 15
                val parsed = parseRepeat(plan.repeatRule)
                preset = parsed.first
                weekdays = parsed.second
            }
            store.strategy(planId)?.let { strategy ->
                val black = parseAppNames(strategy.blacklistApps)
                val white = parseAppNames(strategy.whitelistApps)
                val extras = (black + white).filter { saved -> installed.none { it.name == saved || it.id == saved } }.distinct().map { AppOption(it, it, "工具") }
                allApps = installed + extras
                blackIds = resolveSavedIds(black, allApps)
                whiteIds = resolveSavedIds(white, allApps)
                autoDnd = strategy.autoDnd ?: true
                notifySilence = strategy.notifySilence ?: true
                strictMode = strategy.strictMode == true
            }
        }
    }

    picker?.let { kind ->
        val selected = if (kind == AppListKind.BLACK) blackIds else whiteIds
        AppPickerSheet(
            title = if (kind == AppListKind.BLACK) "选择限制应用" else "选择放行应用",
            apps = allApps,
            selectedIds = selected,
            lockedIds = lockedIds,
            onToggle = { app ->
                fun toggled(value: Set<String>) = if (app.id in value) value - app.id else value + app.id
                if (kind == AppListKind.BLACK) blackIds = toggled(blackIds) else whiteIds = toggled(whiteIds)
            },
            onQuickAdd = { selectedApps ->
                val ids = selectedApps.map { it.id }.toSet()
                if (kind == AppListKind.BLACK) blackIds = blackIds + ids else whiteIds = whiteIds + ids
            },
            onDismiss = { picker = null }
        )
    }

    timePicker?.let { target ->
        val current = if (target == "start") startTime else endTime
        val parts = current.split(":")
        PlanTimePicker(
            title = if (target == "start") "开始时间" else "结束时间",
            initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 21,
            initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            crossDayHint = if (target == "end" && (PlanTime.minutesOfDay(endTime) ?: 0) <= (PlanTime.minutesOfDay(startTime) ?: 0)) "结束时间早于开始时间，将自动视为次日" else null,
            onConfirm = { hour, minute ->
                val value = "%02d:%02d".format(hour, minute)
                if (target == "start") startTime = value else endTime = value
                timePicker = null
            },
            onDismiss = { timePicker = null }
        )
    }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        TopAppBar(
            title = { Text(if (planId == null) "新建计划" else "编辑计划") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(value = name, onValueChange = { name = it; validation = null }, label = { Text("计划名称") }, trailingIcon = { Icon(Icons.Filled.Edit, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = validation != null, supportingText = validation?.let { { Text(it) } })
            }
            item { Section("类型") { PlanTypeSegmented(type) { type = it } } }
            item {
                Section("时间") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeField("开始", startTime, { timePicker = "start" }, Modifier.weight(1f))
                        TimeField("结束", endTime, { timePicker = "end" }, Modifier.weight(1f))
                    }
                    if ((PlanTime.minutesOfDay(endTime) ?: 0) <= (PlanTime.minutesOfDay(startTime) ?: 0)) Text("结束时间早于开始时间，将自动标记为次日", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            item { Section("重复") { RepeatRuleSelector(preset, weekdays, { choice -> preset = choice; weekdays = presetDays(choice) }, { day -> weekdays = if (day in weekdays) weekdays - day else weekdays + day; preset = RepeatPreset.CUSTOM }) } }
            item { Section("提醒") { ReminderStepper(reminder) { reminder = it } } }
            item {
                Section("执行策略") {
                    SettingSwitch("严格模式", "仅放行应用与系统基础功能可用", strictMode) { strictMode = it }
                    Text("开启后仅放行应用与系统基础功能可用，其余应用将被拦截", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (strictMode) {
                        Text("系统必要功能自动放行，基础工具按设置放行", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("临时使用未放行应用请暂停计划", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        AppSelectionRow("限制应用", if (appsLoading) "正在读取已安装应用…" else "执行时禁止打开", allApps, blackIds) { picker = AppListKind.BLACK }
                    }
                    AppSelectionRow("放行应用", if (appsLoading) "正在读取已安装应用…" else "始终可以打开", allApps, whiteIds) { picker = AppListKind.WHITE }
                }
            }
            item {
                Section("执行设置") {
                    SettingSwitch("免打扰", "执行时仅保留闹钟", autoDnd) { autoDnd = it }
                    SettingSwitch("通知静默", "减少无关通知干扰", notifySilence) { notifySilence = it }
                }
            }
            item {
                Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    onClick = {
                        if (name.isBlank()) { validation = "请输入计划名称"; return@Button }
                        scope.launch {
                            val save = suspend {
                                savePlan(planId, name.trim(), type, startTime, endTime, preset, weekdays, reminder, autoDnd, notifySilence, strictMode, allApps, blackIds, whiteIds, store, api)
                            }
                            val existing = planId?.let { store.plan(it) }
                            if (existing == null) {
                                save()
                                onDone()
                            } else {
                                requestOrRunPlanAction(
                                    actionGate, existing, ProtectedPlanAction.EDIT, editOperation = save,
                                    onPrompt = { pendingAction = it },
                                    onCompleted = { onDone() },
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存计划") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.labelLarge); content() }
    }
}

@Composable
private fun TimeField(label: String, value: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(modifier.clickable(onClick = onClick), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.titleMedium) }
            Icon(Icons.Filled.Schedule, "$label 时间")
        }
    }
}

@Composable
private fun AppSelectionRow(label: String, subtitle: String, apps: List<AppOption>, ids: Set<String>, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Apps, null); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(label, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Filled.ChevronRight, "选择")
        }
        if (ids.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(apps.filter { it.id in ids }) { AssistChip(onClick = onClick, label = { Text(it.name) }) } }
    }
}

private suspend fun savePlan(
    planId: Long?, name: String, type: String, start: String, end: String,
    preset: RepeatPreset, weekdays: Set<Int>, reminder: Int, autoDnd: Boolean, notifySilence: Boolean, strictMode: Boolean,
    apps: List<AppOption>, blackIds: Set<String>, whiteIds: Set<String>, store: RecordStore,
    api: com.planlock.app.api.PlanLockApi
) {
    val localId = planId ?: -System.currentTimeMillis()
    val rule = RepeatRule(type = preset.apiType, weekdays = weekdays.sorted().takeIf { preset != RepeatPreset.DAILY })
    val ruleJson = if (preset == RepeatPreset.DAILY) "{\"type\":\"daily\"}" else "{\"type\":\"weekly\",\"weekdays\":[${weekdays.sorted().joinToString()}]}"
    val existing = store.plan(localId)
    val snapshot = existing?.copy(
        name = name,
        type = type,
        startTime = "$start:00",
        endTime = "$end:00",
        repeatRule = ruleJson,
        remindAdvanceMin = reminder
    ) ?: PlanSnapshotEntity(localId, 0, name, type, "$start:00", "$end:00", ruleJson, null, null, reminder, null, 1, 0)
    store.updateLocalPlan(snapshot)
    val black = apps.filter { it.id in blackIds }
    val white = apps.filter { it.id in whiteIds }
    val strategyId = store.strategy(localId)?.id ?: -(System.currentTimeMillis() + 1)
    store.updateLocalStrategy(StrategySnapshotEntity(strategyId, localId, appJson(black), appJson(white), autoDnd, notifySilence, store.strategy(localId)?.version ?: 0, strictMode = strictMode))
    try {
        val request = PlanUpsertRequest(name, type, "$start:00", "$end:00", rule, remindAdvanceMin = reminder)
        val strategy = StrategyUpsertRequest(black.map { AppItem(it.name, it.id) }, white.map { AppItem(it.name, it.id) }, autoDnd, notifySilence)
        if (planId == null) {
            val response = api.createPlan(request)
            response.data?.id?.takeIf { response.code == 0 }?.let { serverId ->
                store.replacePlanId(localId, serverId); store.replaceStrategyPlanId(localId, serverId); api.upsertStrategy(serverId, null, strategy)
            }
        } else {
            val response = api.updatePlan(planId, store.plan(planId)?.version ?: 0, request)
            if (response.code == 0) api.upsertStrategy(planId, store.strategy(planId)?.version, strategy)
        }
    } catch (_: Exception) {
        // 本地保存已经完成，等待后续同步。
    }
}

private fun appJson(apps: List<AppOption>): String = apps.joinToString(prefix = "[", postfix = "]") { "{\"name\":\"${it.name.replace("\"", "\\\"")}\",\"packageName\":\"${it.id}\"}" }
private fun resolveSavedIds(values: List<String>, apps: List<AppOption>): Set<String> = values.map { saved -> apps.firstOrNull { it.id == saved || it.name == saved }?.id ?: saved }.toSet()
private fun presetDays(value: RepeatPreset): Set<Int> = when (value) { RepeatPreset.DAILY -> (1..7).toSet(); RepeatPreset.WEEKDAY -> setOf(1, 2, 3, 4, 5); RepeatPreset.WEEKEND -> setOf(6, 7); RepeatPreset.CUSTOM -> emptySet() }
private fun parseRepeat(raw: String?): Pair<RepeatPreset, Set<Int>> = when {
    raw == null || raw.contains("daily") -> RepeatPreset.DAILY to (1..7).toSet()
    raw.contains("1,2,3,4,5") || raw.contains("1, 2, 3, 4, 5") -> RepeatPreset.WEEKDAY to setOf(1, 2, 3, 4, 5)
    raw.contains("6,7") || raw.contains("6, 7") -> RepeatPreset.WEEKEND to setOf(6, 7)
    else -> RepeatPreset.CUSTOM to Regex("[1-7]").findAll(raw).map { it.value.toInt() }.toSet()
}
