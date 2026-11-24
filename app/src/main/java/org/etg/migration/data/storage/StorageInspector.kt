package org.etg.migration.data.storage

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import org.etg.migration.model.StorageVolumeInfo
import java.io.File

object StorageInspector {
    const val FAT32_SAFE_LIMIT_BYTES = 3_800_000_000L

    fun listStorageVolumes(context: Context): List<StorageVolumeInfo> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        val result = mutableListOf<StorageVolumeInfo>()
        for (vol in sm.storageVolumes) {
            if (vol.state != Environment.MEDIA_MOUNTED && vol.state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                continue
            }
            val dir = resolveVolumeDirectory(vol)
            val stats = dir?.let { readStatFs(it) }
            val desc = vol.getDescription(context)
            val id = vol.uuid ?: if (vol.isPrimary) "primary" else desc
            result += StorageVolumeInfo(
                id = id,
                description = desc,
                path = dir?.absolutePath.orEmpty(),
                isRemovableUsb = vol.isRemovable && !vol.isPrimary,
                totalBytes = stats?.first ?: 0L,
                freeBytes = stats?.second ?: 0L
            )
        }
        return result
    }

    fun listRemovableUsbVolumes(context: Context): List<StorageVolumeInfo> =
        listStorageVolumes(context).filter { it.isRemovableUsb }

    fun createOpenUsbTreeIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sm = context.getSystemService(StorageManager::class.java)
            val usbVol = sm?.storageVolumes?.firstOrNull {
                it.isRemovable && !it.isPrimary && it.state == Environment.MEDIA_MOUNTED
            }
            if (usbVol != null) {
                return usbVol.createOpenDocumentTreeIntent()
            }
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    fun isFat32SingleFileRisk(estimatedBytes: Long): Boolean =
        estimatedBytes >= FAT32_SAFE_LIMIT_BYTES

    private fun resolveVolumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.directory
        }
        if (volume.isPrimary) {
            return Environment.getExternalStorageDirectory()
        }
        val uuid = volume.uuid ?: return null
        val candidate = File("/storage/$uuid")
        return candidate.takeIf { it.exists() }
    }

    private fun readStatFs(dir: File): Pair<Long, Long>? = runCatching {
        val stat = StatFs(dir.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        total to free
    }.getOrNull()
}
