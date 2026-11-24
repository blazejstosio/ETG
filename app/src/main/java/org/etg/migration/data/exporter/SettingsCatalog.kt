package org.etg.migration.data.exporter

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import org.etg.migration.R
import org.etg.migration.data.shizuku.ShizukuBridge
import org.etg.migration.model.IssueReason
import java.util.Locale

object SettingsCatalog {
    enum class Ns(val shell: String) { SYSTEM("system"), SECURE("secure"), GLOBAL("global"), SPECIAL("special") }

    enum class Group(val titleRes: Int) {
        APPEARANCE(R.string.setg_appearance),
        SOUNDS(R.string.setg_sounds),
        DISPLAY(R.string.setg_display),
        ANIMATIONS(R.string.setg_animations),
        NAVIGATION(R.string.setg_navigation),
        KEYBOARD(R.string.setg_keyboard),
        PRIVACY(R.string.setg_privacy),
        ACCESSIBILITY(R.string.setg_accessibility)
    }

    enum class Kind { BOOL, TIMEOUT_MS, SCALE, BRIGHTNESS_MODE, NIGHT_MODE, NAV_MODE, TIME_FORMAT, IME, DNS_MODE, TEXT, DAY_TIME_MS, NIGHT_DISPLAY_AUTO, COLORS, BOLD_TEXT }

    data class Def(val ns: Ns, val key: String, val group: Group, val labelRes: Int, val kind: Kind) {
        val id: String get() = if (ns == Ns.SYSTEM) key else "${ns.shell}:$key"
        val needsShizuku: Boolean get() = ns != Ns.SYSTEM
    }

    const val NIGHT_MODE_KEY = "night_mode"

    val DEFS: List<Def> = listOf(
        Def(Ns.SPECIAL, NIGHT_MODE_KEY, Group.APPEARANCE, R.string.set_night_mode, Kind.NIGHT_MODE),
        Def(Ns.SECURE, "theme_customization_overlay_packages", Group.APPEARANCE, R.string.set_theme_colors, Kind.COLORS),

        Def(Ns.SYSTEM, Settings.System.HAPTIC_FEEDBACK_ENABLED, Group.SOUNDS, R.string.set_haptic, Kind.BOOL),
        Def(Ns.SYSTEM, Settings.System.SOUND_EFFECTS_ENABLED, Group.SOUNDS, R.string.set_touch_sounds, Kind.BOOL),
        Def(Ns.SYSTEM, Settings.System.DTMF_TONE_WHEN_DIALING, Group.SOUNDS, R.string.set_dtmf, Kind.BOOL),
        Def(Ns.SYSTEM, "vibrate_when_ringing", Group.SOUNDS, R.string.set_vibrate_ringing, Kind.BOOL),
        Def(Ns.SYSTEM, "lockscreen_sounds_enabled", Group.SOUNDS, R.string.set_lock_sounds, Kind.BOOL),

        Def(Ns.SYSTEM, Settings.System.SCREEN_OFF_TIMEOUT, Group.DISPLAY, R.string.set_screen_timeout, Kind.TIMEOUT_MS),
        Def(Ns.SYSTEM, Settings.System.SCREEN_BRIGHTNESS_MODE, Group.DISPLAY, R.string.set_brightness_mode, Kind.BRIGHTNESS_MODE),
        Def(Ns.SYSTEM, Settings.System.ACCELEROMETER_ROTATION, Group.DISPLAY, R.string.set_auto_rotate, Kind.BOOL),
        Def(Ns.SYSTEM, Settings.System.FONT_SCALE, Group.DISPLAY, R.string.set_font_scale, Kind.SCALE),
        Def(Ns.SECURE, "night_display_auto_mode", Group.DISPLAY, R.string.set_night_light_schedule, Kind.NIGHT_DISPLAY_AUTO),
        Def(Ns.SECURE, "night_display_custom_start_time", Group.DISPLAY, R.string.set_night_light_start, Kind.DAY_TIME_MS),
        Def(Ns.SECURE, "night_display_custom_end_time", Group.DISPLAY, R.string.set_night_light_end, Kind.DAY_TIME_MS),

        Def(Ns.GLOBAL, Settings.Global.WINDOW_ANIMATION_SCALE, Group.ANIMATIONS, R.string.set_anim_window, Kind.SCALE),
        Def(Ns.GLOBAL, Settings.Global.TRANSITION_ANIMATION_SCALE, Group.ANIMATIONS, R.string.set_anim_transition, Kind.SCALE),
        Def(Ns.GLOBAL, Settings.Global.ANIMATOR_DURATION_SCALE, Group.ANIMATIONS, R.string.set_anim_duration, Kind.SCALE),

        Def(Ns.SECURE, "navigation_mode", Group.NAVIGATION, R.string.set_nav_mode, Kind.NAV_MODE),

        Def(Ns.SECURE, Settings.Secure.DEFAULT_INPUT_METHOD, Group.KEYBOARD, R.string.set_default_keyboard, Kind.IME),
        Def(Ns.SECURE, "spell_checker_enabled", Group.KEYBOARD, R.string.set_spell_checker, Kind.BOOL),
        Def(Ns.SYSTEM, Settings.System.TIME_12_24, Group.KEYBOARD, R.string.set_time_format, Kind.TIME_FORMAT),
        Def(Ns.GLOBAL, Settings.Global.AUTO_TIME, Group.KEYBOARD, R.string.set_auto_time, Kind.BOOL),
        Def(Ns.GLOBAL, Settings.Global.AUTO_TIME_ZONE, Group.KEYBOARD, R.string.set_auto_time_zone, Kind.BOOL),

        Def(Ns.GLOBAL, "private_dns_specifier", Group.PRIVACY, R.string.set_private_dns_host, Kind.TEXT),
        Def(Ns.GLOBAL, "private_dns_mode", Group.PRIVACY, R.string.set_private_dns_mode, Kind.DNS_MODE),
        Def(Ns.SYSTEM, "show_password", Group.PRIVACY, R.string.set_show_password, Kind.BOOL),
        Def(Ns.SECURE, "lock_screen_show_notifications", Group.PRIVACY, R.string.set_lock_notifications, Kind.BOOL),
        Def(Ns.SECURE, "lock_screen_allow_private_notifications", Group.PRIVACY, R.string.set_lock_private_notifications, Kind.BOOL),

        Def(Ns.SECURE, "font_weight_adjustment", Group.ACCESSIBILITY, R.string.set_bold_text, Kind.BOLD_TEXT),
        Def(Ns.SECURE, "high_text_contrast_enabled", Group.ACCESSIBILITY, R.string.set_high_contrast_text, Kind.BOOL),
        Def(Ns.SECURE, "accessibility_display_inversion_enabled", Group.ACCESSIBILITY, R.string.set_color_inversion, Kind.BOOL)
    )

