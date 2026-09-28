package com.planlock.app.security

import android.app.admin.DeviceAdminReceiver

/**
 * 设备管理器接收者：防卸载 / 防强停（需用户在系统设置中激活）。
 */
class PlanLockDeviceAdminReceiver : DeviceAdminReceiver()
