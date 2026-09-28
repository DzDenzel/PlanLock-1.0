package com.planlock.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Path.Companion.toPath
import okio.FileSystem
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SessionPersistenceTest {
    @Test fun identitySurvivesReopenAndFailedWritesDoNotPublish() = runBlocking {
        val testRoot = File("build/session-tests").apply { mkdirs() }
        val directory = Files.createTempDirectory(testRoot.toPath(), "planlock-session").toFile()
        val file = File(directory, "test.preferences_pb")
        var job = SupervisorJob()
        // Windows JVM 的 File.renameTo 不能原子覆盖现有文件；使用官方 Okio 存储运行落盘测试。
        // Android 默认 FileStorage 另由模拟器迁移/重启测试覆盖。
        fun open() = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.absolutePath.toPath() }),
            scope = CoroutineScope(Dispatchers.IO + job)
        )
        var store = open()
        try {
            TokenStore.restore(store)
            assertFalse(TokenStore.hasSession)
            val request = Request.Builder().url("https://example.invalid/").build()
            assertNull(authorizedRequest(request).header("Authorization"))
            val theme = stringPreferencesKey("theme_mode")
            assertTrue(store.data.first()[PlanLockPreferenceKeys.basicToolsEnabled] ?: true)
            store.edit {
                it[theme] = "dark"
                it[PlanLockPreferenceKeys.basicToolsEnabled] = false
            }
            TokenStore.save(store, AuthSession("test-only-token", 42, 8, 7, true))
            job.cancelAndJoin()
            job = SupervisorJob()
            store = open()
            TokenStore.restore(store)
            assertTrue(TokenStore.signedIn)
            assertEquals(42L, TokenStore.userId)
            assertEquals(8L, TokenStore.deviceId)
            assertEquals(7L, TokenStore.guestUserId)
            assertEquals("dark", store.data.first()[theme])
            assertEquals(false, store.data.first()[PlanLockPreferenceKeys.basicToolsEnabled])
            assertEquals("Bearer test-only-token", authorizedRequest(request).header("Authorization"))
            val failing = object : DataStore<Preferences> {
                override val data = store.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("disk unavailable")
            }
            try { TokenStore.save(failing, AuthSession("replacement", 99, 9)); fail("Must report write failure") }
            catch (_: IOException) { assertEquals(42L, TokenStore.userId) }
            try { TokenStore.save(store, AuthSession(" ", 1, 1)); fail("Blank token accepted") }
            catch (_: IllegalArgumentException) { assertTrue(TokenStore.signedIn) }
        } finally {
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