    private val byId = DEFS.associateBy { it.id }

    fun def(id: String): Def? = byId[id]

    fun isValid(def: Def, value: String): Boolean = when (def.kind) {
        Kind.BOOL -> value == "0" || value == "1"
        Kind.SCALE -> value.toFloatOrNull()?.let { it in 0f..10f } == true
        Kind.TIMEOUT_MS -> value.toLongOrNull()?.let { it in 5_000L..Int.MAX_VALUE } == true
        Kind.BRIGHTNESS_MODE, Kind.NAV_MODE, Kind.NIGHT_DISPLAY_AUTO -> value.toIntOrNull()?.let { it in 0..2 } == true
        Kind.TIME_FORMAT -> value == "12" || value == "24"
        Kind.DAY_TIME_MS -> value.toLongOrNull()?.let { it in 0L until 86_400_000L } == true
        Kind.BOLD_TEXT -> value.toIntOrNull()?.let { it in 0..500 } == true
        Kind.NIGHT_MODE -> value in setOf("yes", "no", "auto")
        Kind.DNS_MODE -> value in setOf("off", "opportunistic", "hostname")
        Kind.IME -> value.contains('/') && value.length < 300
        Kind.COLORS -> value.startsWith("{") && value.length < 4_000
        Kind.TEXT -> value.isNotBlank() && value.length < 255 && !value.contains('\n')
    }

    fun readAll(context: Context): Map<String, String> {
        val cr = context.contentResolver
        val out = linkedMapOf<String, String>()
        val missing = mutableListOf<Def>()
        for (def in DEFS) {
            val v = runCatching {
                when (def.ns) {
                    Ns.SYSTEM -> Settings.System.getString(cr, def.key)
                    Ns.SECURE -> Settings.Secure.getString(cr, def.key)
                    Ns.GLOBAL -> Settings.Global.getString(cr, def.key)
                    Ns.SPECIAL -> readSpecial(context, def)
                }
            }.getOrNull()
            if (v != null && isValid(def, v)) out[def.id] = v else if (def.ns != Ns.SPECIAL) missing += def
        }
        if (missing.isNotEmpty() && ShizukuBridge.status(context) == ShizukuBridge.Status.READY) {
            runCatching {
                ShizukuBridge.readSettings(missing.map { it.ns.shell to it.key })
            }.getOrNull()?.forEach { (nsKey, value) ->
                val def = missing.firstOrNull { it.ns.shell == nsKey.first && it.key == nsKey.second }
                if (def != null && isValid(def, value)) out[def.id] = value
            }
        }

        return DEFS.mapNotNull { d -> out[d.id]?.let { d.id to it } }.toMap(LinkedHashMap())
    }

    private fun readSpecial(context: Context, def: Def): String? = when (def.key) {
        NIGHT_MODE_KEY -> when (context.getSystemService(android.app.UiModeManager::class.java)?.nightMode) {
            android.app.UiModeManager.MODE_NIGHT_YES -> "yes"
            android.app.UiModeManager.MODE_NIGHT_NO -> "no"
            android.app.UiModeManager.MODE_NIGHT_AUTO -> "auto"
            else -> null
        }
        else -> null
    }

