package io.github.wg2038.shhh

import io.github.wg2038.shhh.ui.theme.MonetThemeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetThemeResolverTest {

    @Test
    fun isOverlayActiveDetectsCustomThemeKeys() {
        assertFalse(MonetThemeResolver.isOverlayActive(null))
        assertFalse(MonetThemeResolver.isOverlayActive(""))
        assertFalse(MonetThemeResolver.isOverlayActive("{}"))
        assertFalse(MonetThemeResolver.isOverlayActive("""{"other":"value"}"""))

        assertTrue(MonetThemeResolver.isOverlayActive("""{"android.theme.customization.system_palette":"FF336699"}"""))
        assertTrue(MonetThemeResolver.isOverlayActive("""{"android.theme.customization.accent_color":"#336699"}"""))
        assertTrue(MonetThemeResolver.isOverlayActive("""{"android.theme.customization.color_source":"preset"}"""))
    }

    @Test
    fun parseOverlaySeedArgbSupportsHexFormatsAndRejectsInvalidInput() {
        assertNull(MonetThemeResolver.parseOverlaySeedArgb(null))
        assertNull(MonetThemeResolver.parseOverlaySeedArgb("{}"))
        assertNull(MonetThemeResolver.parseOverlaySeedArgb("not-json"))
        assertNull(MonetThemeResolver.parseOverlaySeedArgb("""{"android.theme.customization.system_palette":"12345"}"""))

        assertEquals(
            0xFF336699.toInt(),
            MonetThemeResolver.parseOverlaySeedArgb("""{"android.theme.customization.system_palette":"#336699"}""")
        )
        assertEquals(
            0x80112233.toInt(),
            MonetThemeResolver.parseOverlaySeedArgb("""{"android.theme.customization.accent_color":"0x80112233"}""")
        )
        assertEquals(
            0xFF445566.toInt(),
            MonetThemeResolver.parseOverlaySeedArgb(
                """{"android.theme.customization.system_palette":null,"android.theme.customization.accent_color":"445566"}"""
            )
        )
    }
}
