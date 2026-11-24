package org.etg.migration.data.shizuku

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import org.etg.migration.model.WifiNetworkEntry
import org.etg.migration.model.WifiSecurityType
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object ShizukuBridge {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 47_820
    private const val SHELL_PACKAGE = "com.android.shell"

    enum class Status {
        UNSUPPORTED_ANDROID,
        NOT_INSTALLED,
        NOT_RUNNING,
        NO_PERMISSION,
        READY
    }

    fun status(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Status.UNSUPPORTED_ANDROID
        if (!isInstalled(context)) return Status.NOT_INSTALLED
        val alive = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
        if (!alive) return Status.NOT_RUNNING
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) Status.READY else Status.NO_PERMISSION
    }

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun launchIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri())

    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != PERMISSION_REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }.onFailure {
            Shizuku.removeRequestPermissionResultListener(listener)
            onResult(false)
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun readSavedWifiNetworks(): List<WifiNetworkEntry> {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "Android 11+ required" }

        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }

        val uid = Shizuku.getUid()

        val caller = when (uid) {
            0 -> "root"
            1000 -> "system"
            2000 -> "shell"
            else -> error("Unexpected Shizuku uid $uid")
        }

        val iface = Class.forName("android.net.wifi.IWifiManager")
        val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
        val binder: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.WIFI_SERVICE))
        val wifiManager = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)

        val result: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = Bundle().apply {
                putParcelable("EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE", shellAttributionSource(uid))
            }
            iface.getMethod(
                "getPrivilegedConfiguredNetworks",
                String::class.java,
                String::class.java,
                Bundle::class.java
            ).invoke(wifiManager, caller, SHELL_PACKAGE, extras)
        } else {
            runCatching {
                iface.getMethod("getPrivilegedConfiguredNetworks", String::class.java, String::class.java)
                    .invoke(wifiManager, caller, SHELL_PACKAGE)
            }.getOrElse { err ->
                if (err !is NoSuchMethodException) throw err
                iface.getMethod(
                    "getPrivilegedConfiguredNetworks",
                    String::class.java,
                    String::class.java,
                    Bundle::class.java
                ).invoke(wifiManager, caller, SHELL_PACKAGE, null)
            }
        }

        @Suppress("UNCHECKED_CAST")
        val configs = result?.let { it.javaClass.getMethod("getList").invoke(it) as? List<WifiConfiguration> }.orEmpty()
        return configs.mapNotNull(::toEntry).distinctBy { it.ssid to it.securityType }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun shellAttributionSource(uid: Int): AttributionSource =
        AttributionSource::class.java.getConstructor(
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            Set::class.java,
            AttributionSource::class.java
        ).newInstance(uid, SHELL_PACKAGE, SHELL_PACKAGE, null, null)

    @Suppress("DEPRECATION")
    private fun toEntry(cfg: WifiConfiguration): WifiNetworkEntry? {
        val ssid = cfg.SSID?.removeSurrounding("\"")?.takeIf { it.isNotBlank() } ?: return null
        val psk = cfg.preSharedKey?.takeIf { it.isNotBlank() && it != "*" }
        val wep = cfg.wepKeys?.firstOrNull { !it.isNullOrBlank() }
        val isSae = cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE) &&
            !cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)
        val (type, password) = when {
            psk != null -> (if (isSae) WifiSecurityType.WPA3 else WifiSecurityType.WPA2) to unquoteKey(psk)
            wep != null -> WifiSecurityType.WEP to unquoteKey(wep)
            cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) -> WifiSecurityType.OPEN to ""

            else -> return null
        }
        return WifiNetworkEntry(
            ssid = ssid,
            password = password,
            securityType = type,
            hidden = cfg.hiddenSSID,
            source = "shizuku"
        )
    }

    private fun unquoteKey(value: String): String = value.removeSurrounding("\"")

    fun exec(vararg cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
        val process = m.invoke(null, arrayOf(*cmd), null, null) as Process
        return try {
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            out
        } finally {
            runCatching { process.destroy() }
        }
    }

    fun readSettings(keys: List<Pair<String, String>>): Map<Pair<String, String>, String> {
        if (keys.isEmpty()) return emptyMap()
        val sep = "__ETG_SEP__"
        val safe = Regex("^[a-z_0-9]+$")
        val valid = keys.filter { (ns, k) -> ns in NAMESPACES && safe.matches(k) }
        val script = valid.joinToString("; ") { (ns, k) -> "settings get $ns $k; echo $sep" }
        val out = exec("sh", "-c", script)
        val values = out.split(sep).map { it.trim() }
        return buildMap {
            valid.forEachIndexed { i, key ->
                val v = values.getOrNull(i)
                if (!v.isNullOrBlank() && v != "null") put(key, v)
            }
        }
    }

    fun putSetting(namespace: String, key: String, value: String): Boolean {
        require(namespace in NAMESPACES)
        val out = exec("settings", "put", namespace, key, value)
        return !out.contains("Exception", ignoreCase = true) && !out.contains("error", ignoreCase = true)
    }

    private val NAMESPACES = setOf("system", "secure", "global")
}
