// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.form

import fr.cobbledollars.commandshops.client.ui.UiComponent
import fr.cobbledollars.commandshops.client.ui.UiRect
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

class UiForm(
    private val components: List<UiComponent>,
    private val gap: Int = 0,
    private val topPadding: Int = 2,
    private val bottomPadding: Int = 2,
    private val scrollStep: Int = 12
) {
    var bounds: UiRect = UiRect.EMPTY
        private set

    private var scrollOffset = 0
    private var contentHeight = 0
    private var focusedComponent: UiComponent? = null

    val hasFocusedComponent: Boolean get() = focusedComponent != null

    /** Distinguishes an actual control click from a click on empty panel space. */
    var lastClickHandled: Boolean = false
        private set

    val hasScrollAbove: Boolean
        get() = scrollOffset > 0

    val hasScrollBelow: Boolean
        get() = scrollOffset < maxScroll()

    fun layout(bounds: UiRect) {
        this.bounds = bounds
        applyLayout()
    }

    fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {
        applyLayout()
        context.enableScissor(bounds.x + 1, bounds.y + 1, bounds.right - 1, bounds.bottom - 1)
        for (component in components) {
            if (component.bounds.bottom < bounds.y || component.bounds.y > bounds.bottom) continue
            component.render(context, font, mouseX, mouseY, delta)
        }
        context.disableScissor()
    }

    fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        lastClickHandled = false
        applyLayout()
        if (!bounds.contains(mouseX, mouseY)) {
            clearFocus()
            return false
        }

        val target = components.firstOrNull { it.bounds.contains(mouseX, mouseY) }
        if (target?.wantsFocus(mouseX, mouseY) == true) {
            setFocusedComponent(target)
        } else {
            clearFocus()
        }

        lastClickHandled = target?.mouseClicked(mouseX, mouseY, button) ?: false
        return if (target == null) true else lastClickHandled
    }

    fun mouseScrolled(mouseX: Int, mouseY: Int, horizontalAmount: Double, verticalAmount: Double): Boolean {
        applyLayout()
        if (!bounds.contains(mouseX, mouseY)) return false
        val target = components.firstOrNull { it.bounds.contains(mouseX, mouseY) }
        if (target?.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount) == true) {
            return true
        }
        val nextOffset = (scrollOffset - verticalAmount.toInt() * scrollStep).coerceIn(0, maxScroll())
        if (nextOffset == scrollOffset) return true
        scrollOffset = nextOffset
        applyLayout()
        return true
    }

    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val target = focusedComponent ?: return false
        val consumed = target.keyPressed(keyCode, scanCode, modifiers)
        if (consumed && (keyCode == 256 || keyCode == 257)) {
            clearFocus()
        }
        return consumed
    }

    fun charTyped(char: Char, modifiers: Int): Boolean =
        focusedComponent?.charTyped(char, modifiers) ?: false

    fun tooltip(mouseX: Int, mouseY: Int): List<Component>? {
        if (!bounds.contains(mouseX, mouseY)) return null
        for (component in components) {
            component.tooltip(mouseX, mouseY)?.let { return it }
        }
        return null
    }

    fun clearFocus() {
        setFocusedComponent(null)
    }

    private fun setFocusedComponent(component: UiComponent?) {
        if (focusedComponent === component) return
        focusedComponent?.focusChanged(false)
        focusedComponent = component
        focusedComponent?.focusChanged(true)
    }

    private fun applyLayout() {
        if (bounds == UiRect.EMPTY) return

        contentHeight = measureContentHeight()
        val clampedOffset = scrollOffset.coerceIn(0, maxScroll())
        scrollOffset = clampedOffset
        layoutForOffset(scrollOffset)
    }

    private fun layoutForOffset(offset: Int): Int {
        var y = bounds.y + topPadding - offset
        for (component in components) {
            component.layout(UiRect(bounds.x, y, bounds.width, component.preferredHeight))
            y += component.preferredHeight + gap
        }

        val contentGap = if (components.isEmpty()) 0 else gap
        return (y - bounds.y + bottomPadding - contentGap).coerceAtLeast(0)
    }

    private fun measureContentHeight(): Int {
        if (components.isEmpty()) return topPadding + bottomPadding
        val componentsHeight = components.sumOf { it.preferredHeight }
        val gapsHeight = gap * (components.size - 1)
        return topPadding + componentsHeight + gapsHeight + bottomPadding
    }

    private fun maxScroll(): Int = (contentHeight - bounds.height).coerceAtLeast(0)
}
