package com.planlock.feature.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import com.planlock.app.record.DayTimeline
import com.planlock.design.TimelineGeometry
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.planlock.app.api.dto.ScheduleUpsertRequest
import com.planlock.app.record.TimelineEntry
import com.planlock.app.record.TimelineKind
import com.planlock.app.record.TimelineState
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import com.planlock.design.V2Tokens
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateActionSheet(onDismiss:()->Unit,onPlan:()->Unit,onSchedule:()->Unit){
    ModalBottomSheet(onDismissRequest=onDismiss,shape=RoundedCornerShape(topStart=30.dp,topEnd=30.dp)){
        Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Text("把时间放到正确的位置",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text("计划约束长期行动，日程记录具体安排。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            ActionChoice("新建日程","会议、约会或某段时间要处理的事",Icons.Filled.CalendarMonth,onSchedule)
            ActionChoice("新建计划","需要重复坚持、限制应用或严格执行的目标",Icons.Filled.Flag,onPlan)
        }
    }
}

@Composable private fun ActionChoice(title:String,desc:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){
    Surface(onClick=onClick,shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
        Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){
            Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.primaryContainer){Icon(icon,null,Modifier.padding(10.dp),tint=MaterialTheme.colorScheme.primary)}
            Column(Modifier.padding(start=14.dp).weight(1f)){Text(title,fontWeight=FontWeight.Bold);Text(desc,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleEditorSheet(
    current:ScheduleSnapshotEntity?, plans:List<PlanSnapshotEntity>, onDismiss:()->Unit,
    onSave:(ScheduleUpsertRequest)->Unit,onDelete:(()->Unit)?=null,
){
    val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val today=remember{SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())}
    var title by remember(current){mutableStateOf(current?.title.orEmpty())}
    var startDate by remember(current){mutableStateOf(current?.startDate?:today)}
    var startTime by remember(current){mutableStateOf(current?.startTime?.take(5)?:"09:00")}
    var endDate by remember(current){mutableStateOf(current?.endDate?:today)}
    var endTime by remember(current){mutableStateOf(current?.endTime?.take(5)?:"10:00")}
    var allDay by remember(current){mutableStateOf(current?.allDay?:false)}
    var location by remember(current){mutableStateOf(current?.location.orEmpty())}
    var note by remember(current){mutableStateOf(current?.note.orEmpty())}
    var linked by remember(current){mutableStateOf(current?.linkedPlanId)}
    var reminder by remember(current){mutableStateOf(current?.remindAdvanceMin?.toString().orEmpty())}
    val valid=(reminder.isBlank() || reminder.toIntOrNull()?.let { it in 0..10080 } == true) && title.isNotBlank() && dateValid(startDate) && dateValid(endDate) && endDate>=startDate && (allDay || (timeValid(startTime)&&timeValid(endTime)&&(endDate>startDate||endTime>startTime)))
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=sheetState,shape=RoundedCornerShape(topStart=30.dp,topEnd=30.dp)){
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(if(current==null) "新建日程" else "编辑日程",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text("这是一段安排，不会触发锁机、违规或严格模式。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text("日程标题")},singleLine=true)
            Row(verticalAlignment=Alignment.CenterVertically){Text("全天",Modifier.weight(1f));Switch(allDay,{allDay=it})}
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(startDate,{startDate=it},Modifier.weight(1f),label={Text("开始日期")},singleLine=true);OutlinedTextField(endDate,{endDate=it},Modifier.weight(1f),label={Text("结束日期")},singleLine=true)}
            if(!allDay) Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(startTime,{startTime=it},Modifier.weight(1f),label={Text("开始")},singleLine=true);OutlinedTextField(endTime,{endTime=it},Modifier.weight(1f),label={Text("结束")},singleLine=true)}
            OutlinedTextField(location,{location=it},Modifier.fillMaxWidth(),label={Text("地点（可选）")},leadingIcon={Icon(Icons.Filled.LocationOn,null)},singleLine=true)
            OutlinedTextField(note,{note=it},Modifier.fillMaxWidth(),label={Text("备注（可选）")},minLines=2)
            OutlinedTextField(reminder,{reminder=it},Modifier.fillMaxWidth(),label={Text("提前提醒分钟（可选，仅保存）")},supportingText={Text("当前版本仅保存提醒设置，暂不发送通知")},singleLine=true)
            if(plans.isNotEmpty()){
                Text("关联计划（可选）",style=MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){plans.take(5).forEach{p->FilterChip(selected=linked==p.id,onClick={linked=if(linked==p.id)null else p.id},label={Text(p.name)})}}
            }
            if(current?.linkedPlanDeleted==true) Text("关联计划已删除",color=MaterialTheme.colorScheme.tertiary,style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                if(onDelete!=null) OutlinedButton(onClick=onDelete,Modifier.weight(1f)){Text("删除")}
                Button(onClick={onSave(ScheduleUpsertRequest(title.trim(),startDate,if(allDay)null else "$startTime:00",endDate,if(allDay)null else "$endTime:00",allDay,location.ifBlank{null},note.ifBlank{null},reminder.toIntOrNull(),current?.repeatRule,linked,TimeZone.getDefault().id))},enabled=valid,modifier=Modifier.weight(2f)){Text("保存日程")}
            }
        }
    }
}

@Composable
internal fun TimelineCanvas(entries:List<TimelineEntry>,nowMinutes:Int,onPlan:(Long)->Unit,onSchedule:(Long)->Unit){
    val window = DayTimeline.window(entries, nowMinutes)
    Surface(shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(top=18.dp,bottom=20.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=18.dp),verticalAlignment=Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("DAY CANVAS",style=V2Tokens.labelMicro,color=MaterialTheme.colorScheme.primary)
                    Text("今天的时间画布",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                }
                Text("计划 ◆  日程 ●",style=V2Tokens.labelMicro)
            }
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().height((TimelineGeometry.y(window.endMin,window.startMin)+36f).dp)) {
                val available = (maxWidth.value-52f-12f).coerceAtLeast(0f)
                for (minute in window.startMin..window.endMin step 30) {
                    Row(Modifier.offset(y=TimelineGeometry.y(minute,window.startMin).dp).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(if(minute%60==0) "%02d".format(minute/60) else "",Modifier.width(46.dp).padding(start=12.dp),style=V2Tokens.labelMicro,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(Modifier.weight(1f),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=if(minute%60==0) .65f else .25f))
                    }
                }
                Row(Modifier.offset(y=TimelineGeometry.y(nowMinutes,window.startMin).dp).padding(start=43.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary,RoundedCornerShape(50)))
                    HorizontalDivider(Modifier.weight(1f),color=MaterialTheme.colorScheme.tertiary)
                    Text("现在",style=V2Tokens.labelMicro,color=MaterialTheme.colorScheme.tertiary)
                }
                entries.forEach { entry ->
                    val g=TimelineGeometry.block(window.startMin,window.endMin,entry.startMin,entry.endMin,available,entry.column,entry.columnCount)
                    TimelineBlock(entry,Modifier.offset(x=(52f+g.left).dp,y=g.top.dp).width(g.width.dp).height(g.height.dp)) {
                        if(entry.kind==TimelineKind.PLAN) onPlan(entry.id) else onSchedule(entry.id)
                    }
                }
            }
        }
    }
}

