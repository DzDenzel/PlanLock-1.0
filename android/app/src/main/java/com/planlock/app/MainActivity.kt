package com.planlock.app

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.planlock.design.LocalSharedScope
import com.planlock.design.LocalSharedVisibility
import com.planlock.design.LocalReducedMotion
import com.planlock.app.api.AuthSession
import com.planlock.app.api.PermissionGuidePolicy
import com.planlock.app.api.planLockPreferences
import com.planlock.app.engine.ExecutionFeedback
import com.planlock.app.engine.FeedbackKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.planlock.app.api.ApiClient
import com.planlock.app.api.TokenStore
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.planLockPreferences
import com.planlock.app.api.dto.DeviceRegisterRequest
import com.planlock.app.security.PermissionChecker
import com.planlock.app.security.PermissionIntent
import com.planlock.app.security.PermissionKind
import com.planlock.app.security.AccessibilityHealth
import com.planlock.app.sync.SyncClient
import com.planlock.app.record.RecordStore
import androidx.datastore.preferences.core.edit
import com.planlock.design.NavigationBar
import com.planlock.design.PlanLockTheme
import com.planlock.design.ThemeMode
import com.planlock.design.TopLevelDestination
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import com.planlock.feature.plan.PlanDetailScreen
import com.planlock.feature.plan.PlanEditScreen
import com.planlock.feature.plan.PlanScreen
import com.planlock.feature.plan.TodayScreen
import com.planlock.feature.settings.SettingsScreen
import com.planlock.feature.settings.PermissionSettingsScreen
import com.planlock.feature.stats.StatsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot { dark ->
                // 手动主题可能与系统相反，系统栏图标必须使用实际主题。
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()) { dark }
                )
            }
        }
    }
}

