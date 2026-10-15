package fr.cobbledollars.commandshops.client.ui

import fr.cobbledollars.commandshops.client.ui.form.UiForm
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiFormInteractionTest {
    @Test
    fun `click feedback distinguishes controls from empty space and help labels`() {
        val control = ProbeComponent()
        val label = object : BaseUiComponent() {
            override val preferredHeight = 12
            override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {}
        }
        val form = UiForm(listOf(control, label))
        form.layout(UiRect(10, 20, 100, 60))
        form.mouseClicked(20, 24, 0)
        assertTrue(form.lastClickHandled)
        form.mouseClicked(20, 37, 0)
        assertFalse(form.lastClickHandled)
        form.mouseClicked(20, 70, 0)
        assertFalse(form.lastClickHandled)
        form.mouseClicked(150, 24, 0)
        assertFalse(form.lastClickHandled)
    }

    @Test
    fun `leaving the form releases its editor before an external save action`() {
        val first = ProbeComponent()
        val second = ProbeComponent()
        val form = UiForm(listOf(first, second))
        form.layout(UiRect(10, 20, 100, 40))

        assertTrue(form.mouseClicked(20, 24, 0))
        assertEquals(listOf(true), first.focusChanges)
        assertTrue(form.charTyped('x', 0))
        assertEquals("x", first.typed)

        assertFalse(form.mouseClicked(150, 24, 0))
        assertEquals(listOf(true, false), first.focusChanges)
        assertFalse(form.charTyped('y', 0))
        assertEquals("x", first.typed)
    }

    @Test
    fun `changing rows commits previous focus and redirects keyboard input`() {
        val first = ProbeComponent()
        val second = ProbeComponent()
        val form = UiForm(listOf(first, second))
        form.layout(UiRect(10, 20, 100, 40))

        form.mouseClicked(20, 24, 0)
        form.mouseClicked(20, 37, 0)
        form.charTyped('b', 0)
        form.keyPressed(257, 0, 0)

        assertEquals(listOf(true, false), first.focusChanges)
        assertEquals(listOf(true, false), second.focusChanges)
        assertEquals("", first.typed)
        assertEquals("b", second.typed)
        assertFalse(form.charTyped('c', 0))
    }

    @Test
    fun `scrolling routes clicks to the visible row and clamps at either end`() {
        val rows = List(5) { ProbeComponent() }
        val form = UiForm(rows)
        form.layout(UiRect(10, 20, 100, 28))

        assertFalse(form.mouseScrolled(0, 0, 0.0, -1.0))
        assertTrue(form.mouseScrolled(20, 30, 0.0, -100.0))
        assertTrue(form.hasScrollAbove)
        assertFalse(form.hasScrollBelow)
        form.mouseClicked(20, 40, 0)
        assertEquals(1, rows.last().clicks)
        assertEquals(0, rows.first().clicks)

        form.mouseScrolled(20, 30, 0.0, 100.0)
        assertFalse(form.hasScrollAbove)
        assertTrue(form.hasScrollBelow)
        assertEquals(22, rows.first().bounds.y)
    }

    @Test
    fun `collapsing dynamic content removes stale scroll offset`() {
        val expandingRow = ProbeComponent(height = 120)
        val finalRow = ProbeComponent()
        val form = UiForm(listOf(expandingRow, finalRow))
        val bounds = UiRect(10, 20, 100, 40)
        form.layout(bounds)
        form.mouseScrolled(20, 30, 0.0, -100.0)
        assertTrue(form.hasScrollAbove)

        expandingRow.height = 12
        form.layout(bounds)

        assertFalse(form.hasScrollAbove)
        assertFalse(form.hasScrollBelow)
        assertEquals(22, expandingRow.bounds.y)
        assertEquals(34, finalRow.bounds.y)
    }

    private class ProbeComponent(var height: Int = 12) : BaseUiComponent() {
        val focusChanges = mutableListOf<Boolean>()
        var typed = ""
        var clicks = 0

        override val preferredHeight: Int get() = height
        override val isFocusable: Boolean get() = true

        override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {}

        override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
            clicks++
            return true
        }

        override fun focusChanged(focused: Boolean) {
            focusChanges += focused
        }

        override fun charTyped(char: Char, modifiers: Int): Boolean {
            typed += char
            return true
        }

        override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = true
    }
}