@Composable private fun TimelineBlock(entry:TimelineEntry,modifier:Modifier,onClick:()->Unit) {
    val running=entry.state==TimelineState.RUNNING && entry.kind==TimelineKind.PLAN
    val ended=entry.state==TimelineState.ENDED
    val schedule=entry.kind==TimelineKind.SCHEDULE
    val shape=RoundedCornerShape(14.dp)
    val colors=when {
        running -> listOf(V2Tokens.brandBright,V2Tokens.brandDeep)
        schedule -> listOf(MaterialTheme.colorScheme.tertiaryContainer,MaterialTheme.colorScheme.surfaceContainer)
        else -> listOf(MaterialTheme.colorScheme.surfaceContainerHigh,MaterialTheme.colorScheme.primaryContainer.copy(alpha=.4f))
    }
    val fg=(if(running) Color.White else MaterialTheme.colorScheme.onSurface).copy(alpha=if(ended) .55f else 1f)
    val decorated=if(running) modifier.shadow(V2Tokens.brandE3.y,shape,ambientColor=V2Tokens.brandGlow,spotColor=V2Tokens.brandGlow) else modifier
    if (entry.backgroundBand) {
        Box(decorated.clip(shape).background(MaterialTheme.colorScheme.primary.copy(alpha=if(ended) .04f else .09f)).clickable(onClick=onClick)) {
            Box(Modifier.fillMaxHeight().width(4.dp).background(if(running) V2Tokens.brandBright else MaterialTheme.colorScheme.primary.copy(alpha=.5f)))
            Row(Modifier.fillMaxWidth().padding(start=12.dp,top=8.dp,end=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(entry.title,maxLines=1,fontWeight=FontWeight.Bold,fontSize=12.sp,color=MaterialTheme.colorScheme.primary.copy(alpha=if(ended) .55f else 1f))
                Text(DayTimeline.rangeLabel(entry),style=V2Tokens.labelMicro,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    Box(decorated.clip(shape).background(Brush.linearGradient(colors.map { if(ended) it.copy(alpha=.5f) else it })).clickable(onClick=onClick).padding(horizontal=10.dp,vertical=7.dp)) {
        Column {
            Text(if(running) "正在执行 · 计划" else entry.sourceLabel,style=V2Tokens.labelMicro,color=fg)
            Text(entry.title,maxLines=1,fontWeight=FontWeight.Bold,color=fg,fontSize=13.sp)
            Text(DayTimeline.rangeLabel(entry),style=V2Tokens.labelMicro,color=fg)
            if(!entry.backgroundBand) entry.location?.let { Text(it,maxLines=1,style=V2Tokens.labelMicro,color=fg) }
            if(entry.overflowCount>0) Text("+${entry.overflowCount} 项重叠",style=V2Tokens.labelMicro,color=fg)
        }
    }
}

private fun dateValid(v:String)=v.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
private fun timeValid(v:String)=v.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d"))
