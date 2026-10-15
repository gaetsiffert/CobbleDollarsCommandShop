// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.components

import fr.cobbledollars.commandshops.client.ui.AdminUiTheme
import fr.cobbledollars.commandshops.client.ui.FieldBinding
import fr.cobbledollars.commandshops.client.ui.UiChrome
import fr.cobbledollars.commandshops.client.ui.UiIcons
import fr.cobbledollars.commandshops.client.ui.UiRect
import fr.cobbledollars.commandshops.client.ui.UiText
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

class UiSelectComponent(
    label: String,
    private val binding: FieldBinding<String>,
    private val displayText: (String) -> String = { it },
    private val emptyText: String = "",
    private val onRequestSelection: (UiRect) -> Unit,
    private val actionIcon: String = UiIcons.SELECT_ONE,
    tooltipProvider: (() -> List<Component>?)? = null
) : UiLabeledRowComponent(label, tooltipProvider = tooltipProvider) {
    private var buttonRect: UiRect = UiRect.EMPTY

    override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {
        val valueX = drawLabel(context, font, mouseX, mouseY)
        buttonRect = UiRect(bounds.right - UiChrome.SMALL_ICON_BUTTON_WIDTH - 4, bounds.y, UiChrome.SMALL_ICON_BUTTON_WIDTH, preferredHeight)
        val valueWidth = (buttonRect.x - valueX - 2).coerceAtLeast(10)
        val rawValue = binding.get()
        val renderedValue = rawValue.takeIf(String::isNotBlank)?.let(displayText) ?: UiText.admin(emptyText)

        UiChrome.drawHoverOverlay(
            context,
            UiRect(valueX - 1, bounds.y, valueWidth + 1, preferredHeight),
            UiRect(valueX - 1, bounds.y, valueWidth + 1, preferredHeight).contains(mouseX, mouseY),
            0x33334466
        )
        context.drawString(font, font.plainSubstrByWidth(renderedValue, valueWidth - 2), valueX, bounds.y + 2, 0xAAAAFF, false)

        UiChrome.drawIconButton(
            context = context,
            font = font,
            rect = buttonRect,
            label = actionIcon,
            hovered = buttonRect.contains(mouseX, mouseY),
            style = AdminUiTheme.neutralButton
        )
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (!bounds.contains(mouseX, mouseY)) return false
        if (super.tooltip(mouseX, mouseY) != null) return false
        onRequestSelection(buttonRect)
        return true
    }

    override fun tooltip(mouseX: Int, mouseY: Int): List<Component>? =
        if (buttonRect.contains(mouseX, mouseY)) listOf(Component.translatable("gui.cobbledollarscommandshops.admin.tooltip.select"))
        else super.tooltip(mouseX, mouseY)
}
