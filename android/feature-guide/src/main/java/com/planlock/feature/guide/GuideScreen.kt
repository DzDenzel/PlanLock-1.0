package com.planlock.feature.guide

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.planlock.app.api.ApiClient
import com.planlock.app.api.dto.AppItem
import com.planlock.app.api.dto.PlanUpsertRequest
import com.planlock.app.api.dto.StrategyUpsertRequest
import com.planlock.app.engine.AppPackageResolver
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.StrategySnapshotEntity
import com.planlock.design.AppOption
import com.planlock.design.AppPickerSheet
import com.planlock.design.PlanTimePicker
import com.planlock.design.ReminderStepper
import com.planlock.design.SettingSwitch
import com.planlock.design.StandardAppCategories
import com.planlock.design.StepIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class GuideAppList { RESTRICTED, ALLOWED }

@Composable
fun GuideScreen(onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val resolver = remember { AppPackageResolver(context) }
    val scope = rememberCoroutineScope()
    val categoryOptions = remember { StandardAppCategories.map { AppOption("category:$it", "${it}类应用", it) } }

    var step by remember { mutableIntStateOf(1) }
    var type by remember { mutableStateOf("") }
    var startTime by remember { mutableStateOf("14:00") }
    var endTime by remember { mutableStateOf("18:00") }
    var reminder by remember { mutableIntStateOf(15) }
    var autoDnd by remember { mutableStateOf(true) }
    var notifySilence by remember { mutableStateOf(true) }
    var strictMode by remember { mutableStateOf(false) }
    var restrictedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allowedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var apps by remember { mutableStateOf(categoryOptions) }
    var appsLoading by remember { mutableStateOf(true) }
    var picker by remember { mutableStateOf<GuideAppList?>(null) }
    var timePicker by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val installed = withContext(Dispatchers.IO) {
            resolver.installedApps().map { AppOption(it.packageName, it.name, it.category, it.icon?.toBitmap(64, 64)?.asImageBitmap()) }
        }
        apps = categoryOptions + installed
        appsLoading = false
    }

    fun applyTemplate(template: SceneTemplate?) {
        if (template == null) {
            restrictedIds = emptySet(); allowedIds = emptySet(); reminder = 15; autoDnd = true; notifySilence = true; strictMode = false
            return
        }
        reminder = template.remindAdvanceMin
        autoDnd = template.autoDnd
        notifySilence = template.notifySilence
        strictMode = template.strictMode
        restrictedIds = template.blacklist.map { "category:$it" }.toSet()
        allowedIds = template.whitelist.map { "category:$it" }.toSet()
    }

    picker?.let { kind ->
        val selected = if (kind == GuideAppList.RESTRICTED) restrictedIds else allowedIds
        AppPickerSheet(
            title = if (kind == GuideAppList.RESTRICTED) "选择限制应用" else "选择放行应用",
            apps = apps,
            selectedIds = selected,
            onToggle = { app ->
                fun toggle(value: Set<String>) = if (app.id in value) value - app.id else value + app.id
                if (kind == GuideAppList.RESTRICTED) restrictedIds = toggle(restrictedIds) else allowedIds = toggle(allowedIds)
            },
            onQuickAdd = { selectedApps ->
                val ids = selectedApps.map { it.id }.toSet()
                if (kind == GuideAppList.RESTRICTED) restrictedIds = restrictedIds + ids else allowedIds = allowedIds + ids
            },
            onDismiss = { picker = null }
        )
    }

    timePicker?.let { target ->
        val current = if (target == "start") startTime else endTime
        val parts = current.split(":")
        PlanTimePicker(
            title = if (target == "start") "开始时间" else "结束时间",
            initialHour = parts.firstOrNull()?.toIntOrNull() ?: 14,
            initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            crossDayHint = if (target == "end" && minutes(endTime) <= minutes(startTime)) "结束时间早于开始时间，将自动视为次日" else null,
            onConfirm = { hour, minute ->
                val value = "%02d:%02d".format(hour, minute)
                if (target == "start") startTime = value else endTime = value
                timePicker = null
            },
            onDismiss = { timePicker = null }
        )
    }

    Scaffold(
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step > 1) OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("上一步") }
                    Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        onClick = {
                            if (step < 4) step++ else scope.launch {
                                saveGuidePlan(type, startTime, endTime, reminder, autoDnd, notifySilence, strictMode, apps, restrictedIds, allowedIds, store, api)
                                onDone()
                            }
                        },
                        enabled = type.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (step < 4) "下一步" else "完成") }
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { StepIndicator(step) }
            item {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(180)) { direction * it / 3 } + fadeIn(tween(180)))
                            .togetherWith(slideOutHorizontally(tween(180)) { -direction * it / 3 } + fadeOut(tween(180)))
                    },
                    label = "guide-step"
                ) { currentStep ->
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (currentStep) {
                            1 -> {
                                Text("选择你的计划目标", style = MaterialTheme.typography.headlineMedium)
                                Text("选择学习、运动或睡眠会自动套用推荐策略，之后仍可调整。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                targets.forEach { target ->
                                    TargetCard(target, selected = type == target.type) {
                                        type = target.type
                                        applyTemplate(SceneTemplates.byType(target.type))
                                    }
                                }
                            }
                            2 -> {
                                if (strictMode) {
                                    Text("严格模式已开启", style = MaterialTheme.typography.headlineMedium)
                                    Text("未放行应用将被拦截，无需逐个选择限制应用。下一步选择放行应用，也可在第 4 步关闭严格模式。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("系统必要功能自动放行，基础工具按设置放行", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text("选择限制应用", style = MaterialTheme.typography.headlineMedium)
                                    AppChoice("执行时禁止打开", appsLoading, apps, restrictedIds) { picker = GuideAppList.RESTRICTED }
                                }
                            }
                            3 -> {
                                Text("选择放行应用", style = MaterialTheme.typography.headlineMedium)
                                AppChoice("这些应用始终可以打开", appsLoading, apps, allowedIds) { picker = GuideAppList.ALLOWED }
                            }
                            4 -> {
                                Text("设置执行时间", style = MaterialTheme.typography.headlineMedium)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        GuideTimeField("开始", startTime, { timePicker = "start" }, Modifier.weight(1f))
                                        GuideTimeField("结束", endTime, { timePicker = "end" }, Modifier.weight(1f))
                                    }
                                    if (minutes(endTime) <= minutes(startTime)) {
                                        Text("结束时间早于开始时间，将自动标记为次日", color = MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Column(Modifier.padding(14.dp)) { ReminderStepper(reminder) { reminder = it } }
                                }
                                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Column(Modifier.padding(14.dp)) {
                                        SettingSwitch("严格模式", "仅放行应用与系统基础功能可用", strictMode) { strictMode = it }
                                        Text("开启后仅放行应用与系统基础功能可用，其余应用将被拦截", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (strictMode) {
                                            Text("系统必要功能自动放行，基础工具按设置放行", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("临时使用未放行应用请暂停计划", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        SettingSwitch("免打扰", "执行时仅保留闹钟", autoDnd) { autoDnd = it }
                                        SettingSwitch("通知静默", "减少无关通知干扰", notifySilence) { notifySilence = it }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class Target(val type: String, val description: String, val icon: ImageVector)
private val targets = listOf(
    Target("学习", "严格模式：仅放行应用与系统基础功能可用", Icons.Filled.School),
    Target("运动", "保留音乐与运动健康应用", Icons.Filled.FitnessCenter),
    Target("睡眠", "严格模式：仅放行应用与系统基础功能可用，静默通知", Icons.Filled.Bedtime),
    Target("工作", "从空白策略开始配置", Icons.Filled.Work),
    Target("自定义", "按自己的节奏设置", Icons.Filled.Tune)
)

@Composable
private fun TargetCard(target: Target, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(target.icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 16.dp)) { Text(target.type, style = MaterialTheme.typography.titleMedium); Text(target.description, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun AppChoice(subtitle: String, loading: Boolean, apps: List<AppOption>, ids: Set<String>, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Apps, null); Text(if (loading) "正在读取已安装应用…" else "$subtitle · 已选 ${ids.size} 项", Modifier.padding(start = 12.dp)) }
            if (ids.isEmpty()) Text("点按打开应用选择器", color = MaterialTheme.colorScheme.primary)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(apps.filter { it.id in ids }) { AssistChip(onClick = onClick, label = { Text(it.name) }) } }
        }
    }
}

@Composable
private fun GuideTimeField(label: String, value: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(modifier.clickable(onClick = onClick), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.titleMedium) }
            Icon(Icons.Filled.Schedule, "$label 时间")
        }
    }
}

private suspend fun saveGuidePlan(
    type: String, start: String, end: String, reminder: Int, autoDnd: Boolean, notifySilence: Boolean, strictMode: Boolean,
    apps: List<AppOption>, restrictedIds: Set<String>, allowedIds: Set<String>, store: RecordStore,
    api: com.planlock.app.api.PlanLockApi
) {
    val localId = -System.currentTimeMillis()
    val restricted = apps.filter { it.id in restrictedIds }
    val allowed = apps.filter { it.id in allowedIds }
    store.saveLocalPlan(PlanSnapshotEntity(localId, 0, type, type, "$start:00", "$end:00", null, null, null, reminder, null, 1, 0))
    store.saveLocalStrategy(StrategySnapshotEntity(-(System.currentTimeMillis() + 1), localId, localList(restricted), localList(allowed), autoDnd, notifySilence, 0, strictMode = strictMode))
    try {
        val response = api.createPlan(PlanUpsertRequest(type, type, "$start:00", "$end:00", remindAdvanceMin = reminder))
        response.data?.id?.takeIf { response.code == 0 }?.let { serverId ->
            store.replacePlanId(localId, serverId)
            store.replaceStrategyPlanId(localId, serverId)
            api.upsertStrategy(serverId, null, StrategyUpsertRequest(apiItems(restricted), apiItems(allowed), autoDnd, notifySilence))
        }
    } catch (_: Exception) {
        // 本地数据已保存，等待同步。
    }
}

private fun localList(apps: List<AppOption>): String = apps.joinToString(",") { if (it.id.startsWith("category:")) it.category else it.id }
private fun apiItems(apps: List<AppOption>): List<AppItem> = apps.map { AppItem(name = if (it.id.startsWith("category:")) it.category else it.name, packageName = it.id.takeUnless { id -> id.startsWith("category:") }.orEmpty()) }
private fun minutes(value: String): Int = value.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
