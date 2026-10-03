package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.data.model.CompressionPreset
import com.example.data.model.LockType
import com.example.data.model.PageSizePreset
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * App settings.
 *
 * Security:
 *  - Enum settings are parsed defensively: a stored value that no longer exists (renamed / removed enum
 *    constant after an update) falls back to the default instead of crashing the app at startup.
 *  - The PIN is never stored in clear text: PBKDF2 (random salt) hash only, compared in constant time.
 *    A legacy clear-text PIN is migrated automatically on first use.
 *  - Wrong PIN attempts are rate-limited (5 free attempts, then 30 s doubling up to 15 min).
 */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    init {
        migrateLegacyPin()
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T {
        val raw = prefs.getString(key, null) ?: return default
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }

    var lockType: LockType
        get() = readEnum(KEY_LOCK_TYPE, LockType.NONE)
        set(value) = prefs.edit().putString(KEY_LOCK_TYPE, value.name).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", "System") ?: "System"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var pdfPageSize: PageSizePreset
        get() = readEnum("pdf_page_size", PageSizePreset.A4)
        set(value) = prefs.edit().putString("pdf_page_size", value.name).apply()

    var pdfCompression: CompressionPreset
        get() = readEnum("pdf_compression", CompressionPreset.HIGH)
        set(value) = prefs.edit().putString("pdf_compression", value.name).apply()

    var ocrLanguage: String
        get() = prefs.getString("ocr_language", "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString("ocr_language", value).apply()

    // ------------------------------------------------------------------ PIN

    /** True when a PIN hash exists. */
    val hasPin: Boolean get() = prefs.contains(KEY_PIN_HASH) && prefs.contains(KEY_PIN_SALT)

    /**
     * Kept for source compatibility. Reading NEVER returns the PIN (it is not stored); writing sets it.
     */
    @Deprecated("The PIN is stored hashed. Use setPin / verifyPin / hasPin.")
    var userPin: String
        get() = ""
        set(value) = setPin(value)

    fun setPin(pin: String) {
        if (pin.isBlank()) {
            clearPin()
            return
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_PIN_SALT, b64(salt))
            .putString(KEY_PIN_HASH, b64(hash(pin, salt)))
            .remove(KEY_LEGACY_PIN)
            .putInt(KEY_PIN_FAILS, 0)
            .putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).remove(KEY_LEGACY_PIN)
            .putInt(KEY_PIN_FAILS, 0).putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    /** Milliseconds before another PIN attempt is allowed (0 = allowed now). */
    fun pinLockoutRemainingMs(): Long =
        (prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Verifies the PIN with rate limiting. Returns false while locked out, without checking. */
    fun verifyPin(entered: String): Boolean {
        if (!hasPin) return false
        if (pinLockoutRemainingMs() > 0L) return false
        val salt = unb64(prefs.getString(KEY_PIN_SALT, null)) ?: return false
        val expected = unb64(prefs.getString(KEY_PIN_HASH, null)) ?: return false
        val ok = MessageDigest.isEqual(hash(entered, salt), expected)
        if (ok) {
            prefs.edit().putInt(KEY_PIN_FAILS, 0).putLong(KEY_PIN_LOCKOUT_UNTIL, 0L).apply()
        } else {
            val fails = prefs.getInt(KEY_PIN_FAILS, 0) + 1
            val editor = prefs.edit().putInt(KEY_PIN_FAILS, fails)
            if (fails >= FREE_ATTEMPTS) {
                val step = (fails - FREE_ATTEMPTS).coerceAtMost(5)
                val delay = min(BASE_LOCKOUT_MS shl step, MAX_LOCKOUT_MS)
                editor.putLong(KEY_PIN_LOCKOUT_UNTIL, System.currentTimeMillis() + delay)
            }
            editor.apply()
        }
        return ok
    }

    private fun migrateLegacyPin() {
        val legacy = prefs.getString(KEY_LEGACY_PIN, null) ?: return
        if (legacy.isNotBlank() && !hasPin) setPin(legacy) else prefs.edit().remove(KEY_LEGACY_PIN).apply()
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return try {
            // PBKDF2WithHmacSHA1 is available on every supported API level (minSdk 24).
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String?): ByteArray? = s?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

    // ------------------------------------------------------------------ updates

    /** Last automatic update check (wall clock, ms). */
    var lastUpdateCheckAt: Long
        get() = prefs.getLong("last_update_check_at", 0L)
        set(value) = prefs.edit().putLong("last_update_check_at", value).apply()

    /** Version the user chose to skip ("Later" + ignore). */
    var ignoredUpdateVersion: String
        get() = prefs.getString("ignored_update_version", "") ?: ""
        set(value) = prefs.edit().putString("ignored_update_version", value).apply()

    // ------------------------------------------------------------------ camera (persisted user choices)
    // Every capture setting the user changes is kept for all later sessions until the user changes it.

    var cameraAutoCapture: Boolean
        get() = prefs.getBoolean("cam_auto_capture", true)
        set(value) = prefs.edit().putBoolean("cam_auto_capture", value).apply()

    /** CameraX ImageCapture flash mode: 0 = AUTO, 1 = ON, 2 = OFF (default). */
    var cameraFlashMode: Int
        get() = prefs.getInt("cam_flash_mode", 2).takeIf { it in 0..2 } ?: 2
        set(value) = prefs.edit().putInt("cam_flash_mode", value).apply()

    var cameraTorch: Boolean
        get() = prefs.getBoolean("cam_torch", false)
        set(value) = prefs.edit().putBoolean("cam_torch", value).apply()

    var cameraGrid: Boolean
        get() = prefs.getBoolean("cam_grid", false)
        set(value) = prefs.edit().putBoolean("cam_grid", value).apply()

    var cameraFrontFacing: Boolean
        get() = prefs.getBoolean("cam_front", false)
        set(value) = prefs.edit().putBoolean("cam_front", value).apply()

    /** Last scan mode chosen by the user in the camera (ScanCameraMode name). */
    var cameraLastMode: String
        get() = prefs.getString("cam_last_mode", "DOCUMENT") ?: "DOCUMENT"
        set(value) = prefs.edit().putString("cam_last_mode", value).apply()

    /**
     * Capture engine for normal scans: Google ML Kit Document Scanner (default, most accurate) or the
     * built-in camera. ID card / passport always use the built-in camera (dedicated guides).
     */
    var scanEngine: String
        get() = prefs.getString("scan_engine", SCAN_ENGINE_GOOGLE)
            ?.takeIf { it == SCAN_ENGINE_GOOGLE || it == SCAN_ENGINE_BUILT_IN } ?: SCAN_ENGINE_GOOGLE
        set(value) = prefs.edit().putString("scan_engine", value).apply()

    /** Last version announced by a notification (one notification per version). */
    var lastNotifiedUpdateVersion: String
        get() = prefs.getString("last_notified_update_version", "") ?: ""
        set(value) = prefs.edit().putString("last_notified_update_version", value).apply()

    /** The notification permission is requested once; the user can change it later in system settings. */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean("notification_permission_asked", false)
        set(value) = prefs.edit().putBoolean("notification_permission_asked", value).apply()

    companion object {
        const val SCAN_ENGINE_GOOGLE = "GOOGLE"
        const val SCAN_ENGINE_BUILT_IN = "BUILT_IN"
        private const val KEY_LOCK_TYPE = "lock_type"
        private const val KEY_LEGACY_PIN = "user_pin"
        private const val KEY_PIN_HASH = "user_pin_hash"
        private const val KEY_PIN_SALT = "user_pin_salt"
        private const val KEY_PIN_FAILS = "user_pin_fails"
        private const val KEY_PIN_LOCKOUT_UNTIL = "user_pin_lockout_until"
        private const val PBKDF2_ITERATIONS = 20_000
        private const val FREE_ATTEMPTS = 5
        private const val BASE_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 15 * 60_000L
    }
}
