package com.planlock.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.planlock.app.api.ApiClient
import com.planlock.app.api.AssistantCapabilityRegistry
import com.planlock.app.api.AssistantIntent
import com.planlock.app.api.AssistantIntentParser
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.security.BackupPasswordStore
import com.planlock.feature.plan.PendingPlanAction
import com.planlock.feature.plan.PlanActionDialogHost
import com.planlock.feature.plan.PlanActionGate
import com.planlock.feature.plan.PlanTime
import com.planlock.feature.plan.ProtectedPlanAction
import com.planlock.feature.plan.requestOrRunPlanAction
import com.planlock.design.V2Tokens
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import com.planlock.design.planLockAtmosphere
import com.planlock.design.PageAtmosphere
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen() {
    val context = LocalContext.current
    val store = remember { RecordStore(context) }
    val api = remember { ApiClient.create() }
    val gate = remember { PlanActionGate(store, api, BackupPasswordStore(context.planLockPreferences)) }
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<PlanSnapshotEntity>>(emptyList()) }
    var streak by remember { mutableStateOf(0) }
    var unlocks by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("可以直接问我下面四类本地问题。") }
    var pendingAction by remember { mutableStateOf<PendingPlanAction?>(null) }

    PlanActionDialogHost(gate, pendingAction) { pendingAction = null }

    suspend fun refreshLocal() {
        plans = store.plans()
        val values = context.planLockPreferences.data.first()
        streak = values[PlanLockPreferenceKeys.cachedContinuousDays] ?: 0
        unlocks = values[PlanLockPreferenceKeys.cachedTodayUnlocks] ?: 0
    }
    LaunchedEffect(Unit) { refreshLocal() }

    fun submit() {
        scope.launch {
            val now = Calendar.getInstance()
            val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val isoDay = PlanOccurrence.isoDayOfWeek(now.get(Calendar.DAY_OF_WEEK))
            val active = plans.firstOrNull { PlanTime.isActive(it, minutes, isoDay) }
            when (AssistantIntentParser.parse(input)) {
                AssistantIntent.UNLOCKS -> answer = "今天已记录 $unlocks 次解锁。当前版本没有设置每日次数上限。"
                AssistantIntent.REMAINING_TIME -> answer = active?.let {
                    val remaining = PlanTime.remainingMinutes(it, minutes)
                    "${it.name} 还剩 %02d:%02d。".format(remaining / 60, remaining % 60)
                } ?: "当前没有正在执行的计划。"
                AssistantIntent.STREAK -> answer = "当前连续达成 $streak 天。"
                AssistantIntent.TOGGLE_TODAY_PLAN -> {
                    val target = active ?: plans.firstOrNull {
                        PlanOccurrence.isWithinOccurrence(it, minutes, isoDay)
                    }
                    if (target == null) answer = "当前时段没有可暂停或恢复的计划。"
                    else requestOrRunPlanAction(
                        gate, target, ProtectedPlanAction.PAUSE,
                        onPrompt = { pendingAction = it },
                        onCompleted = {
                            answer = if (target.status == 1) "已暂停 ${target.name}。" else "已恢复 ${target.name}。"
                            scope.launch { refreshLocal() }
                        },
                    )
                }
                null -> answer = "这项能力尚未接入。你可以点击上方能力卡查看可用问法。"
            }
        }
    }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.ASSISTANT),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 2.dp)) {
                    Text("本地助手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("快速询问计划与执行状态", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Surface(shape=RoundedCornerShape(28.dp,28.dp,28.dp,10.dp),color=MaterialTheme.colorScheme.primary){
                    Box(Modifier.background(Brush.linearGradient(listOf(V2Tokens.brandBright,V2Tokens.brandDeep))).padding(22.dp)){
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("本地规则，非 AI 对话",color=androidx.compose.ui.graphics.Color.White,fontWeight=FontWeight.Bold);Text("所有回答来自本机计划与最近保存的统计。断网可用，数据不离开设备。",color=androidx.compose.ui.graphics.Color.White.copy(alpha=.78f));Text(answer,color=androidx.compose.ui.graphics.Color.White,style=MaterialTheme.typography.titleMedium)}
                    }
                }
            }
            item { Text("可用指令",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp)) }
            if (AssistantCapabilityRegistry.entries.isEmpty()) {
                item {
                    UIStatePanel(
                        state = UiState.EMPTY,
                        message = "暂无可用能力，请在设置中检查插件功能。",
                    )
                }
            } else {
                items(AssistantCapabilityRegistry.entries, key = { it.id }) { capability ->
                    Card(
                        Modifier.fillMaxWidth().clickable { input = capability.example },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
                            Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primaryContainer){Text("✦",Modifier.padding(horizontal=11.dp,vertical=8.dp),color=MaterialTheme.colorScheme.primary)}
                            Column(Modifier.padding(start=14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(capability.title, style = MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Text(capability.description, color = MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                                Text(capability.example, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input, { input = it }, label = { Text("输入本地指令") }, modifier = Modifier.weight(1f), singleLine = true,shape=RoundedCornerShape(18.dp))
                    Button(onClick = ::submit, enabled = input.isNotBlank()) { Text("发送") }
                }
            }
        }
    }
}
