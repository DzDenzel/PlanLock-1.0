package com.planlock.app.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PlanLockPreferenceKeys {
    val basicToolsEnabled = booleanPreferencesKey("basic_tools_enabled")
    val pluginsEnabled = booleanPreferencesKey("plugins_enabled")
    val cachedContinuousDays = intPreferencesKey("cached_continuous_days")
    val cachedTodayUnlocks = intPreferencesKey("cached_today_unlocks")
    val dndPreviousFilter = intPreferencesKey("dnd_previous_filter")
    val dndPlanId = longPreferencesKey("dnd_plan_id")
    val dndAppliedAt = longPreferencesKey("dnd_applied_at")
    val permissionGuideDismissedAt = longPreferencesKey("perm_guide_dismissed_at")
    val notificationPermissionRequested = booleanPreferencesKey("notification_permission_requested")
    val locationPermissionRequested = booleanPreferencesKey("location_permission_requested")
}

// 全进程共用一份实例，主题迁移和身份更新不会相互覆盖。
val Context.planLockPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "planlock",
    produceMigrations = { listOf(SharedPreferencesMigration(it, "appearance", setOf("theme_mode"))) }
)

data class AuthSession(
    val token: String? = null,
    val userId: Long? = null,
    val deviceId: Long? = null,
    val guestUserId: Long? = null,
    val signedIn: Boolean = false
) {
    val usable: Boolean get() = !token.isNullOrBlank() && userId != null && deviceId != null
}

object TokenStore {
    private val lock = Mutex()
    @Volatile private var session = AuthSession()
    val token get() = session.token
    val userId get() = session.userId
    val deviceId get() = session.deviceId
    val guestUserId get() = session.guestUserId
    val signedIn get() = session.signedIn
    val hasSession get() = session.usable

    private val tokenKey = stringPreferencesKey("token")
    private val userKey = longPreferencesKey("user_id")
    private val deviceKey = longPreferencesKey("device_id")
    private val guestKey = longPreferencesKey("guest_user_id")
    private val signedInKey = booleanPreferencesKey("signed_in")

    suspend fun restore(store: DataStore<Preferences>) = lock.withLock {
        val p = store.data.first()
        val saved = AuthSession(p[tokenKey], p[userKey], p[deviceKey], p[guestKey], p[signedInKey] ?: false)
        session = if (saved.usable) saved else AuthSession()
    }

    suspend fun save(store: DataStore<Preferences>, value: AuthSession) = lock.withLock {
        require(value.usable) { "不完整的登录身份" }
        store.edit { p ->
            p[tokenKey] = checkNotNull(value.token)
            p[userKey] = checkNotNull(value.userId)
            p[deviceKey] = checkNotNull(value.deviceId)
            value.guestUserId?.let { p[guestKey] = it } ?: p.remove(guestKey)
            p[signedInKey] = value.signedIn
        }
        // 成功落盘后一次性发布，避免请求看到半份身份或虚假的登录成功。
        session = value
    }
}
