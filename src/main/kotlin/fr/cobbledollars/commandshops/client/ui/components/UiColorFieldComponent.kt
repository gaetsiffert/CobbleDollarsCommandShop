// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.components

import fr.cobbledollars.commandshops.client.ui.FieldBinding
import fr.cobbledollars.commandshops.client.ui.UiChrome
import fr.cobbledollars.commandshops.client.ui.UiColorCodec
import fr.cobbledollars.commandshops.client.ui.UiRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

class UiColorFieldComponent(
    label: String,
    private val binding: FieldBinding<Int>,
    private val onRequestPicker: (title: String, color: Int, onApply: (Int) -> Unit) -> Unit,
    tooltipProvider: (() -> List<Component>?)? = null
) : UiLabeledRowComponent(label, tooltipProvider = tooltipProvider) {
    private val editor = UiSingleLineEditorState()
    private var focused = false
    private var valueRect: UiRect = UiRect.EMPTY
    private var swatchRect: UiRect = UiRect.EMPTY

    override val isFocusable: Boolean
        get() = true

    override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {
        val valueX = drawLabel(context, font, mouseX, mouseY)
        swatchRect = UiRect(bounds.right - 14, bounds.y + 1, 10, 10)
        valueRect = UiRect(
            x = valueX - 1,
            y = bounds.y,
            width = (swatchRect.x - valueX - 4).coerceAtLeast(10),
            height = preferredHeight
        )

        if (focused) {
            UiChrome.drawInputBox(context, valueRect, true)
            editor.render(context, font, valueRect, 0xFFFFFF)
        } else {
            UiChrome.drawHoverOverlay(context, valueRect, valueRect.contains(mouseX, mouseY), 0x44444466)
            context.drawString(
                font,
                font.plainSubstrByWidth(UiColorCodec.formatHex(binding.get()), valueRect.width - 4),
                valueRect.x + 2,
                bounds.y + 2,
                0xFFCC44,
                false
            )
        }

        drawSwatch(
            context = context,
            rect = swatchRect,
            color = currentPreviewColor(),
            hovered = swatchRect.contains(mouseX, mouseY)
        )
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (!bounds.contains(mouseX, mouseY)) return false
        if (swatchRect.contains(mouseX, mouseY)) {
            onRequestPicker(label, currentPreviewColor()) { color ->
                binding.set(color)
                if (focused) {
                    editor.reset(UiColorCodec.formatHex(color))
                }
            }
            return true
        }
        if (valueRect.contains(mouseX, mouseY)) {
            editor.moveCursorToMouse(Minecraft.getInstance().font, valueRect, mouseX)
        }
        return true
    }

    override fun wantsFocus(mouseX: Int, mouseY: Int): Boolean = valueRect.contains(mouseX, mouseY)

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!focused) return false
        when (editor.keyPressed(keyCode)) {
            UiSingleLineEditorState.Action.CANCEL -> editor.reset(UiColorCodec.formatHex(binding.get()))
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
            editor.reset(UiColorCodec.formatHex(binding.get()))
        } else if (this.focused) {
            commit()
        }
        this.focused = focused
    }

    private fun commit() {
        UiColorCodec.parseHex(editor.currentText)?.let(binding::set)
        editor.reset(UiColorCodec.formatHex(binding.get()))
    }

    private fun currentPreviewColor(): Int =
        if (focused) UiColorCodec.parseHex(editor.currentText) ?: binding.get() else binding.get()

    private fun drawSwatch(context: GuiGraphics, rect: UiRect, color: Int, hovered: Boolean) {
        context.fill(rect.x, rect.y, rect.right, rect.bottom, 0xFF000000.toInt())
        context.fill(rect.x + 1, rect.y + 1, rect.right - 1, rect.bottom - 1, 0xFF000000.toInt() or color)
        UiChrome.drawHoverOverlay(
            context = context,
            rect = UiRect(rect.x + 1, rect.y + 1, rect.width - 2, rect.height - 2),
            hovered = hovered,
            overlayColor = 0x22FFFFFF
        )
    }
}
