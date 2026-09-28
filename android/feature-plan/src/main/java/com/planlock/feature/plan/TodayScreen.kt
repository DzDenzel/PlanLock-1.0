package com.planlock.feature.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.planlock.app.api.ApiClient
import com.planlock.app.api.planLockPreferences
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.WeatherClient
import com.planlock.app.api.DeviceWeatherLocation
import com.planlock.app.api.WeatherLocationChoice
import com.planlock.app.api.WeatherSnapshot
import com.planlock.app.api.WeatherStore
import androidx.datastore.preferences.core.edit
import com.planlock.app.engine.ExecutionEngine
import com.planlock.app.engine.ExecutionFeedback
import com.planlock.app.engine.executionHaptic
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.DayTimeline
import com.planlock.app.record.RecordStore
import com.planlock.app.record.TimelineEntry
import com.planlock.app.record.TimelineKind
import com.planlock.app.record.TimelineState
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import com.planlock.app.sync.SyncClient
import com.planlock.app.security.BackupPasswordStore
import com.planlock.design.PlanCardData
import com.planlock.design.PlanVisualState
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import com.planlock.design.V2Tokens
import com.planlock.design.planLockAtmosphere
import com.planlock.design.PageAtmosphere
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TodayScreen(
    onCreate: (Boolean) -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val actionGate = remember { PlanActionGate(store, api, BackupPasswordStore(context.planLockPreferences)) }
    val syncClient = remember { SyncClient(api, store) }
    val weatherClient = remember { WeatherClient() }
    val weatherStore = remember { WeatherStore(context.planLockPreferences) }
    val deviceWeatherLocation = remember { DeviceWeatherLocation(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val clock = rememberPlanClock()
    var plans by remember { mutableStateOf<List<PlanSnapshotEntity>>(emptyList()) }
    var schedules by remember { mutableStateOf<List<ScheduleSnapshotEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var streak by remember { mutableIntStateOf(0) }
    var violations by remember { mutableIntStateOf(0) }
    var unlocks by remember { mutableIntStateOf(0) }
    var runningId by remember { mutableStateOf(ExecutionEngine.activePlanId()) }
    var weather by remember { mutableStateOf<WeatherSnapshot?>(null) }
    var weatherFromCache by remember { mutableStateOf(false) }
    var showWeatherInfo by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingPlanAction?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var showCreateActions by remember { mutableStateOf(false) }
    var showScheduleEditor by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<ScheduleSnapshotEntity?>(null) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var timelineExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var positionedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val density = LocalDensity.current

    PlanActionDialogHost(actionGate, pendingAction, onDismiss = { pendingAction = null })

    suspend fun refresh() {
        plans = store.plans()
        schedules = store.schedules()
        runCatching { api.today() }.getOrNull()?.data?.let {
            violations = it.violationCount
            unlocks = it.unlockCount
            context.planLockPreferences.edit { values -> values[PlanLockPreferenceKeys.cachedTodayUnlocks] = it.unlockCount }
        }
        runCatching { api.stats() }.getOrNull()?.data?.let { stats ->
            streak = stats.continuousDays
            context.planLockPreferences.edit { values -> values[PlanLockPreferenceKeys.cachedContinuousDays] = stats.continuousDays }
            violations = maxOf(violations, stats.violationCount)
            unlocks = maxOf(unlocks, stats.unlockCount)
        }
        loading = false
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(30_000)
            plans = store.plans()
            schedules = store.schedules()
        }
    }
    LaunchedEffect(Unit) {
        val location = WeatherLocationChoice.select(
            located = deviceWeatherLocation.resolve(),
            manual = weatherStore.location(),
        ) ?: return@LaunchedEffect
        val cached = weatherStore.snapshot(location)
        val current = weatherClient.current(location)
        if (current != null) {
            weatherStore.saveSnapshot(current)
            weather = current
            weatherFromCache = false
        } else {
            weather = cached
            weatherFromCache = cached != null
        }
    }
    LaunchedEffect(Unit) {
        ExecutionFeedback.events.collect {
            runningId = ExecutionEngine.activePlanId()
            val latest = store.plans()
            plans = latest
        }
    }

    val nowMinutes = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
    val isoDay = PlanOccurrence.isoDayOfWeek(clock.get(Calendar.DAY_OF_WEEK))
    val active = plans.firstOrNull { it.id == runningId && PlanTime.isActive(it, nowMinutes, isoDay) }
        ?: plans.firstOrNull { PlanTime.isActive(it, nowMinutes, isoDay) }
    val todayKey = remember(clock) { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(clock.timeInMillis)) }
    val selectedKey = selectedDate ?: todayKey
    val selectedNowMinutes = if (selectedKey == todayKey) nowMinutes else 0
    val timeline = remember(plans, schedules, selectedKey, selectedNowMinutes) { DayTimeline.build(selectedKey, plans, schedules, selectedNowMinutes) }
    val window = remember(timeline, selectedNowMinutes) { DayTimeline.window(timeline, selectedNowMinutes) }
    LaunchedEffect(loading, selectedKey, timelineExpanded) {
        if (timelineExpanded && !loading && positionedDate != selectedKey && timeline.isNotEmpty()) {
            val offset = with(density) { ((nowMinutes - window.startMin) / 60f * 56f).dp.roundToPx() }
            listState.scrollToItem(4, if (selectedKey == todayKey) offset.coerceAtLeast(0) else 0)
            positionedDate = selectedKey
        }
    }

    if (showCreateActions) CreateActionSheet(
        onDismiss = { showCreateActions = false },
        onPlan = { showCreateActions = false; onCreate(plans.isEmpty()) },
        onSchedule = { showCreateActions = false; editingSchedule = null; showScheduleEditor = true },
    )
    if (showScheduleEditor) ScheduleEditorSheet(
        current = editingSchedule,
        plans = plans,
        onDismiss = { showScheduleEditor = false },
        onSave = { request ->
            scope.launch {
                val old = editingSchedule
                val localId = old?.id ?: -System.currentTimeMillis()
                store.saveLocalSchedule(ScheduleSnapshotEntity(localId, old?.userId ?: 1L, request.title, request.startDate, request.startTime, request.endDate, request.endTime, request.allDay, request.location, request.note, request.remindAdvanceMin, request.repeatRule, request.linkedPlanId, false, request.tzId, 1, old?.version ?: 0))
                store.enqueueScheduleUpsert(localId, old?.version?.takeIf { localId > 0 }, request)
                runCatching { syncClient.pushPendingRecords() }
                showScheduleEditor = false
                refresh()
            }
        },
        onDelete = editingSchedule?.let { old -> {
            scope.launch {
                store.deleteLocalSchedule(old.id)
                if (old.id > 0) store.enqueueScheduleDelete(old.id)
                runCatching { syncClient.pushPendingRecords() }
                showScheduleEditor = false
                refresh()
            }
        } },
    )
    if (showWeatherInfo) weather?.let { snapshot ->
        WeatherInfoDialog(snapshot = snapshot, fromCache = weatherFromCache, onDismiss = { showWeatherInfo = false })
    }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.TODAY),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding)
        ) {
            if (loading) {
                UIStatePanel(UiState.LOADING)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 28.dp),
                ) {
                    item {
                        TodayMasthead(
                            date = formatCurrentDate(clock),
                            streak = streak,
                            scheduleCount = timeline.size,
                            activeCount = if (active == null) 0 else 1,
                            weather = weather,
                            weatherFromCache = weatherFromCache,
                            onWeatherInfo = { showWeatherInfo = true },
                            onCreate = { showCreateActions = true },
                        )
                    }
                    item {
                        WeekIndicator(todayKey, selectedKey, plans, schedules, nowMinutes) { key ->
                            selectedDate = key.takeUnless { it == todayKey }
                            timelineExpanded = false
                            positionedDate = null
                        }
                    }
                    item {
                        Column {
                            if (selectedKey != todayKey) {
                                TextButton(onClick = { selectedDate = null; timelineExpanded = false; positionedDate = null }) { Text("回到今天") }
                            }
                            active?.let { plan ->
                            ActionCover(plan, nowMinutes) {
                                scope.launch {
                                    requestOrRunPlanAction(actionGate, plan, ProtectedPlanAction.PAUSE,
                                        onPrompt = { pendingAction = it },
                                    onCompleted = { warning -> actionMessage = warning; launch { refresh() } })
                                }
                            }
                            }
                        }
                    }
                    if (!timelineExpanded) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (selectedKey == todayKey) "接下来" else "当日安排", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { timelineExpanded = true; positionedDate = null }) { Text("查看今日全部") }
                            }
                        }
                        if (timeline.isEmpty()) item { DayEmptyCover(selectedKey == todayKey) { showCreateActions = true } }
                        items(timeline.take(3), key = { "compact-${it.kind}-${it.id}-${it.startMin}" }) { entry ->
                            CompactTimelineRow(entry) {
                                if (entry.kind == TimelineKind.PLAN) onOpenDetail(entry.id)
                                else { editingSchedule = schedules.firstOrNull { it.id == entry.id }; showScheduleEditor = true }
                            }
                        }
                    } else {
                        item {
                            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("完整时间轴", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { timelineExpanded = false }) { Text("收起") }
                            }
                        }
                        item(key = "timeline-canvas") {
                            if (timeline.isEmpty()) DayEmptyCover(selectedKey == todayKey) { showCreateActions = true }
                            else TimelineCanvas(timeline, selectedNowMinutes, onPlan = onOpenDetail, onSchedule = { id -> editingSchedule = schedules.firstOrNull { it.id == id }; showScheduleEditor = true })
                        }
                    }
                    item { StatsStrip(streak, violations, unlocks) }
                    actionMessage?.let { message ->
                        item { Text(message, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.tertiary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekIndicator(todayKey: String, selectedKey: String, plans: List<PlanSnapshotEntity>, schedules: List<ScheduleSnapshotEntity>, nowMinutes: Int, onSelect: (String) -> Unit) {
    val today = remember(todayKey) { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(todayKey) }
    val start = remember(today) {
        Calendar.getInstance().apply {
            time = today ?: Date()
            add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
        }
    }
    val days = remember(todayKey, plans, schedules, nowMinutes, start) {
        (0..6).map { dayIndex ->
            val day = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, dayIndex) }
            val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day.time)
            WeekIndicatorDay(
                label = listOf("一", "二", "三", "四", "五", "六", "日")[dayIndex],
                dayOfMonth = day.get(Calendar.DAY_OF_MONTH),
                dateKey = key,
                count = DayTimeline.build(key, plans, schedules, if (key == todayKey) nowMinutes else 0).size,
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(day.label, style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(
                    onClick = { onSelect(day.dateKey) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (day.dateKey == selectedKey) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    border = if (day.dateKey == todayKey && day.dateKey != selectedKey) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Text("${day.dayOfMonth}", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = if (day.dateKey == selectedKey) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                }
                Text(if (day.count > 0) "•".repeat(day.count.coerceAtMost(3)) else "", style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private data class WeekIndicatorDay(
    val label: String,
    val dayOfMonth: Int,
    val dateKey: String,
    val count: Int,
)

@Composable
private fun TodayMasthead(
    date: String,
    streak: Int,
    scheduleCount: Int,
    activeCount: Int,
    weather: WeatherSnapshot?,
    weatherFromCache: Boolean,
    onWeatherInfo: () -> Unit,
    onCreate: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("律刻", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text("PLANLOCK", Modifier.padding(start = 8.dp), style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        Surface(onClick = onCreate, shape = RoundedCornerShape(V2Tokens.shapePill), color = MaterialTheme.colorScheme.surfaceContainer) {
            Icon(Icons.Filled.Add, "新建安排", Modifier.padding(8.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
    Row(Modifier.padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(16.dp).height(2.dp).background(MaterialTheme.colorScheme.primary))
        Text(date, Modifier.padding(start = 10.dp), style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.primary)
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom) {
        Text("把今天\n握在手里。", Modifier.weight(1f), style = V2Tokens.headlineMedium.copy(fontSize = 40.sp, lineHeight = 42.sp), fontWeight = FontWeight.Black)
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(158.dp).padding(bottom = 4.dp)) {
            weather?.let { snapshot ->
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onWeatherInfo).padding(vertical = 2.dp),
                ) {
                    Text(snapshot.location.displayName, style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${"%.1f".format(Locale.US, snapshot.temperatureC)}°C · ${weatherLabel(snapshot.weatherCode)}  ⓘ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    if (weatherFromCache) Text("缓存 · ${snapshot.observedAt.takeLast(5)}", style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
            }
            Text("$scheduleCount 项安排", style = V2Tokens.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (activeCount == 1) "1 项正在执行" else "当前节奏平稳", style = V2Tokens.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (streak > 0) Text("连续 $streak 天", style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WeatherInfoDialog(snapshot: WeatherSnapshot, fromCache: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("天气数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(snapshot.location.displayName, style = MaterialTheme.typography.titleMedium)
                Text("观测时间 · ${snapshot.observedAt.takeLast(5)}")
                if (fromCache) Text("当前显示上次成功获取的数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("© Open-Meteo.com", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("CC BY 4.0", style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

@Composable
private fun ActionCover(plan: PlanSnapshotEntity, nowMinutes: Int, onPause: () -> Unit) {
    val shape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 10.dp)
    val remaining = PlanTime.remainingMinutes(plan, nowMinutes)
    Box(
        Modifier.fillMaxWidth().height(236.dp)
            .shadow(V2Tokens.brandE3.y, shape, ambientColor = V2Tokens.brandGlow, spotColor = V2Tokens.brandGlow)
            .clip(shape)
            .background(Brush.linearGradient(listOf(V2Tokens.brandBright, V2Tokens.brandDeep)))
            .drawBehind {
                val center = Offset(size.width * .9f, size.height * .12f)
                drawCircle(V2Tokens.onBrand.copy(alpha = .13f), size.width * .34f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                drawCircle(V2Tokens.onBrand.copy(alpha = .1f), size.width * .24f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }.padding(22.dp)
    ) {
        Column {
            Text("NOW / 正在执行", style = V2Tokens.labelMicro, color = V2Tokens.onBrand.copy(alpha = .78f), fontWeight = FontWeight.Bold)
            Text(plan.name, Modifier.padding(top = 9.dp), style = MaterialTheme.typography.titleLarge, color = V2Tokens.onBrand)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text("%02d:%02d".format(remaining / 60, remaining % 60), style = V2Tokens.numeralHero, color = V2Tokens.onBrand)
                Text("剩余", Modifier.padding(start = 9.dp, bottom = 7.dp), style = V2Tokens.bodyMedium, color = V2Tokens.onBrand.copy(alpha = .76f))
            }
            Spacer(Modifier.weight(1f))
            Text("${PlanTime.format(plan.startTime)} — ${PlanTime.format(plan.endTime)}", style = V2Tokens.labelMedium, color = V2Tokens.onBrand.copy(alpha = .9f))
            Text("${plan.type} · ${repeatLabel(plan.repeatRule)}", style = V2Tokens.labelMicro, color = V2Tokens.onBrand.copy(alpha = .72f))
        }
        Surface(onClick = onPause, modifier = Modifier.align(Alignment.BottomEnd).size(48.dp), shape = RoundedCornerShape(V2Tokens.shapePill), color = V2Tokens.brandContainer) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Pause, "暂停计划", tint = V2Tokens.onBrandContainer) }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(V2Tokens.onBrand.copy(alpha = .2f))) {
            Box(Modifier.fillMaxWidth(PlanTime.progress(plan, nowMinutes)).height(3.dp).background(V2Tokens.brandContainer))
        }
    }
}

@Composable
private fun CompactTimelineRow(entry: TimelineEntry, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DayTimeline.rangeLabel(entry), style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(timelineStateLabel(entry.state), style = V2Tokens.labelMedium, color = if (entry.state == TimelineState.RUNNING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayEmptyCover(isToday: Boolean, onCreate: () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isToday) "从一段专注开始" else "这一天还没有安排", style = MaterialTheme.typography.headlineMedium)
            Text(if (isToday) "建立第一项计划，今天的行动与节奏会在这里出现。" else "可以创建日程或计划，为这一天留下一段清晰节奏。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onCreate) { Text("新建安排") }
        }
    }
}


@Composable
private fun StatsStrip(streak: Int, violations: Int, unlocks: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        shape = RoundedCornerShape(V2Tokens.shapeMd),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Metric("$streak", "连续达成", Modifier.weight(1f))
            Metric("$violations", "今日违规", Modifier.weight(1f), true)
            Metric("$unlocks", "今日解锁", Modifier.weight(1f), true)
        }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier, divider: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (divider) Box(Modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(label, style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun PlanSnapshotEntity.toCardData(state: PlanVisualState? = null) = PlanCardData(
    id = id,
    name = name,
    type = type,
    timeText = "${PlanTime.format(startTime)} 至 ${PlanTime.format(endTime)}",
    repeatText = repeatLabel(repeatRule),
    state = state ?: if (status == 1) PlanVisualState.UPCOMING else PlanVisualState.PAUSED,
)

internal fun repeatLabel(raw: String?): String = when {
    raw == null || raw.contains("daily") -> "每天"
    raw.contains("1,2,3,4,5") || raw.contains("1, 2, 3, 4, 5") -> "工作日"
    raw.contains("6,7") || raw.contains("6, 7") -> "周末"
    else -> "自定义"
}

private fun formatCurrentDate(clock: Calendar): String {
    val week = listOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")[clock.get(Calendar.DAY_OF_WEEK) - 1]
    return "${clock.get(Calendar.MONTH) + 1} 月 ${clock.get(Calendar.DAY_OF_MONTH)} 日 · $week"
}

private fun timelineStateLabel(state: TimelineState): String = when (state) {
    TimelineState.RUNNING -> "进行中"
    TimelineState.UPCOMING -> "待开始"
    TimelineState.ENDED -> "已结束"
    TimelineState.PAUSED -> "已暂停"
}

private fun weatherLabel(code: Int): String = when (code) {
    0 -> "晴"
    1, 2 -> "少云"
    3 -> "阴"
    45, 48 -> "雾"
    in 51..67 -> "雨"
    in 71..77 -> "雪"
    in 80..82 -> "阵雨"
    in 85..86 -> "阵雪"
    in 95..99 -> "雷雨"
    else -> "天气"
}
