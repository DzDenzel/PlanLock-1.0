package com.planlock.app.api

import com.planlock.app.api.mock.MockApi
import com.planlock.core.api.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * REST 客户端工厂。开发期 useMock = true；联调期切换为 false。
 */
object ApiClient {

    private val BASE_URL: String = BuildConfig.API_BASE_URL

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun create(useMock: Boolean = BuildConfig.USE_MOCK): PlanLockApi {
        if (useMock) {
            return MockApi()
        }
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(authorizedRequest(chain.request()))
            }
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PlanLockApi::class.java)
    }
}

internal fun authorizedRequest(request: Request): Request {
    val token = TokenStore.token
    return if (token.isNullOrBlank()) request else request.newBuilder().header("Authorization", "Bearer $token").build()
}
