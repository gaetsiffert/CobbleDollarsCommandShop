// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

import fr.cobbledollars.commandshops.client.ui.drawBorder
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

object UiChrome {
    const val SMALL_ICON_BUTTON_WIDTH = 14
    const val SMALL_ICON_BUTTON_HEIGHT = 12
    const val HELP_MARKER_WIDTH = 8
    private const val SECTION_HEADER_TOP = 2
    private const val SECTION_HEADER_BOTTOM = 4
    private const val DEFAULT_HOVER_OVERLAY_COLOR = 0x22334466
    private const val DEFAULT_ROW_SELECTED_COLOR = 0x66334455
    private const val DEFAULT_ROW_BACKGROUND_COLOR = 0x00000000
    private const val DEFAULT_COLLAPSIBLE_BACKGROUND = 0x22000000
    private const val DEFAULT_COLLAPSIBLE_HOVER_BACKGROUND = 0x33444466

    fun drawThickBorder(
        context: GuiGraphics,
        rect: UiRect,
        color: Int,
        thickness: Int
    ) {
        for (i in 0 until thickness) {
            context.drawBorder(
                rect.x + i,
                rect.y + i,
                rect.width - i * 2,
                rect.height - i * 2,
                color
            )
        }
    }

    fun drawPanel(
        context: GuiGraphics,
        rect: UiRect,
        style: UiPanelStyle
    ) {
        context.fill(rect.x, rect.y, rect.right, rect.bottom, style.backgroundColor)
        drawThickBorder(context, rect, style.borderColor, style.borderThickness)
    }

    fun drawHoverOverlay(
        context: GuiGraphics,
        rect: UiRect,
        hovered: Boolean,
        overlayColor: Int = DEFAULT_HOVER_OVERLAY_COLOR
    ) {
        if (!hovered || rect.width <= 0 || rect.height <= 0) return
        context.fill(rect.x, rect.y, rect.right, rect.bottom, overlayColor)
    }

    fun drawInteractiveRow(
        context: GuiGraphics,
        rect: UiRect,
        hovered: Boolean,
        selected: Boolean = false,
        backgroundColor: Int = DEFAULT_ROW_BACKGROUND_COLOR,
        selectedColor: Int = DEFAULT_ROW_SELECTED_COLOR,
        hoverOverlayColor: Int = DEFAULT_HOVER_OVERLAY_COLOR
    ) {
        if (rect.width <= 0 || rect.height <= 0) return
        if (backgroundColor != 0) {
            context.fill(rect.x, rect.y, rect.right, rect.bottom, backgroundColor)
        }
        if (selected) {
            context.fill(rect.x, rect.y, rect.right, rect.bottom, selectedColor)
        } else {
            drawHoverOverlay(context, rect, hovered, hoverOverlayColor)
        }
    }

    fun measureButtonWidth(font: Font, label: String, horizontalPadding: Int = 10): Int =
        font.width(label) + horizontalPadding

    fun drawSection(
        context: GuiGraphics,
        font: Font,
        rect: UiRect,
        label: String,
        style: UiPanelStyle
    ) {
        drawPanel(context, rect, style)
        val headerHeight = measureSectionHeaderHeight(font, rect.width, label)
        style.titleBarColor?.let { titleBarColor ->
            context.fill(rect.x + 1, rect.y + 1, rect.right - 1, rect.y + headerHeight, titleBarColor)
        }
        UiTextWrap.wrap(font, label, (rect.width - 6).coerceAtLeast(8)).forEachIndexed { index, line ->
            context.drawString(
                font,
                line,
                rect.x + 3,
                rect.y + SECTION_HEADER_TOP + index * (font.lineHeight + 1),
                style.titleColor,
                true
            )
        }
    }

