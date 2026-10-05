package cc.eu.jeanwenzel.Noctreed.data

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 授权辅助：以 shell UID 执行 pm grant，为本应用授予
 * WRITE_SECURE_SETTINGS，再由 AccessibilityGranter 写入系统设置。
 */
object ShizukuGranter {
    private const val TAG = "ShizukuGranter"
    const val REQUEST_CODE = 1001

    fun available(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun granted(): Boolean = available() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun shouldShowRationale(): Boolean = try {
        Shizuku.shouldShowRequestPermissionRationale()
    } catch (t: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed", t)
        }
    }

    /** 以 shell 身份授予 WRITE_SECURE_SETTINGS；返回是否成功。 */
    fun grantWriteSecureSettings(): Boolean {
        if (!granted()) return false
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(
                null,
                arrayOf(
                    "pm", "grant",
                    "cc.eu.jeanwenzel.Noctreed",
                    "android.permission.WRITE_SECURE_SETTINGS"
                ),
                null,
                null
            ) as Process
            p.waitFor() == 0
        } catch (t: Throwable) {
            Log.w(TAG, "grant via shizuku failed", t)
            false
        }
    }
}
