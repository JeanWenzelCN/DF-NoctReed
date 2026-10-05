package cc.eu.jeanwenzel.Noctreed.data

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * 无障碍永久授权辅助：
 * 持有 WRITE_SECURE_SETTINGS（经 adb 或 Shizuku/Sui 授予）时，
 * 直接把本应用的无障碍服务写入系统设置，重启后依然生效。
 */
object AccessibilityGranter {

    private const val TAG = "AccessibilityGranter"
    private const val SERVICE =
        "cc.eu.jeanwenzel.Noctreed/cc.eu.jeanwenzel.Noctreed.service.HarmonicaAccessibilityService"

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** 已持有权限时尝试写入；返回是否成功（已开启视为成功）。 */
    fun tryEnable(context: Context): Boolean {
        if (!hasWriteSecureSettings(context)) return false
        return try {
            val cr = context.contentResolver
            val enabled = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (!enabled.split(':').any { it.equals(SERVICE, ignoreCase = true) }) {
                val merged = if (enabled.isEmpty()) SERVICE else "$enabled:$SERVICE"
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged
                )
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "enable accessibility failed", t)
            false
        }
    }
}