    fun applyWithShizuku(context: Context, def: Def, value: String): Pair<IssueReason, String>? {
        if (!isValid(def, value)) return IssueReason.CORRUPTED to value.take(40)
        return when {
            def.ns == Ns.SPECIAL && def.key == NIGHT_MODE_KEY -> {
                ShizukuBridge.exec("cmd", "uimode", "night", value); null
            }
            def.kind == Kind.IME -> {
                val pkg = value.substringBefore('/')
                val installed = runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
                if (!installed) return IssueReason.APP_NOT_INSTALLED to imeName(value)
                ShizukuBridge.exec("ime", "enable", value)
                ShizukuBridge.exec("ime", "set", value)
                null
            }
            def.kind == Kind.NAV_MODE -> {
                val overlay = when (value) {
                    "0" -> "threebutton"
                    "1" -> "twobutton"
                    else -> "gestural"
                }
                ShizukuBridge.exec(
                    "cmd", "overlay", "enable-exclusive", "--category",
                    "com.android.internal.systemui.navbar.$overlay"
                )
                null
            }
            else -> if (ShizukuBridge.putSetting(def.ns.shell, def.key, value)) null else IssueReason.REFUSED_BY_SYSTEM to ""
        }
    }

    fun formatValue(res: Resources, def: Def, raw: String): String = when (def.kind) {
        Kind.BOOL -> res.getString(if (raw == "1") R.string.setv_on else R.string.setv_off)
        Kind.SCALE -> {
            val f = raw.toFloatOrNull()
            when {
                f == null -> raw
                f == 0f && def.group == Group.ANIMATIONS -> res.getString(R.string.setv_anim_off)
                else -> String.format(Locale.getDefault(), "%.2f", f).trimEnd('0').trimEnd(',', '.') + "×"
            }
        }
        Kind.TIMEOUT_MS -> raw.toLongOrNull()?.let { ms ->
            val s = ms / 1000
            when {
                s >= 86_400 -> res.getString(R.string.setv_never)
                s >= 60 -> res.getString(R.string.setv_minutes, (s / 60).toInt())
                else -> res.getString(R.string.setv_seconds, s.toInt())
            }
        } ?: raw
        Kind.BRIGHTNESS_MODE -> res.getString(if (raw == "1") R.string.setv_brightness_auto else R.string.setv_brightness_manual)
        Kind.NIGHT_MODE -> res.getString(
            when (raw) {
                "yes" -> R.string.setv_theme_dark
                "no" -> R.string.setv_theme_light
                else -> R.string.setv_theme_auto
            }
        )
        Kind.NAV_MODE -> res.getString(
            when (raw) {
                "0" -> R.string.setv_nav_3button
                "1" -> R.string.setv_nav_2button
                else -> R.string.setv_nav_gestures
            }
        )
        Kind.TIME_FORMAT -> res.getString(if (raw == "24") R.string.setv_24h else R.string.setv_12h)
        Kind.IME -> imeName(raw)
        Kind.DNS_MODE -> res.getString(
            when (raw) {
                "off" -> R.string.setv_dns_off
                "hostname" -> R.string.setv_dns_host
                else -> R.string.setv_dns_auto
            }
        )
        Kind.DAY_TIME_MS -> raw.toLongOrNull()?.let { ms ->
            val min = (ms / 60_000).toInt()
            String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60)
        } ?: raw
        Kind.NIGHT_DISPLAY_AUTO -> res.getString(
            when (raw) {
                "1" -> R.string.setv_nl_custom
                "2" -> R.string.setv_nl_sunset
                else -> R.string.setv_nl_off
            }
        )
        Kind.BOLD_TEXT -> res.getString(if ((raw.toIntOrNull() ?: 0) > 0) R.string.setv_on else R.string.setv_off)
        Kind.COLORS -> res.getString(R.string.setv_colors_custom)
        Kind.TEXT -> raw
    }

    private val KNOWN_KEYBOARDS = mapOf(
        "com.google.android.inputmethod.latin" to "Gboard",
        "com.samsung.android.honeyboard" to "Samsung Keyboard",
        "com.android.inputmethod.latin" to "AOSP Keyboard",
        "helium314.keyboard" to "HeliBoard",
        "org.futo.inputmethod.latin" to "FUTO Keyboard",
        "com.touchtype.swiftkey" to "SwiftKey",
        "org.florisboard.ime" to "FlorisBoard",
        "dev.patrickgold.florisboard" to "FlorisBoard",
        "com.simplemobiletools.keyboard" to "Simple Keyboard"
    )

    fun imeName(component: String): String {
        val pkg = component.substringBefore('/')
        return KNOWN_KEYBOARDS[pkg] ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