    fun measureSectionHeaderHeight(
        font: Font,
        width: Int,
        label: String
    ): Int {
        val lines = UiTextWrap.wrap(font, label, (width - 6).coerceAtLeast(8))
        return (SECTION_HEADER_TOP + UiTextWrap.lineBlockHeight(
            font,
            lines.size
        ) + SECTION_HEADER_BOTTOM).coerceAtLeast(14)
    }

    fun drawButton(
        context: GuiGraphics,
        font: Font,
        rect: UiRect,
        label: String,
        hovered: Boolean,
        style: UiButtonStyle
    ) {
        val backgroundColor = if (hovered) style.hoverBackgroundColor else style.normalBackgroundColor
        val textColor = if (hovered) style.hoverTextColor else style.textColor
        context.fill(rect.x, rect.y, rect.right, rect.bottom, backgroundColor)
        context.drawBorder(rect.x, rect.y, rect.width, rect.height, style.borderColor)
        val fittedLabel = fitText(font, label, (rect.width - 6).coerceAtLeast(0))
        context.drawString(font, fittedLabel, rect.x + 3, rect.y + 2, textColor, false)
    }

    fun drawCollapsibleHeader(
        context: GuiGraphics,
        font: Font,
        rect: UiRect,
        title: String,
        expanded: Boolean,
        hovered: Boolean,
        textColor: Int = 0xBBBBCC,
        backgroundColor: Int = DEFAULT_COLLAPSIBLE_BACKGROUND,
        hoverBackgroundColor: Int = DEFAULT_COLLAPSIBLE_HOVER_BACKGROUND
    ) {
        drawInteractiveRow(
            context = context,
            rect = UiRect(rect.x + 1, rect.y, rect.width - 2, rect.height),
            hovered = hovered,
            backgroundColor = backgroundColor,
            hoverOverlayColor = hoverBackgroundColor
        )
        val chevron = if (expanded) "\u25BE" else "\u25B8"
        context.drawString(font, "$chevron $title", rect.x + 3, rect.y + 2, textColor, false)
    }

    fun drawIconButton(
        context: GuiGraphics,
        font: Font,
        rect: UiRect,
        label: String,
        hovered: Boolean,
        style: UiButtonStyle
    ) {
        val backgroundColor = if (hovered) style.hoverBackgroundColor else style.normalBackgroundColor
        val textColor = if (hovered) style.hoverTextColor else style.textColor
        context.fill(rect.x, rect.y, rect.right, rect.bottom, backgroundColor)
        context.drawBorder(rect.x, rect.y, rect.width, rect.height, style.borderColor)
        val fittedLabel = fitText(font, label, (rect.width - 2).coerceAtLeast(0))
        context.drawString(
            font,
            fittedLabel,
            rect.x + (rect.width - font.width(fittedLabel)) / 2,
            rect.y + 2,
            textColor,
            false
        )
    }

    fun drawInputBox(
        context: GuiGraphics,
        rect: UiRect,
        focused: Boolean,
        backgroundColor: Int = if (focused) 0xFF333355.toInt() else 0xFF222233.toInt(),
        borderColor: Int = if (focused) 0xFFAAAAFF.toInt() else 0xFF555577.toInt()
    ) {
        context.fill(rect.x, rect.y, rect.right, rect.bottom, backgroundColor)
        context.drawBorder(rect.x, rect.y, rect.width, rect.height, borderColor)
    }

    fun drawHelpMarker(
        context: GuiGraphics,
        font: Font,
        x: Int,
        y: Int,
        hovered: Boolean
    ) {
        context.drawString(font, "?", x, y + 2, if (hovered) 0xFFFF55 else 0x777777, false)
    }

    fun fitText(font: Font, text: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (font.width(text) <= maxWidth) return text
        val ellipsis = "..."
        val ellipsisWidth = font.width(ellipsis)
        if (maxWidth <= ellipsisWidth) return font.plainSubstrByWidth(text, maxWidth)
        return font.plainSubstrByWidth(text, maxWidth - ellipsisWidth).trimEnd() + ellipsis
    }
}
