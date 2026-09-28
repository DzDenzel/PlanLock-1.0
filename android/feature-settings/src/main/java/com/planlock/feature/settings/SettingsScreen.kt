package com.planlock.feature.settings

import android.provider.Settings
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.edit
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.planlock.app.api.ApiClient
import com.planlock.app.api.TokenStore
import com.planlock.app.api.AuthSession
import com.planlock.app.api.planLockPreferences
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.AssistantCapabilityRegistry
import com.planlock.app.api.WeatherClient
import com.planlock.app.api.WeatherLocation
import com.planlock.app.api.WeatherStore
import kotlinx.coroutines.CancellationException
import com.planlock.app.api.dto.BackupPasswordResetRequest
import com.planlock.app.api.dto.BackupPasswordSetRequest
import com.planlock.app.api.dto.DeviceDto
import com.planlock.app.api.dto.LoginRequest
import com.planlock.app.api.dto.MergeRequest
import com.planlock.app.api.dto.SendCodeRequest
import com.planlock.design.ThemeMode
import com.planlock.design.SettingSwitch
import com.planlock.design.planLockAtmosphere
import com.planlock.design.PageAtmosphere
import com.planlock.app.security.PermissionChecker
import com.planlock.app.security.PermissionKind
import com.planlock.app.security.BackupPasswordStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.IOException

