package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SettingsHomeEntriesTest {

    private fun mainRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return File(requireNotNull(dir), "src/main/java")
    }

    private fun file(name: String): File {
        return mainRoot().resolve("dev/deeplinks/native/$name")
    }

    @Test
    fun `SettingsHome has exactly 2 onClick onOpenDevices`() {
        val text = file("SettingsRoute.kt").readText()
        val count = Regex("""onClick\s*=\s*onOpenDevices""").findAll(text).count()
        assertEquals(2, count)
    }

    @Test
    fun `LanguageSettings has no pairingManage or onOpenDevices`() {
        val text = file("SettingsRoute.kt").readText()
        val langStart = text.indexOf("internal fun LanguageSettings(")
        val langEnd = text.indexOf("\n}\n", langStart) + 3
        val langBody = text.substring(langStart, langEnd)
        assertFalse("LanguageSettings still uses pairingManage", langBody.contains("pairingManage"))
        assertFalse("LanguageSettings still has onOpenDevices", langBody.contains("onOpenDevices"))
    }

    @Test
    fun `SettingsHome has exactly 5 DshListSection top-level`() {
        val text = file("SettingsRoute.kt").readText()
        val homeStart = text.indexOf("internal fun SettingsHome(")
        val homeEnd = text.indexOf("\n}\n", homeStart) + 3
        val homeBody = text.substring(homeStart, homeEnd)
        val sections = Regex("""DshListSection\((container\s*=\s*DshSectionContainer\.Card,\s*)?header\s*=""").findAll(homeBody).map { it.range.first }.toList()
        assertEquals(5, sections.size)
    }
}
