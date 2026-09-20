package com.example

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Receiver that provides Device Administration capabilities to allow
 * programmatic screen locking via DevicePolicyManager.lockNow().
 * Compatible with Android 8.0 (API 26) through the latest Android versions.
 */
class ScreenLockAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        ScreenLockRepository.updateAdminStatus(true)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        ScreenLockRepository.updateAdminStatus(false)
        // If service is running when admin is disabled, stop the service
        ScreenLockService.stop(context)
    }
}
