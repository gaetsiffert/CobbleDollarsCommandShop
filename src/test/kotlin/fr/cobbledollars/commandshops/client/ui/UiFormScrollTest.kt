package fr.cobbledollars.commandshops.client.ui

import fr.cobbledollars.commandshops.client.ui.form.UiForm
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UiFormScrollTest {
    @Test
    fun `restoration waits for a viewport and keeps the requested position through repeated rebuilds`() {
        val form = form()
        form.restoreScrollPosition(216)
        assertEquals(216, form.scrollPosition)
        form.layout(UiRect(10, 20, 200, 80))
        assertEquals(216, form.scrollPosition)
        val rebuilt = form()
        rebuilt.restoreScrollPosition(form.scrollPosition)
        assertEquals(216, rebuilt.scrollPosition)
        rebuilt.layout(form.bounds)
        assertEquals(216, rebuilt.scrollPosition)
    }

    @Test
    fun `restored positions clamp to the actual content and resized viewport`() {
        val form = form()
        form.restoreScrollPosition(Int.MAX_VALUE)
        form.layout(UiRect(10, 20, 200, 80))
        assertTrue(form.hasScrollAbove)
        assertFalse(form.hasScrollBelow)
        form.layout(UiRect(10, 20, 200, 1000))
        assertEquals(0, form.scrollPosition)
        form.restoreScrollPosition(-100)
        assertEquals(0, form.scrollPosition)
    }

    @Test
    fun `committing focus can rebuild without blurring the same field recursively`() {
        lateinit var form: UiForm
        var commits = 0
        val row = object : BaseUiComponent() {
            override val preferredHeight = 12
            override val isFocusable = true
            override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {}
            override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int) = true
            override fun focusChanged(focused: Boolean) {
                if (!focused) { commits++; form.clearFocus() }
            }
        }
        form = UiForm(listOf(row))
        form.layout(UiRect(10, 20, 200, 80))
        form.mouseClicked(20, 24, 0)
        form.clearFocus()
        assertEquals(1, commits)
        assertFalse(form.hasFocusedComponent)
    }

    private fun form() = UiForm(List(40) {
        object : BaseUiComponent() {
            override val preferredHeight = 12
            override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {}
        }
    }, gap = 3)
}
