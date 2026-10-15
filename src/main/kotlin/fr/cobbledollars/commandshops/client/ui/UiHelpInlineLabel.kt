// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

object UiHelpInlineLabel {
    data class Layout(
        val fittedText: String,
        val contentStartX: Int,
        val tooltipRect: UiRect?
    )

    fun render(
        context: GuiGraphics,
        font: Font,
        bounds: UiRect,
        mouseX: Int,
        mouseY: Int,
        labelText: String,
        textColor: Int,
        hasTooltip: Boolean,
        startX: Int = 4,
        yOffset: Int = 2,
        maxRightX: Int = bounds.right - 4,
        afterSpacing: Int = 2,
        drawText: Boolean = true,
        helpYOffset: Int = 0
    ): Layout {
        val textX = bounds.x + startX
        val reservedWidth = if (hasTooltip) UiChrome.HELP_MARKER_WIDTH else 0
        val maxTextWidth = (maxRightX - textX - reservedWidth).coerceAtLeast(0)
        val fittedText = UiChrome.fitText(font, labelText, maxTextWidth)
        if (drawText) {
            context.drawString(font, fittedText, textX, bounds.y + yOffset, textColor, false)
        }

        var contentStartX = textX + font.width(fittedText)
        val tooltipRect = if (hasTooltip) {
            val questionX = contentStartX
            val rect = UiRect(questionX - 2, bounds.y + helpYOffset, UiChrome.HELP_MARKER_WIDTH, bounds.height)
            UiChrome.drawHelpMarker(context, font, questionX, bounds.y + helpYOffset, rect.contains(mouseX, mouseY))
            contentStartX += UiChrome.HELP_MARKER_WIDTH
            rect
        } else {
            null
        }

        return Layout(
            fittedText = fittedText,
            contentStartX = contentStartX + afterSpacing,
            tooltipRect = tooltipRect
        )
    }
}
