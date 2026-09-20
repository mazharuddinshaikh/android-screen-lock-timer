package com.example

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ScreenLockState(
    val isServiceRunning: Boolean = false,
    val intervalMinutes: Int = 15,
    val remainingSeconds: Int = 15 * 60,
    val totalLocksCount: Int = 0,
    val lastLockTimestamp: Long = 0L,
    val isDeviceAdminActive: Boolean = false,
    val hasNotificationPermission: Boolean = true
)

object ScreenLockRepository {
    private const val PREFS_NAME = "screen_lock_prefs"
    private const val KEY_INTERVAL = "interval_minutes"
    private const val KEY_LOCKS_COUNT = "total_locks_count"
    private const val KEY_LAST_LOCK = "last_lock_timestamp"

    private val _state = MutableStateFlow(ScreenLockState())
    val state: StateFlow<ScreenLockState> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        val p = prefs ?: return
        val savedInterval = p.getInt(KEY_INTERVAL, 15).coerceIn(1, 180)
        val savedLocks = p.getInt(KEY_LOCKS_COUNT, 0)
        val savedLastLock = p.getLong(KEY_LAST_LOCK, 0L)
        val isAdmin = checkAdminStatus(context)

        _state.update { current ->
            current.copy(
                intervalMinutes = savedInterval,
                remainingSeconds = if (current.isServiceRunning) current.remainingSeconds else savedInterval * 60,
                totalLocksCount = savedLocks,
                lastLockTimestamp = savedLastLock,
                isDeviceAdminActive = isAdmin
            )
        }
    }

    fun checkAdminStatus(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val component = ComponentName(context, ScreenLockAdminReceiver::class.java)
            val active = dpm?.isAdminActive(component) == true
            _state.update { it.copy(isDeviceAdminActive = active) }
            active
        } catch (_: Exception) {
            false
        }
    }

    fun updateAdminStatus(isActive: Boolean) {
        _state.update { it.copy(isDeviceAdminActive = isActive) }
    }

    fun updateNotificationPermission(granted: Boolean) {
        _state.update { it.copy(hasNotificationPermission = granted) }
    }

    fun setIntervalMinutes(context: Context, minutes: Int) {
        val clamped = minutes.coerceIn(1, 180)
        prefs?.edit()?.putInt(KEY_INTERVAL, clamped)?.apply()
        _state.update { current ->
            current.copy(
                intervalMinutes = clamped,
                remainingSeconds = if (!current.isServiceRunning) clamped * 60 else current.remainingSeconds.coerceAtMost(clamped * 60)
            )
        }
    }

    fun setServiceRunning(running: Boolean) {
        _state.update { current ->
            current.copy(
                isServiceRunning = running,
                remainingSeconds = if (running) current.intervalMinutes * 60 else current.intervalMinutes * 60
            )
        }
    }

    fun updateCountdown(seconds: Int) {
        _state.update { it.copy(remainingSeconds = seconds) }
    }

    fun recordLockEvent(context: Context) {
        val now = System.currentTimeMillis()
        val newCount = _state.value.totalLocksCount + 1
        prefs?.edit()
            ?.putInt(KEY_LOCKS_COUNT, newCount)
            ?.putLong(KEY_LAST_LOCK, now)
            ?.apply()

        _state.update {
            it.copy(
                totalLocksCount = newCount,
                lastLockTimestamp = now,
                remainingSeconds = it.intervalMinutes * 60
            )
        }
    }

    fun resetStats(context: Context) {
        prefs?.edit()
            ?.putInt(KEY_LOCKS_COUNT, 0)
            ?.putLong(KEY_LAST_LOCK, 0L)
            ?.apply()

        _state.update {
            it.copy(
                totalLocksCount = 0,
                lastLockTimestamp = 0L
            )
        }
    }
}
