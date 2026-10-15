// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.components

import fr.cobbledollars.commandshops.client.ui.BaseUiComponent
import fr.cobbledollars.commandshops.client.ui.FieldBinding
import fr.cobbledollars.commandshops.client.ui.AdminUiTheme
import fr.cobbledollars.commandshops.client.ui.UiHelpInlineLabel
import fr.cobbledollars.commandshops.client.ui.UiChrome
import fr.cobbledollars.commandshops.client.ui.UiIcons
import fr.cobbledollars.commandshops.client.ui.UiRect
import fr.cobbledollars.commandshops.client.ui.UiText
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

class UiItemFieldComponent(
    private val label: String,
    private val binding: FieldBinding<String>,
    private val displayText: (String) -> String = { if (it.isBlank()) UiText.admin("(global default)") else it.substringAfter(":") },
    private val onRequestPicker: (UiRect) -> Unit,
    private val editable: Boolean = true,
    private val tooltipProvider: (() -> List<Component>?)? = null,
    override val preferredHeight: Int = 12
) : BaseUiComponent() {
    private val pickerLabel = UiIcons.PICK_ITEM
    private var focused = false
    private val editor = UiSingleLineEditorState()
    private var tooltipRect: UiRect? = null
    private var valueRect: UiRect = UiRect.EMPTY
    private var pickerRect: UiRect = UiRect.EMPTY

    override val isFocusable: Boolean
        get() = editable

    override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {
        pickerRect = UiRect(bounds.right - UiChrome.SMALL_ICON_BUTTON_WIDTH - 4, bounds.y, UiChrome.SMALL_ICON_BUTTON_WIDTH, preferredHeight)
        val labelRender = UiHelpInlineLabel.render(
            context = context,
            font = font,
            bounds = bounds,
            mouseX = mouseX,
            mouseY = mouseY,
            labelText = "${UiText.admin(label)}: ",
            textColor = 0x999999,
            hasTooltip = tooltipProvider != null,
            startX = 6,
            yOffset = 2,
            maxRightX = pickerRect.x - 4,
            afterSpacing = 0
        )
        tooltipRect = labelRender.tooltipRect
        val valueX = labelRender.contentStartX
        valueRect = UiRect(valueX, bounds.y, (pickerRect.x - valueX - 2).coerceAtLeast(10), preferredHeight)

        if (focused) {
            UiChrome.drawInputBox(context, valueRect, true)
            editor.render(context, font, valueRect, 0xFFFFFF)
        } else {
            UiChrome.drawHoverOverlay(context, valueRect, editable && valueRect.contains(mouseX, mouseY), 0x44444466)
            val displayValue = displayText(binding.get())
            context.drawString(font, font.plainSubstrByWidth(displayValue, valueRect.width - 2), valueRect.x, bounds.y + 2, 0xFFCC44, false)
        }

        val pickerHovered = pickerRect.contains(mouseX, mouseY)
        UiChrome.drawIconButton(
            context = context,
            font = font,
            rect = pickerRect,
            label = pickerLabel,
            hovered = pickerHovered,
            style = AdminUiTheme.itemPickerButton
        )
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (!bounds.contains(mouseX, mouseY)) return false
        if (!pickerRect.contains(mouseX, mouseY) && !valueRect.contains(mouseX, mouseY)) return false
        if (pickerRect.contains(mouseX, mouseY)) {
            onRequestPicker(pickerRect)
        } else if (valueRect.contains(mouseX, mouseY)) {
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

    override fun tooltip(mouseX: Int, mouseY: Int): List<Component>? {
        if (pickerRect.contains(mouseX, mouseY)) return listOf(Component.translatable("gui.cobbledollarscommandshops.admin.tooltip.pick_item"))
        val provider = tooltipProvider ?: return null
        return if (tooltipRect?.contains(mouseX, mouseY) == true) provider() else null
    }

    private fun commit() {
        binding.set(editor.currentText.trim())
    }
}
