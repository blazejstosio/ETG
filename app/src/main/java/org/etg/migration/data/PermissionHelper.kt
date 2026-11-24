package org.etg.migration.data

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.provider.Telephony
import androidx.core.content.ContextCompat

object PermissionHelper {
    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasContactsRead(context: Context): Boolean =
        hasPermission(context, Manifest.permission.READ_CONTACTS)

    fun hasContactsWrite(context: Context): Boolean =
        hasPermission(context, Manifest.permission.WRITE_CONTACTS)

    fun hasCalendarRead(context: Context): Boolean =
        hasPermission(context, Manifest.permission.READ_CALENDAR)

    fun hasCalendarWrite(context: Context): Boolean =
        hasPermission(context, Manifest.permission.WRITE_CALENDAR)

    fun hasSmsRead(context: Context): Boolean =
        hasPermission(context, Manifest.permission.READ_SMS)

    fun hasCallLogRead(context: Context): Boolean =
        hasPermission(context, Manifest.permission.READ_CALL_LOG)

    fun hasCallLogWrite(context: Context): Boolean =
        hasPermission(context, Manifest.permission.WRITE_CALL_LOG)

    fun hasImagesRead(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                hasPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
        else -> hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasVideosRead(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ||
                hasPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
        else -> hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasAudioRead(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            hasPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
        else -> hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasAllFilesAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) &&
            hasPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun hasBluetoothConnect(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }

    fun hasNearbyWifi(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasCamera(context: Context): Boolean =
        hasPermission(context, Manifest.permission.CAMERA)

    fun hasNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

    fun isDefaultSmsApp(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            }
        }
        return runCatching { Telephony.Sms.getDefaultSmsPackage(context) == context.packageName }
            .getOrDefault(false)
    }

    fun canWriteSystemSettings(context: Context): Boolean =
        Settings.System.canWrite(context)

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun mediaPermissionsToRequest(includeAudio: Boolean = true): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
            if (includeAudio) list += Manifest.permission.READ_MEDIA_AUDIO
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                list += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                list += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        return list.toTypedArray()
    }

    fun allCoreRuntimePermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG
        )
        perms += mediaPermissionsToRequest(includeAudio = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return perms.distinct().toTypedArray()
    }
}
