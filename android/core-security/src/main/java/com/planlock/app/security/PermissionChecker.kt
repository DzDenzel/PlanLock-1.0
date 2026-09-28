package com.planlock.app.security

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

enum class PermissionKind(
    val title: String,
    val consequence: String,
    val runtime: Boolean = false,
    val requiredForExecution: Boolean = true,
) {
    USAGE_STATS("使用情况访问", "用于识别当前前台应用并执行限制"),
    ACCESSIBILITY("无障碍服务", "用于在打开受限应用时及时拦截"),
    DEVICE_ADMIN("设备管理器", "用于降低执行期间被强行停止的风险"),
    DND("勿扰权限", "用于执行时保留闹钟并减少通知干扰"),
    NOTIFICATIONS("通知权限", "用于显示计划执行状态与异常提醒"),
    LOCATION("定位", "用于自动获取当前位置的天气", runtime = true, requiredForExecution = false),
}

object PermissionChecker {
    fun check(context: Context): Map<PermissionKind, Boolean> = PermissionKind.entries.associateWith { kind ->
        when (kind) {
            PermissionKind.USAGE_STATS -> hasUsageStats(context)
            PermissionKind.ACCESSIBILITY -> accessibilityHealth(context) == AccessibilityHealth.HEALTHY
            PermissionKind.DEVICE_ADMIN -> {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.isAdminActive(deviceAdminComponent(context))
            }
            PermissionKind.DND -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.isNotificationPolicyAccessGranted
            }
            PermissionKind.NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 ||
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            PermissionKind.LOCATION -> context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasUsageStats(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun accessibilityHealth(context: Context): AccessibilityHealth {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val expected = ComponentName(context, "com.planlock.app.engine.AccessibilityInterceptorService").flattenToString()
        val configured = enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        return AccessibilityHealthEvaluator.evaluate(
            configured = configured,
            signal = AccessibilityHealthStore(context).read(),
            nowMillis = System.currentTimeMillis(),
        )
    }

    internal fun deviceAdminComponent(context: Context): ComponentName = ComponentName(context, PlanLockDeviceAdminReceiver::class.java)
}

object PermissionIntent {
    fun forKind(context: Context, kind: PermissionKind): Intent = when (kind) {
        PermissionKind.USAGE_STATS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
        PermissionKind.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PermissionKind.DEVICE_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, PermissionChecker.deviceAdminComponent(context))
        PermissionKind.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        PermissionKind.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        PermissionKind.LOCATION -> appDetails(context)
    }

    fun appDetails(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:${context.packageName}"),
    )

    fun open(context: Context, kind: PermissionKind) {
        val intent = forKind(context, kind)
        runCatching { context.startActivity(intent) }.onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}
