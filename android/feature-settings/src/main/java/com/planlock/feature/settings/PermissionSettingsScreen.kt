package com.planlock.feature.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.planLockPreferences
import com.planlock.app.security.AccessibilityHealth
import com.planlock.app.security.PermissionChecker
import com.planlock.app.security.PermissionIntent
import com.planlock.app.security.PermissionKind
import com.planlock.design.PageAtmosphere
import com.planlock.design.planLockAtmosphere
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var states by remember { mutableStateOf(PermissionChecker.check(context)) }
    var locationRequested by remember { mutableStateOf(false) }
    var requestMarkerLoaded by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        states = PermissionChecker.check(context)
    }

    fun requestLocation() {
        locationRequested = true
        scope.launch {
            context.planLockPreferences.edit { it[PlanLockPreferenceKeys.locationPermissionRequested] = true }
        }
        locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    LaunchedEffect(Unit) {
        locationRequested = context.planLockPreferences.data.first()[PlanLockPreferenceKeys.locationPermissionRequested] ?: false
        requestMarkerLoaded = true
        states = PermissionChecker.check(context)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) states = PermissionChecker.check(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationState = locationPermissionUiState(
        granted = states[PermissionKind.LOCATION] == true,
        requestedBefore = locationRequested,
        shouldShowRationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) == true,
    )

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.QUIET),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("权限与系统能力") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        PermissionKind.entries.forEachIndexed { index, kind ->
                            if (index > 0) HorizontalDivider()
                            val stopped = kind == PermissionKind.ACCESSIBILITY &&
                                PermissionChecker.accessibilityHealth(context) == AccessibilityHealth.CONFIGURED_NOT_RUNNING
                            val status = when {
                                stopped -> "重新开启"
                                kind == PermissionKind.LOCATION -> when (locationState) {
                                    LocationPermissionUiState.ENABLED -> "已开启"
                                    LocationPermissionUiState.ASK -> "去开启"
                                    LocationPermissionUiState.ASK_AGAIN -> "再次申请"
                                    LocationPermissionUiState.APP_SETTINGS -> "前往设置"
                                }
                                states[kind] == true -> "已开启"
                                else -> "去开启"
                            }
                            PermissionSettingRow(
                                title = kind.title,
                                description = if (stopped) "服务已停止响应，需要重新开启" else kind.consequence,
                                status = status,
                                enabled = states[kind] == true && !stopped,
                                onClick = {
                                    if (kind != PermissionKind.LOCATION) {
                                        PermissionIntent.open(context, kind)
                                    } else if (requestMarkerLoaded) {
                                        when (locationState) {
                                            LocationPermissionUiState.ASK,
                                            LocationPermissionUiState.ASK_AGAIN -> requestLocation()
                                            LocationPermissionUiState.APP_SETTINGS,
                                            LocationPermissionUiState.ENABLED -> context.startActivity(PermissionIntent.appDetails(context))
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
