package org.etg.migration.data.exporter

import org.etg.migration.model.SoundRef
import org.etg.migration.model.SystemSettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionCatalogTest {
    @Test
    fun addressKeyMatchesNumberFormats() {
        val k = SelectionCatalog.addressKey("+48 600 123 456")
        assertEquals(k, SelectionCatalog.addressKey("600123456"))
        assertEquals(k, SelectionCatalog.addressKey("0048-600-123-456"))
        assertEquals("orange", SelectionCatalog.addressKey("ORANGE"))
    }

    @Test
    fun mediaFolderKeyTrimsSlashes() {
        assertEquals("DCIM/Camera", SelectionCatalog.mediaFolderKey("DCIM/Camera/"))
    }

    @Test
    fun filterSnapshotRemovesExcludedEntries() {
        val snap = SystemSettingsSnapshot(
            writableSystemSettings = mapOf("font_scale" to "1.1", "screen_off_timeout" to "60000"),
            personalDictionaryWords = listOf("grafen"),
            hasWallpaper = true,
            ringtone = SoundRef(title = "Beep")
        )
        val out = SelectionCatalog.filterSnapshot(
            snap,
            setOf("font_scale", SelectionCatalog.SETTING_WALLPAPER, SelectionCatalog.SETTING_RINGTONE)
        )
        assertEquals(setOf("screen_off_timeout"), out.writableSystemSettings.keys)
        assertFalse(out.hasWallpaper)
        assertNull(out.ringtone)
        assertTrue(out.personalDictionaryWords.isNotEmpty())
    }

    @Test
    fun settingsAreGroupedAndShizukuOnesMarked() {
        val snap = SystemSettingsSnapshot(
            writableSystemSettings = mapOf("screen_off_timeout" to "60000"),
            hasWallpaper = true,
            extendedSettings = mapOf(
                "global:window_animation_scale" to "0.5",
                "special:night_mode" to "yes"
            )
        )
        val items = SelectionCatalog.settingsItems(snap)
        val groups = items.map { it.groupRes }
        assertEquals(groups.sortedBy { g -> SettingsCatalog.Group.entries.first { it.titleRes == g }.ordinal }, groups)
        assertEquals(SelectionCatalog.SETTING_WALLPAPER, items.first().key)
        assertTrue(items.first { it.key == "global:window_animation_scale" }.needsShizuku)
        assertFalse(items.first { it.key == "screen_off_timeout" }.needsShizuku)
        val filtered = SelectionCatalog.filterSnapshot(snap, setOf("special:night_mode"))
        assertEquals(setOf("global:window_animation_scale"), filtered.extendedSettings.keys)
    }

    @Test
    fun invalidSettingValuesAreRejected() {
        val anim = SettingsCatalog.def("global:window_animation_scale")!!
        assertTrue(SettingsCatalog.isValid(anim, "0.5"))
        assertFalse(SettingsCatalog.isValid(anim, "abc"))
        val dns = SettingsCatalog.def("global:private_dns_mode")!!
        assertFalse(SettingsCatalog.isValid(dns, "hostname; reboot"))
        val nav = SettingsCatalog.def("secure:navigation_mode")!!
        assertFalse(SettingsCatalog.isValid(nav, "7"))
    }
}
