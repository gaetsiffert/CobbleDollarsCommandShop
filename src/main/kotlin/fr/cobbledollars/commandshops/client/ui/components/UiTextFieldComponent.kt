// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.components

import fr.cobbledollars.commandshops.client.ui.FieldBinding
import fr.cobbledollars.commandshops.client.ui.UiChrome
import fr.cobbledollars.commandshops.client.ui.UiRect
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

class UiTextFieldComponent(
    label: String,
    private val binding: FieldBinding<String>,
    private val editable: Boolean = true,
    private val valueColor: Int = 0xCCCCCC,
    tooltipProvider: (() -> List<Component>?)? = null
) : UiLabeledRowComponent(label, tooltipProvider = tooltipProvider) {
    private var focused = false
    private val editor = UiSingleLineEditorState()
    private var valueRect: UiRect = UiRect.EMPTY

    override val isFocusable: Boolean
        get() = editable

    override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {
        val valueX = drawLabel(context, font, mouseX, mouseY)
        val valueWidth = (bounds.right - valueX - 12).coerceAtLeast(10)
        valueRect = UiRect(valueX - 1, bounds.y, valueWidth + 1, preferredHeight)

        if (focused) {
            UiChrome.drawInputBox(context, valueRect, true)
            editor.render(context, font, valueRect, 0xFFFFFF)
            return
        }

        UiChrome.drawHoverOverlay(context, valueRect, editable && valueRect.contains(mouseX, mouseY), 0x44444466)

        context.drawString(
            font,
            font.plainSubstrByWidth(binding.get(), valueWidth - 2),
            valueX,
            bounds.y + 2,
            valueColor,
            false
        )
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (!editable || !bounds.contains(mouseX, mouseY)) return false
        if (!valueRect.contains(mouseX, mouseY)) return false
        if (valueRect.contains(mouseX, mouseY)) {
            editor.moveCursorToMouse(net.minecraft.client.Minecraft.getInstance().font, valueRect, mouseX)
        }
        return true
    }

    override fun wantsFocus(mouseX: Int, mouseY: Int): Boolean =
        editable && valueRect.contains(mouseX, mouseY)

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!focused) return false
        when (editor.keyPressed(keyCode)) {
            UiSingleLineEditorState.Action.CANCEL -> editor.reset(binding.get())
            UiSingleLineEditorState.Action.COMMIT -> commit()
            UiSingleLineEditorState.Action.NONE -> {}
        }
        return true
    }

    override fun charTyped(char: Char, modifiers: Int): Boolean {
        if (!focused) return false
        return editor.charTyped(char)
    }

    override fun focusChanged(focused: Boolean) {
        if (this.focused == focused) return
        if (focused) {
            editor.reset(binding.get())
        } else if (this.focused) {
            commit()
        }
        this.focused = focused
    }

    private fun commit() {
        binding.set(editor.currentText.trim())
    }
}