@Composable
private fun PermissionGuideDialog(
    missing: List<PermissionKind>,
    accessibilityHealth: AccessibilityHealth,
    onOpen: (PermissionKind) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("完成权限设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Text("部分权限未开启，相关计划能力可能无法正常工作。")
                missing.forEach { permission ->
                    val accessibilityStopped = permission == PermissionKind.ACCESSIBILITY &&
                        accessibilityHealth == AccessibilityHealth.CONFIGURED_NOT_RUNNING
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        androidx.compose.material3.Text(permission.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        androidx.compose.material3.Text(
                            if (accessibilityStopped) "无障碍服务已停止响应，请在系统设置中关闭后重新开启" else permission.consequence,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { onOpen(permission) }, modifier = Modifier.height(44.dp)) {
                            androidx.compose.material3.Text(if (accessibilityStopped) "去重新开启" else "去开启")
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("稍后") } }
    )
}

@Composable
private fun AppRoot(onThemeChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val preference = remember { ThemePreference(context) }
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(retry) {
        loadError = false
        try {
            TokenStore.restore(context.planLockPreferences)
            themeMode = preference.mode.first()
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = true }
    }
    val dark = themeMode.isDark(isSystemInDarkTheme())
    SideEffect { onThemeChanged(dark) }
    PlanLockTheme(themeMode = themeMode) {
        if (!loaded) {
            UIStatePanel(if (loadError) UiState.ERROR else UiState.LOADING,
                message = if (loadError) "本机偏好读取失败，请重试" else null,
                onAction = if (loadError) ({ retry++ }) else null)
        } else AppContent(themeMode, snackbar) { mode ->
            scope.launch {
                try { preference.save(mode); themeMode = mode }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { snackbar.showSnackbar("主题保存失败，请重试") }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@SuppressLint("InlinedApi")
@Composable
private fun AppContent(themeMode: ThemeMode, snackbar: SnackbarHostState, onThemeModeChange: (ThemeMode) -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val api = remember { ApiClient.create() }
    val syncClient = remember { SyncClient(api, RecordStore(context)) }
    var ready by remember { mutableStateOf(false) }
    var permissionStates by remember { mutableStateOf<Map<PermissionKind, Boolean>>(emptyMap()) }
    var permissionGuideDismissedAt by remember { mutableStateOf<Long?>(null) }
    var notificationRequestInFlight by remember { mutableStateOf(false) }
    val notificationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionStates = PermissionChecker.check(context)
        notificationRequestInFlight = false
    }

    LaunchedEffect(Unit) {
        val deviceUid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android-dev"
        try {
            if (!TokenStore.hasSession) {
                val response = api.deviceRegister(DeviceRegisterRequest(deviceUid, "Android", "android"))
                val auth = response.data
                if (response.code == 0 && auth != null) TokenStore.save(context.planLockPreferences,
                    AuthSession(auth.token, auth.userId, auth.deviceId, auth.guestUserId))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // 网络不可用时继续使用本地快照。
        }
        ready = true
    }

    LaunchedEffect(ready) {
        if (ready) {
            permissionStates = PermissionChecker.check(context)
            val preferences = context.planLockPreferences.data.first()
            permissionGuideDismissedAt = preferences[PlanLockPreferenceKeys.permissionGuideDismissedAt]
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                preferences[PlanLockPreferenceKeys.notificationPermissionRequested] != true
            ) {
                context.planLockPreferences.edit { it[PlanLockPreferenceKeys.notificationPermissionRequested] = true }
                notificationRequestInFlight = true
            }
        }
    }

    LaunchedEffect(notificationRequestInFlight) {
        if (notificationRequestInFlight) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    DisposableEffect(lifecycleOwner, ready) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionStates = PermissionChecker.check(context)
                // 注册到已恢复的生命周期时也会收到 ON_RESUME，覆盖 ready 后首次启动。
                if (ready) {
                    runCatching { ContextCompat.startForegroundService(context, Intent(context, ExecutionService::class.java)) }
                    lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { syncClient.pushPendingRecords() }
                    }
                }
                lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val dismissedAt = context.planLockPreferences.data.first()[PlanLockPreferenceKeys.permissionGuideDismissedAt]
                    withContext(Dispatchers.Main.immediate) { permissionGuideDismissedAt = dismissedAt }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { UIStatePanel(UiState.LOADING) }
        return
    }

    val navController = rememberNavController()
    val missingPermissions = permissionStates.filter { (kind, granted) -> kind.requiredForExecution && !granted }.keys.toList()
    val accessibilityHealth = PermissionChecker.accessibilityHealth(context)
    val showPermissionGuide = PermissionGuidePolicy.shouldShow(
        hasMissingPermissions = missingPermissions.isNotEmpty(),
        dismissedAt = permissionGuideDismissedAt,
        nowMillis = System.currentTimeMillis(),
        notificationRequestInFlight = notificationRequestInFlight
    )
    if (showPermissionGuide) {
        PermissionGuideDialog(
            missing = missingPermissions,
            accessibilityHealth = accessibilityHealth,
            onOpen = { PermissionIntent.open(context, it) },
            onDismiss = {
                val dismissedAt = System.currentTimeMillis()
                permissionGuideDismissedAt = dismissedAt
                lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        context.planLockPreferences.edit { it[PlanLockPreferenceKeys.permissionGuideDismissedAt] = dismissedAt }
                    }
                }
            }
        )
    }
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val currentRoute by rememberUpdatedState(route)
    val topRoutes = TopLevelDestination.entries.map { it.route }.toSet()

    LaunchedEffect(Unit) {
        ExecutionFeedback.events.collect { event ->
            if (event.kind == FeedbackKind.COMPLETED) {
                lifecycleOwner.lifecycleScope.launch { snackbar.showSnackbar("计划已完成") }
            } else if (event.kind == FeedbackKind.DND_PERMISSION_MISSING) {
                if (currentRoute != TopLevelDestination.SETTINGS.route) {
                    navController.navigate(TopLevelDestination.SETTINGS.route) { launchSingleTop = true }
                }
                lifecycleOwner.lifecycleScope.launch { snackbar.showSnackbar("免打扰未授权，自动勿扰未生效") }
            }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route in topRoutes || route == "assistant") {
                NavigationBar(
                    selectedRoute = route,
                    onSelect = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(TopLevelDestination.TODAY.route) { saveState = true }
                            launchSingleTop = true
                            // BUG-014：恢复 TODAY 的已保存栈会把刚弹出的计划页重新压回。
                        }
                    },
                    onAssistant = {
                        if (route != "assistant") navController.navigate("assistant") { launchSingleTop = true }
                    },
                )
            }
        }
    ) { innerPadding ->
        SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedScope provides this) {
        NavHost(navController, startDestination = TopLevelDestination.TODAY.route, modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(if (reducedMotion) 0 else 180)) + slideInHorizontally(tween(if (reducedMotion) 0 else 180)) { it / 12 } },
            exitTransition = { fadeOut(tween(if (reducedMotion) 0 else 180)) + slideOutHorizontally(tween(if (reducedMotion) 0 else 180)) { -it / 12 } },
            popEnterTransition = { fadeIn(tween(if (reducedMotion) 0 else 180)) + slideInHorizontally(tween(if (reducedMotion) 0 else 180)) { -it / 12 } },
            popExitTransition = { fadeOut(tween(if (reducedMotion) 0 else 180)) + slideOutHorizontally(tween(if (reducedMotion) 0 else 180)) { it / 12 } }
        ) {
            composable(TopLevelDestination.TODAY.route) {
                TodayScreen(
                    onCreate = { navController.navigate("edit/-1") },
                    onOpenDetail = { navController.navigate("detail/$it") }
                )
            }
            composable(TopLevelDestination.PLANS.route) {
                CompositionLocalProvider(LocalSharedVisibility provides this) {
                PlanScreen(
                    onCreate = { navController.navigate("edit/-1") },
                    onOpenDetail = { navController.navigate("detail/$it") }
                )
            }
            }
            composable(TopLevelDestination.STATS.route) { StatsScreen() }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(themeMode, onThemeModeChange, onOpenPermissions = { navController.navigate("settings/permissions") })
            }
            composable("settings/permissions") { PermissionSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("assistant") { AssistantScreen() }
            composable("edit/{planId}", arguments = listOf(navArgument("planId") { type = NavType.LongType })) { backStack ->
                val id = backStack.arguments?.getLong("planId")?.takeIf { it >= 0 }
                PlanEditScreen(
                    planId = id,
                    onDone = {
                        lifecycleOwner.lifecycleScope.launch { snackbar.showSnackbar("保存成功") }
                        navController.navigate(TopLevelDestination.PLANS.route) {
                            popUpTo(TopLevelDestination.TODAY.route) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("detail/{planId}", arguments = listOf(navArgument("planId") { type = NavType.LongType })) { backStack ->
                val id = backStack.arguments?.getLong("planId") ?: return@composable
                CompositionLocalProvider(LocalSharedVisibility provides this) {
                    PlanDetailScreen(planId = id, onEdit = { navController.navigate("edit/$id") }, onBack = { navController.popBackStack() })
                }
            }
        }
        }
        }
    }
}
