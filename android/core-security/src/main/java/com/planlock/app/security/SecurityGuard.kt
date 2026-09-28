package com.planlock.app.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * 防绕过与备用密码（阶段2 基础版）：
 * - 设备管理器：校验是否激活（防卸载 / 防强停前置条件）
 * - 备用密码：本地复杂度校验（解锁行为由 record 模块上报）
 */
class SecurityGuard(private val context: Context) {

    private val dpm: DevicePolicyManager
        get() = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** 设备管理器是否已激活。 */
    fun isAdminActive(component: ComponentName): Boolean = dpm.isAdminActive(component)

    /**
     * 备用密码复杂度校验：要求不高（类似锁屏密码），4-16 位。
     * 哈希校验与解锁记录：阶段2 预留（见 TODO，接后端 /me/backup-password 与 record 模块）。
     */
    fun validateBackupPassword(input: String?): Boolean {
        return input != null && input.length in 4..16
    }
}
