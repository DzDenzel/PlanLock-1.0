package com.planlock.feature.plan

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.planlock.app.api.ApiClient
import com.planlock.app.api.planLockPreferences
import com.planlock.app.engine.executionHaptic
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.security.BackupPasswordStore
import com.planlock.design.LocalReducedMotion
import com.planlock.design.PageAtmosphere
import com.planlock.design.PlanCard
import com.planlock.design.PlanVisualState
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import com.planlock.design.planLockAtmosphere
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(onCreate: (Boolean) -> Unit = {}, onOpenDetail: (Long) -> Unit = {}) {
    val reducedMotion = LocalReducedMotion.current
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val actionGate = remember { PlanActionGate(store, api, BackupPasswordStore(context.planLockPreferences)) }
    val scope = rememberCoroutineScope()
    val clock = rememberPlanClock()
    var plans by remember { mutableStateOf<List<PlanSnapshotEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<PendingPlanAction?>(null) }
    var allExpanded by rememberSaveable { mutableStateOf(false) }
    var storageExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    PlanActionDialogHost(actionGate, pendingAction) { pendingAction = null }

    suspend fun refresh() {
        loading = true
        error = null
        try {
            val pull = api.syncPull()
            if (pull.code == 0) pull.data?.let { store.saveSnapshots(it.plans.orEmpty(), it.strategies.orEmpty(), it.schedules.orEmpty()) }
        } catch (_: Exception) {
            error = "无法连接服务，请检查网络后重试"
        }
        plans = store.plans()
        if (plans.isNotEmpty()) error = null
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    val nowMinutes = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
    val todayIso = PlanOccurrence.isoDayOfWeek(clock.get(Calendar.DAY_OF_WEEK))
    val overview = remember(plans, nowMinutes, todayIso) { PlanOverview.build(plans, nowMinutes, todayIso) }
    val filteredAll = remember(overview.scheduled, selectedCategory) {
        overview.scheduled.filter { selectedCategory == null || it.type.trim().ifEmpty { "其他" } == selectedCategory }
    }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.PLANS),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = { FloatingActionButton(onClick = { onCreate(plans.isEmpty()) }) { Icon(Icons.Filled.Add, "新建计划") } },
    ) { padding ->
        when {
            loading && plans.isEmpty() -> UIStatePanel(UiState.LOADING, modifier = Modifier.padding(padding))
            error != null && plans.isEmpty() -> UIStatePanel(UiState.ERROR, error, { scope.launch { refresh() } }, Modifier.padding(padding))
            plans.isEmpty() -> UIStatePanel(UiState.EMPTY, "建立第一项计划", { onCreate(true) }, Modifier.padding(padding))
            else -> PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { refresh() } }, modifier = Modifier.padding(padding)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("计划", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                            }
                            IconButton(onClick = { scope.launch { refresh() } }) { Icon(Icons.Filled.Refresh, "刷新") }
                        }
                    }
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { allExpanded = !allExpanded }
                                .semantics { contentDescription = if (allExpanded) "全部计划，已展开" else "全部计划，已收起" },
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("全部计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("共 ${plans.distinctBy { it.id }.size} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (allExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (allExpanded) "收起" else "展开")
                            }
                        }
                    }

                    if (!allExpanded) {
                        item { SectionTitle("当前执行", "${overview.current.size} 项") }
                        if (overview.current.isEmpty()) item { QuietText("当前没有正在执行的计划") }
                        items(overview.current, key = { "current-${it.id}" }) { plan ->
                            PlanCard(
                                plan.toCardData(PlanVisualState.EXECUTING),
                                onClick = { onOpenDetail(plan.id) },
                                modifier = Modifier.animateItem(tween(if (reducedMotion) 0 else 180), tween(if (reducedMotion) 0 else 220), tween(if (reducedMotion) 0 else 180)),
                                sharedKey = "plan-${plan.id}",
                                trailingAction = {
                                    TextButton(onClick = {
                                        scope.launch {
                                            requestOrRunPlanAction(actionGate, plan, ProtectedPlanAction.PAUSE,
                                                onPrompt = { pendingAction = it },
                                                onCompleted = { executionHaptic(context); scope.launch { refresh() } })
                                        }
                                    }) { Text("暂停") }
                                },
                                onOverflow = { onOpenDetail(plan.id) },
                            )
                        }

                        item { SectionTitle("近期计划", "未来 7 天 · ${overview.upcoming.size} 项") }
                        if (overview.upcoming.isEmpty()) item { QuietText("未来七天没有待开始计划") }
                        items(overview.upcoming, key = { "upcoming-${it.plan.id}" }) { occurrence ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(nextLabel(occurrence.dayOffset), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                PlanCard(occurrence.plan.toCardData(PlanVisualState.UPCOMING), onClick = { onOpenDetail(occurrence.plan.id) }, onOverflow = { onOpenDetail(occurrence.plan.id) })
                            }
                        }

                        if (overview.categoryCounts.isNotEmpty()) {
                            item { SectionTitle("必要分类", "按类型快速定位") }
                            overview.categoryCounts.entries.chunked(2).forEachIndexed { rowIndex, row ->
                                item(key = "category-row-$rowIndex") {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { (category, count) -> CategoryTile(category, count, Modifier.weight(1f)) {
                                            selectedCategory = category
                                            allExpanded = true
                                        } }
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        item { SectionTitle("完整列表", "${filteredAll.size} 项") }
                        item { CategoryFilter("全部", selectedCategory == null, Modifier.fillMaxWidth()) { selectedCategory = null } }
                        overview.categoryCounts.keys.chunked(3).forEachIndexed { rowIndex, row ->
                            item(key = "filter-row-$rowIndex") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { category ->
                                        CategoryFilter(category, selectedCategory == category, Modifier.weight(1f)) { selectedCategory = category }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                        items(filteredAll, key = { "all-${it.id}" }) { plan ->
                            PlanCard(plan.toCardData(if (plan in overview.current) PlanVisualState.EXECUTING else PlanVisualState.UPCOMING), onClick = { onOpenDetail(plan.id) }, onOverflow = { onOpenDetail(plan.id) })
                        }
                        item {
                            Surface(
                                Modifier.fillMaxWidth().semantics { contentDescription = if (storageExpanded) "暂停计划，已展开" else "暂停计划，已收起" }
                                    .clickable { storageExpanded = !storageExpanded },
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("已暂停 / 时间不完整", style = MaterialTheme.typography.titleMedium)
                                        Text("${overview.stored.size} 项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(if (storageExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (storageExpanded) "收起" else "展开")
                                }
                            }
                        }
                        if (storageExpanded) items(overview.stored, key = { "stored-${it.id}" }) { plan ->
                            PlanCard(plan.toCardData(PlanVisualState.PAUSED), onClick = { onOpenDetail(plan.id) }, onOverflow = { onOpenDetail(plan.id) })
                        }
                    }
                    item { Spacer(Modifier.padding(bottom = 48.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuietText(text: String) {
    Text(text, modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CategoryTile(name: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$count 项计划", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CategoryFilter(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), shape = MaterialTheme.shapes.large, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private fun nextLabel(offset: Int): String = when (offset) {
    0 -> "今天"
    1 -> "明天"
    else -> "$offset 天后"
}