private enum class Dialog { LOGIN, SET_PASSWORD, RESET_PASSWORD, WEATHER_CITY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit, onOpenPermissions: () -> Unit) {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val passwordStore = remember { BackupPasswordStore(context.planLockPreferences) }
    val weatherStore = remember { WeatherStore(context.planLockPreferences) }
    val weatherClient = remember { WeatherClient() }
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceDto>>(emptyList()) }
    var loadingDevices by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf(TokenStore.signedIn) }
    var basicToolsEnabled by remember { mutableStateOf(true) }
    var pluginsEnabled by remember { mutableStateOf(false) }
    var weatherLocation by remember { mutableStateOf<WeatherLocation?>(null) }
    var permissionStates by remember { mutableStateOf(PermissionChecker.check(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionStates = PermissionChecker.check(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    suspend fun loadDevices() {
        loadingDevices = true
        devices = runCatching { api.devices().data.orEmpty() }.getOrDefault(emptyList())
        loadingDevices = false
    }
    LaunchedEffect(Unit) {
        loadDevices()
        weatherLocation = weatherStore.location()
    }
    LaunchedEffect(context) {
        try {
            context.planLockPreferences.data.collect { preferences ->
                basicToolsEnabled = preferences[PlanLockPreferenceKeys.basicToolsEnabled] ?: true
                pluginsEnabled = preferences[PlanLockPreferenceKeys.pluginsEnabled] ?: false
            }
        } catch (_: IOException) {
            message = "基础工具设置读取失败，请重新打开设置页"
        }
    }

    dialog?.let { active ->
        when (active) {
            Dialog.LOGIN -> LoginDialog(
                onDismiss = { dialog = null },
                sendCode = { account ->
                    runCatching {
                        val result = api.sendCode(SendCodeRequest(account, "login"))
                        if (result.code == 0) null else result.message
                    }.getOrElse { "验证码发送失败，请检查网络" }
                },
                onSubmit = { account, credential, useCode ->
                    scope.launch {
                        val deviceUid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android-dev"
                        val guestId = TokenStore.guestUserId ?: TokenStore.userId
                        val result = runCatching {
                            api.login(
                                LoginRequest(
                                    account,
                                    password = credential.takeUnless { useCode },
                                    code = credential.takeIf { useCode },
                                    deviceUid = deviceUid,
                                    deviceName = "Android",
                                    platform = "android"
                                )
                            )
                        }.getOrNull()
                        val auth = result?.data
                        if (result?.code == 0 && auth?.token != null) {
                            try {
                                TokenStore.save(context.planLockPreferences,
                                    AuthSession(auth.token, auth.userId, auth.deviceId, guestId, true))
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                message = "登录身份保存失败，请重试"
                                return@launch
                            }
                            signedIn = true
                            val merged = guestId?.let { runCatching { api.merge(MergeRequest(it)) }.getOrNull()?.data?.get("mergedPlans") }
                            message = if (merged != null) "登录成功，已合并 $merged 个本机计划" else "登录成功"
                            dialog = null
                            loadDevices()
                        } else message = result?.message ?: "登录失败，请稍后重试"
                    }
                }
            )
            Dialog.SET_PASSWORD -> SetPasswordDialog(
                onDismiss = { dialog = null },
                onSubmit = { password, question, answer ->
                    scope.launch {
                        val ok = runCatching { api.setBackupPassword(BackupPasswordSetRequest(password, question, answer)).code == 0 }.getOrDefault(false)
                        if (ok) passwordStore.set(password)
                        message = if (ok) "备用密码已设置" else "设置失败，请检查输入或网络"
                        if (ok) dialog = null
                    }
                }
            )
            Dialog.RESET_PASSWORD -> ResetPasswordDialog(
                onDismiss = { dialog = null },
                onSubmit = { account, credential, useCode, password ->
                    scope.launch {
                        val request = BackupPasswordResetRequest(account, code = credential.takeIf { useCode }, securityAnswer = credential.takeUnless { useCode }, newBackupPassword = password)
                        val ok = runCatching { api.resetBackupPassword(request).code == 0 }.getOrDefault(false)
                        if (ok) passwordStore.set(password)
                        message = if (ok) "备用密码已重置" else "重置失败，请检查验证码或密保答案"
                        if (ok) dialog = null
                    }
                }
            )
            Dialog.WEATHER_CITY -> WeatherCityDialog(
                current = weatherLocation,
                search = weatherClient::searchCities,
                onDismiss = { dialog = null },
                onSelect = { location ->
                    scope.launch {
                        weatherStore.saveLocation(location)
                        weatherLocation = location
                        message = "天气城市已设为 ${location.displayName}"
                        dialog = null
                    }
                },
                onClear = {
                    scope.launch {
                        weatherStore.clearLocation()
                        weatherLocation = null
                        message = "天气城市已清除"
                        dialog = null
                    }
                },
            )
        }
    }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.QUIET),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 4.dp)) {
                    Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("集中管理外观、执行与系统权限", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SettingsCard {
                    GroupHeader("快捷设置")
                    Text("主题模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    HorizontalDivider()
                    SettingSwitch("基础工具默认放行", "保留常用工具，系统必要功能始终放行", basicToolsEnabled) { enabled ->
                        scope.launch {
                            try {
                                context.planLockPreferences.edit { it[PlanLockPreferenceKeys.basicToolsEnabled] = enabled }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                message = "基础工具设置保存失败，请重试"
                            }
                        }
                    }
                    HorizontalDivider()
                    SettingSwitch("插件功能", "显示当前版本内置的本机能力", pluginsEnabled) { enabled ->
                        scope.launch {
                            runCatching { context.planLockPreferences.edit { it[PlanLockPreferenceKeys.pluginsEnabled] = enabled } }
                                .onFailure { message = "插件设置保存失败，请重试" }
                        }
                    }
                    if (pluginsEnabled) {
                        AssistantCapabilityRegistry.entries.chunked(2).forEach { rowCapabilities ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowCapabilities.forEach { capability ->
                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                    ) {
                                        Text(
                                            capability.title,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                                if (rowCapabilities.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("天气城市", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(weatherLocation?.displayName ?: "未设置时今日页不显示天气", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { dialog = Dialog.WEATHER_CITY }) { Text(if (weatherLocation == null) "设置" else "更改") }
                    }
                }
            }
            item {
                SettingsCard {
                    GroupHeader("账户与安全")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (signedIn) "已登录账号" else "本机游客模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (signedIn) "计划可与其他设备同步" else "登录后可合并本机计划", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!signedIn) TextButton(onClick = { dialog = Dialog.LOGIN }) { Text("登录") }
                    }
                    HorizontalDivider()
                    Text("备用密码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("用于执行期间的紧急解锁", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { dialog = Dialog.SET_PASSWORD }, modifier = Modifier.weight(1f)) { Text("设置密码") }
                        OutlinedButton(onClick = { dialog = Dialog.RESET_PASSWORD }, modifier = Modifier.weight(1f)) { Text("重置") }
                    }
                }
            }
            message?.let { item { Text(it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } }

            item {
                SettingsCard {
                    GroupHeader("设备", if (loadingDevices) "读取中" else "${devices.size} 台")
                    when {
                        loadingDevices -> Text("正在读取设备…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        devices.isEmpty() -> CompactInfoRow("暂无可管理设备", "登录并联网后可查看和管理设备")
                    }
                }
            }
            if (!loadingDevices) items(devices, key = { it.id }) { device ->
                DeviceRow(
                    device,
                    onPrimary = { scope.launch { runCatching { api.setPrimaryDevice(device.id) }; loadDevices() } },
                    onUnbind = { scope.launch { runCatching { api.unbindDevice(device.id) }; loadDevices() } },
                )
            }

            item {
                SettingsCard {
                    val enabledCount = PermissionKind.entries.count { permissionStates[it] == true }
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onOpenPermissions).padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("权限与系统能力", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                            Text("$enabledCount/${PermissionKind.entries.size} 已开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "进入")
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("律刻 PlanLock · 让重要时间不被打断", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("v1.0.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
@Composable
private fun GroupHeader(title: String, status: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun CompactInfoRow(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PermissionSettingRow(
    title: String,
    description: String,
    status: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(status, style = MaterialTheme.typography.labelLarge, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun DeviceRow(device: DeviceDto, onPrimary: () -> Unit, onUnbind: () -> Unit) {
    SettingsCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(device.deviceName ?: "未命名设备", style = MaterialTheme.typography.titleMedium); Text(device.platform ?: "未知平台", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(if (device.online == true) "在线" else "离线", color = if (device.online == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (device.isPrimary == true) Text("主设备", color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (device.isPrimary != true) OutlinedButton(onClick = onPrimary, modifier = Modifier.weight(1f)) { Text("设为主设备") }
            OutlinedButton(onClick = onUnbind, modifier = Modifier.weight(1f)) { Text("解绑") }
        }
    }
}

@Composable
private fun LoginDialog(
    onDismiss: () -> Unit,
    sendCode: suspend (String) -> String?,
    onSubmit: (String, String, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var useCode by remember { mutableStateOf(false) }
    var cooldown by remember { mutableIntStateOf(0) }
    var sendMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1_000)
            cooldown--
        }
    }

    FormDialog("账号登录", onDismiss, account.isNotBlank() && credential.isNotBlank(), { onSubmit(account.trim(), credential, useCode) }) {
        OutlinedTextField(account, { account = it }, label = { Text("手机号或邮箱") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !useCode, onClick = { useCode = false; credential = ""; sendMessage = null }, label = { Text("密码登录") })
            FilterChip(selected = useCode, onClick = { useCode = true; credential = ""; sendMessage = null }, label = { Text("验证码登录") })
        }
        OutlinedTextField(
            credential,
            { credential = it },
            label = { Text(if (useCode) "验证码" else "密码") },
            singleLine = true,
            visualTransformation = if (useCode) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = if (useCode) {
                {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val error = sendCode(account.trim())
                                sendMessage = error ?: "验证码已发送"
                                if (error == null) cooldown = 60
                            }
                        },
                        enabled = account.isNotBlank() && cooldown == 0
                    ) { Text(if (cooldown > 0) "${cooldown}s" else "获取验证码") }
                }
            } else null
        )
        sendMessage?.let {
            Text(it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WeatherCityDialog(
    current: WeatherLocation?,
    search: suspend (String) -> List<WeatherLocation>,
    onDismiss: () -> Unit,
    onSelect: (WeatherLocation) -> Unit,
    onClear: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WeatherLocation>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置天气城市") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                current?.let { Text("当前：${it.displayName}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(query, { query = it }, label = { Text("输入城市名称") }, singleLine = true)
                Button(
                    onClick = {
                        scope.launch {
                            loading = true
                            searched = true
                            results = try {
                                search(query)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                            loading = false
                        }
                    },
                    enabled = query.isNotBlank() && !loading,
                ) { Text(if (loading) "搜索中…" else "搜索") }
                if (searched && !loading && results.isEmpty()) {
                    Text("没有找到匹配城市", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                results.forEach { location ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(location) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(location.displayName, Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        },
        confirmButton = {
            if (current != null) TextButton(onClick = onClear) { Text("清除城市") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
@Composable
private fun SetPasswordDialog(onDismiss: () -> Unit, onSubmit: (String, String?, String?) -> Unit) {
    var password by remember { mutableStateOf("") }; var question by remember { mutableStateOf("") }; var answer by remember { mutableStateOf("") }
    FormDialog("设置备用密码", onDismiss, password.length in 4..16, { onSubmit(password, question.ifBlank { null }, answer.ifBlank { null }) }) {
        OutlinedTextField(password, { password = it }, label = { Text("4–16 位备用密码") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(question, { question = it }, label = { Text("密保问题（可选）") })
        OutlinedTextField(answer, { answer = it }, label = { Text("密保答案（可选）") })
    }
}

@Composable
private fun ResetPasswordDialog(onDismiss: () -> Unit, onSubmit: (String, String, Boolean, String) -> Unit) {
    var account by remember { mutableStateOf("") }; var credential by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var useCode by remember { mutableStateOf(true) }
    FormDialog("重置备用密码", onDismiss, account.isNotBlank() && credential.isNotBlank() && password.length in 4..16, { onSubmit(account.trim(), credential.trim(), useCode, password) }) {
        OutlinedTextField(account, { account = it }, label = { Text("手机号或邮箱") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { useCode = true }) { Text(if (useCode) "● 验证码" else "验证码") }; TextButton(onClick = { useCode = false }) { Text(if (!useCode) "● 密保答案" else "密保答案") } }
        OutlinedTextField(credential, { credential = it }, label = { Text(if (useCode) "验证码" else "密保答案") })
        OutlinedTextField(password, { password = it }, label = { Text("新备用密码") }, visualTransformation = PasswordVisualTransformation())
    }
}

@Composable
private fun FormDialog(title: String, onDismiss: () -> Unit, valid: Boolean, onConfirm: () -> Unit, fields: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { fields() } },
        confirmButton = { Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), onClick = onConfirm, enabled = valid) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
