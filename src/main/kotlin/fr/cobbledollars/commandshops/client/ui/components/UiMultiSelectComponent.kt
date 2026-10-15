// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.components

import fr.cobbledollars.commandshops.client.ui.AdminUiTheme
import fr.cobbledollars.commandshops.client.ui.FieldBinding
import fr.cobbledollars.commandshops.client.ui.UiChrome
import fr.cobbledollars.commandshops.client.ui.UiIcons
import fr.cobbledollars.commandshops.client.ui.UiRect
import fr.cobbledollars.commandshops.client.ui.UiText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

class UiMultiSelectComponent(
    label: String,
    private val binding: FieldBinding<Set<String>>,
    private val colorProvider: (String) -> Int = { 0x888888 },
    private val displayText: (String) -> String = { it },
    private val onRequestSelection: (UiRect, Set<String>) -> Unit,
    private val actionIcon: String = UiIcons.SELECT_MANY,
    private val tooltipProvider: (() -> List<Component>?)? = null
) : UiLabeledRowComponent(label, tooltipProvider = tooltipProvider) {
    private companion object {
        const val ROW_HEIGHT = 12
        const val CHIP_GAP = 2
        const val CHIP_PADDING = 4
    }

    private data class ChipLayout(
        val value: String,
        val text: String,
        val rect: UiRect
    )

    private var buttonRect: UiRect = UiRect.EMPTY

    override val preferredHeight: Int
        get() = measurePreferredHeight(Minecraft.getInstance().font)

    override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {
        val valueX = drawLabel(context, font, mouseX, mouseY)
        buttonRect = UiRect(
            bounds.right - UiChrome.SMALL_ICON_BUTTON_WIDTH - 4,
            bounds.y,
            UiChrome.SMALL_ICON_BUTTON_WIDTH,
            UiChrome.SMALL_ICON_BUTTON_HEIGHT
        )
        val maxRight = buttonRect.x - 2
        val values = binding.get().toList()

        if (values.isEmpty()) {
            val emptyRect = UiRect(valueX - 1, bounds.y, (maxRight - valueX).coerceAtLeast(10), ROW_HEIGHT)
            UiChrome.drawHoverOverlay(context, emptyRect, emptyRect.contains(mouseX, mouseY), 0x33334466)
            context.drawString(font, UiText.admin("(none)"), valueX, bounds.y + 2, 0x777777, false)
        } else {
            for (chip in chipLayouts(font, valueX, maxRight, values)) {
                context.fill(
                    chip.rect.x,
                    chip.rect.y,
                    chip.rect.right,
                    chip.rect.bottom,
                    (0xCC000000L or colorProvider(chip.value).toLong()).toInt()
                )
                context.drawString(font, chip.text, chip.rect.x + 2, chip.rect.y + 1, 0xFFFFFF, true)
            }
        }

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
        onRequestSelection(buttonRect, binding.get())
        return true
    }

    private fun measurePreferredHeight(font: Font): Int {
        val values = binding.get().toList()
        if (values.isEmpty() || bounds.width <= 0) return ROW_HEIGHT

        val valueX = valueStartX(font)
        val maxRight = bounds.right - UiChrome.SMALL_ICON_BUTTON_WIDTH - 6
        val rows = chipLayouts(font, valueX, maxRight, values)
            .map { (it.rect.y - bounds.y) / ROW_HEIGHT }
            .maxOrNull()
            ?.plus(1)
            ?: 1
        return (rows * ROW_HEIGHT).coerceAtLeast(ROW_HEIGHT)
    }

    private fun valueStartX(font: Font): Int {
        val labelText = "${UiText.admin(label)}: "
        val tooltipOffset = if (tooltipProvider != null) 8 else 0
        return bounds.x + 6 + font.width(labelText) + tooltipOffset
    }

    private fun chipLayouts(font: Font, valueX: Int, maxRight: Int, values: List<String>): List<ChipLayout> {
        val startX = if (maxRight - valueX < 24) bounds.x + 4 else valueX
        val availableWidth = (maxRight - startX).coerceAtLeast(10)
        var chipX = startX
        var chipY = if (startX == valueX) bounds.y + 1 else bounds.y + ROW_HEIGHT + 1
        return values.map { value ->
            val display = displayText(value)
            val chipWidth = (font.width(display) + CHIP_PADDING).coerceAtMost(availableWidth)
            if (chipX > startX && chipX + chipWidth > maxRight) {
                chipX = startX
                chipY += ROW_HEIGHT
            }
            val rect = UiRect(chipX, chipY, chipWidth, ROW_HEIGHT - 2)
            chipX += chipWidth + CHIP_GAP
            ChipLayout(value = value, text = fitText(font, display, chipWidth - CHIP_PADDING), rect = rect)
        }
    }

    private fun fitText(font: Font, text: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (font.width(text) <= maxWidth) return text
        val ellipsis = "..."
        val ellipsisWidth = font.width(ellipsis)
        if (maxWidth <= ellipsisWidth) return font.plainSubstrByWidth(text, maxWidth)
        return font.plainSubstrByWidth(text, maxWidth - ellipsisWidth).trimEnd() + ellipsis
    }
}
