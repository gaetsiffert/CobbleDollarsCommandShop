// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

interface UiComponent {
    var bounds: UiRect

    val preferredHeight: Int

    val isFocusable: Boolean
        get() = false

    fun layout(bounds: UiRect) {
        this.bounds = bounds
    }

    fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float)

    fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean = false

    fun mouseScrolled(mouseX: Int, mouseY: Int, horizontalAmount: Double, verticalAmount: Double): Boolean = false

    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false

    fun charTyped(char: Char, modifiers: Int): Boolean = false

    fun focusChanged(focused: Boolean) {}

    fun wantsFocus(mouseX: Int, mouseY: Int): Boolean = isFocusable

    fun tooltip(mouseX: Int, mouseY: Int): List<Component>? = null
}

abstract class BaseUiComponent(
    override var bounds: UiRect = UiRect.EMPTY
) : UiComponent
