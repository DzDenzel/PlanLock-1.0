package com.planlock.feature.stats

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.planlock.app.api.ApiClient
import com.planlock.app.api.dto.ApiResponse
import com.planlock.app.api.dto.DailyStat
import com.planlock.app.api.dto.ExecutionRecordDto
import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.api.dto.TodaySummary
import com.planlock.app.record.RecordStore
import com.planlock.design.StatTile
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import com.planlock.design.V2Tokens
import com.planlock.design.planLockAtmosphere
import com.planlock.design.PageAtmosphere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class ExportState { IDLE, EXPORTING, SUCCESS, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<StatsResponse?>(null) }
    var today by remember { mutableStateOf<List<ExecutionRecordDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    var planNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var exportState by remember { mutableStateOf(ExportState.IDLE) }

    suspend fun load() {
        loading = true
        val localPlans = runCatching { store.plans() }.getOrDefault(emptyList())
        planNames = localPlans.associate { it.id to it.name }
        try {
            val snapshot = statsSnapshot(api.stats("week"), api.today())
            stats = snapshot.first
            today = snapshot.second
            offline = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stats = StatsResponse(period = "week", completedCount = localPlans.count { it.status == 1 })
            today = emptyList()
            offline = true
        }
        loading = false
    }

    suspend fun export(uri: Uri, format: String) {
        exportState = ExportState.EXPORTING
        try {
            val body = withContext(Dispatchers.IO) {
                if (format == "ics") api.exportPlans().string() else api.exportRecords(format).string()
            }
            withContext(Dispatchers.IO) {
                val output = checkNotNull(context.contentResolver.openOutputStream(uri)) { "无法打开导出文件" }
                output.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            exportState = ExportState.SUCCESS
        } catch (_: Exception) {
            exportState = ExportState.ERROR
        }
    }

    val icsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri -> uri?.let { scope.launch { export(it, "ics") } } }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { scope.launch { export(it, "csv") } } }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { scope.launch { export(it, "json") } } }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.STATS),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        when {
            loading -> UIStatePanel(UiState.LOADING, "正在汇总本周数据", modifier = Modifier.padding(padding))
            stats == null -> UIStatePanel(UiState.ERROR, "统计数据不可用", { scope.launch { load() } }, Modifier.padding(padding))
            else -> {
                val value = stats!!
                val daily = normalizedWeek(value.daily.orEmpty())
                LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(top=26.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){Text("统计",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Black,modifier=Modifier.weight(1f));Text("近 7 天",style=V2Tokens.labelMicro,color=MaterialTheme.colorScheme.primary)}
                        if (offline) Text(
                            "在线数据暂不可用，当前为本机概览；完成数依据已启用计划估算，违规、解锁与今日明细可能不完整。",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    item { InsightHero(value.continuousDays,value.completedCount,daily) }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AnimatedStatTile(value.continuousDays, " 天", "连续达成", "本周", modifier = Modifier.weight(1f))
                            AnimatedStatTile(value.violationCount, label = "违规", trend = "本周累计", danger = value.violationCount > 0, modifier = Modifier.weight(1f))
                            AnimatedStatTile(value.unlockCount, label = "解锁", trend = "本周累计", danger = value.unlockCount > 0, modifier = Modifier.weight(1f))
                        }
                    }
                    item { Text("今日明细", style = MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp)) }
                    if (today.isEmpty()) item { UIStatePanel(UiState.EMPTY, "今天还没有执行记录") }
                    else items(today, key = { it.id }) { record -> ExecutionRow(record, planNames[record.planId]) }
                    item { Text("带走你的数据", style = MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp)) }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { icsLauncher.launch("planlock-plans.ics") }, modifier = Modifier.weight(1f)) { androidx.compose.material3.Icon(Icons.Filled.Download, null); Text("ICS") }
                            Button(onClick = { csvLauncher.launch("planlock-records.csv") }, modifier = Modifier.weight(1f)) { Text("CSV") }
                            Button(onClick = { jsonLauncher.launch("planlock-records.json") }, modifier = Modifier.weight(1f)) { Text("JSON") }
                        }
                    }
                    if (exportState != ExportState.IDLE) item {
                        Text(
                            when (exportState) {
                                ExportState.EXPORTING -> "正在导出…"
                                ExportState.SUCCESS -> "导出完成"
                                ExportState.ERROR -> "导出失败，请检查网络后重试"
                                ExportState.IDLE -> ""
                            },
                            color = if (exportState == ExportState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// 两份接口数据均有效才提交在线快照，业务失败沿用页面的本地降级流程。
internal fun statsSnapshot(
    stats: ApiResponse<StatsResponse>,
    today: ApiResponse<TodaySummary>
): Pair<StatsResponse, List<ExecutionRecordDto>> {
    check(stats.code == 0 && today.code == 0) { "统计接口返回业务错误" }
    return checkNotNull(stats.data) to checkNotNull(today.data).executions.orEmpty()
}

@Composable
private fun InsightHero(streak: Int, completed: Int, daily: List<DailyStat>) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 10.dp), color = MaterialTheme.colorScheme.primary) {
        Box(Modifier.background(Brush.linearGradient(listOf(V2Tokens.brandBright, V2Tokens.brandDeep))).padding(22.dp)) {
            Column {
                Text("THIS WEEK", style = V2Tokens.labelMicro, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .72f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$streak", style = V2Tokens.numeralHero, color = androidx.compose.ui.graphics.Color.White)
                    Text(" 天连续", color = androidx.compose.ui.graphics.Color.White.copy(alpha = .82f), modifier = Modifier.padding(start = 8.dp, bottom = 9.dp))
                }
                Text("本周完成 $completed 次 · 每一次完成都会留下刻度", color = androidx.compose.ui.graphics.Color.White.copy(alpha = .8f))
                WeekChart(daily, true)
            }
        }
    }
}

@Composable
private fun AnimatedStatTile(
    value: Int,
    suffix: String = "",
    label: String,
    trend: String,
    danger: Boolean = false,
    modifier: Modifier = Modifier
) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val animatedValue by animateIntAsState(targetValue = if (started) value else 0, animationSpec = tween(450), label = "stat-number")
    StatTile("$animatedValue$suffix", label, trend, danger, modifier)
}

@Composable
private fun WeekChart(daily: List<DailyStat>, onBrand: Boolean = false) {
    val growth = remember { Animatable(0f) }
    LaunchedEffect(daily) { growth.snapTo(0f); growth.animateTo(1f, tween(500)) }
    val primary = if (onBrand) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = if (onBrand) androidx.compose.ui.graphics.Color.Transparent else surface.copy(alpha = .45f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.fillMaxWidth().height(if (onBrand) 88.dp else 120.dp)) {
                val slot = size.width / daily.size.coerceAtLeast(1)
                val max = daily.maxOfOrNull { it.completed }?.coerceAtLeast(1) ?: 1
                daily.forEachIndexed { index, item ->
                    val height = (size.height * item.completed / max.toFloat() * growth.value).coerceAtLeast(4f)
                    drawRoundRect(primary, topLeft = androidx.compose.ui.geometry.Offset(index * slot + slot * .2f, size.height - height), size = androidx.compose.ui.geometry.Size(slot * .6f, height.coerceAtLeast(0f)), cornerRadius = CornerRadius(10f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                daily.forEach { Text(weekdayLabel(it.date), style = MaterialTheme.typography.labelMedium, color = if (onBrand) androidx.compose.ui.graphics.Color.White.copy(alpha = .75f) else MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

@Composable
private fun ExecutionRow(record: ExecutionRecordDto, planName: String?) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(planName ?: "计划 #${record.planId}", style = MaterialTheme.typography.titleMedium); Text(record.startedAt ?: "未记录开始时间", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(if (record.status == 1) "已完成" else "执行中", color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun isoDateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

internal fun normalizedWeek(values: List<DailyStat>, today: String = isoDateFormat().format(Date())): List<DailyStat> {
    val formatter = isoDateFormat()
    val byDate = values.mapNotNull { item ->
        item.date?.takeIf { value -> runCatching { formatter.parse(value) }.getOrNull() != null }
            ?.let { it to item.completed }
    }.toMap()
    val todayDate = requireNotNull(formatter.parse(today)) { "Invalid date: $today" }
    return (6 downTo 0).map { offset ->
        val date = Calendar.getInstance().apply {
            time = todayDate
            add(Calendar.DAY_OF_MONTH, -offset)
        }.let { formatter.format(it.time) }
        DailyStat(date, byDate[date] ?: 0)
    }
}

private fun weekdayLabel(value: String?): String {
    val date = value?.let { runCatching { isoDateFormat().parse(it) }.getOrNull() } ?: return "—"
    val day = Calendar.getInstance().apply { time = date }.get(Calendar.DAY_OF_WEEK)
    return listOf("日", "一", "二", "三", "四", "五", "六")[day - 1]
}
