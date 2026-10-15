// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.overlay

import fr.cobbledollars.commandshops.client.ui.AdminUiTheme
import fr.cobbledollars.commandshops.client.ui.UiChrome
import fr.cobbledollars.commandshops.client.ui.UiModalChrome
import fr.cobbledollars.commandshops.client.ui.UiRect
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

class UiDropdownOverlay(
    private val doneLabel: () -> String
) {
    data class State(
        val key: String?,
        val options: List<String>,
        val selected: Set<String>,
        val scroll: Int,
        val multiSelect: Boolean,
        val anchorX: Int,
        val anchorY: Int,
        val guiLeft: Int,
        val guiTop: Int,
        val guiWidth: Int,
        val guiHeight: Int
    )

    sealed class Action {
        data class Toggle(val option: String) : Action()
        data object Apply : Action()
        data object Dismiss : Action()
    }

    fun render(
        context: GuiGraphics,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        state: State,
        displayLabel: (String) -> String,
        optionColor: (String) -> Int
    ) {
        if (state.key == null) return
        val layout = layout(font, state, displayLabel)
        val maxVisible = layout.visibleRows
        var drawY = layout.listRect.y + 2

        UiModalChrome.withPopupLayer(context) {
            UiModalChrome.drawFrame(
                context = context,
                font = font,
                layout = layout.frame,
                mouseX = mouseX,
                mouseY = mouseY,
                title = null,
                style = AdminUiTheme.modalOverlay
            )

            for (i in state.scroll until (state.scroll + maxVisible).coerceAtMost(state.options.size)) {
                val option = state.options[i]
                val label = displayLabel(option)
                val selected = option in state.selected
                val hovered = mouseX in layout.listRect.x..layout.listRect.right && mouseY in drawY..(drawY + 12)
                val color = optionColor(option)
                val rowLeft = layout.listRect.x + 1
                val rowRight = layout.listRect.right - 1

                if (state.multiSelect) {
                    UiChrome.drawInteractiveRow(
                        context = context,
                        rect = UiRect(rowLeft, drawY, layout.listRect.width - 2, 12),
                        hovered = hovered,
                        selected = selected,
                        selectedColor = (0x88000000L or color.toLong()).toInt(),
                        hoverOverlayColor = 0x44FFFFFF
                    )
                    val check = if (selected) "\u2713 " else "  "
                    if (state.key.startsWith("hazard_color_")) {
                        context.fill(layout.listRect.x + 4, drawY + 2, layout.listRect.x + 12, drawY + 10, (0xFF000000L or color.toLong()).toInt())
                        context.renderOutline(layout.listRect.x + 4, drawY + 2, 8, 8, 0xFFCCCCCC.toInt())
                        context.drawString(
                            font,
                            UiChrome.fitText(font, check + label, rowRight - layout.listRect.x - 19),
                            layout.listRect.x + 15,
                            drawY + 2,
                            if (selected) 0xFFFFFF else 0xCCCCCC,
                            false
                        )
                    } else {
                        context.drawString(
                            font,
                            UiChrome.fitText(font, check + label, rowRight - layout.listRect.x - 8),
                            layout.listRect.x + 4,
                            drawY + 2,
                            if (selected) 0xFFFFFF else 0xCCCCCC,
                            false
                        )
                    }
                } else {
                    UiChrome.drawInteractiveRow(
                        context = context,
                        rect = UiRect(rowLeft, drawY, layout.listRect.width - 2, 12),
                        hovered = hovered,
                        selected = selected,
                        selectedColor = 0xAA224433.toInt(),
                        hoverOverlayColor = 0x66334455
                    )
                    if (selected) context.renderOutline(rowLeft, drawY, layout.listRect.width - 2, 12, 0xFF55AA55.toInt())
                    if (state.key.startsWith("hazard_color_")) {
                        context.fill(layout.listRect.x + 4, drawY + 2, layout.listRect.x + 12, drawY + 10, (0xFF000000L or color.toLong()).toInt())
                        context.renderOutline(layout.listRect.x + 4, drawY + 2, 8, 8, 0xFFCCCCCC.toInt())
                        context.drawString(
                            font,
                            UiChrome.fitText(font, label, rowRight - layout.listRect.x - 24),
                            layout.listRect.x + 15,
                            drawY + 2,
                            if (selected) 0x55FF55 else if (hovered) 0xFFFFFF else 0xCCCCCC,
                            false
                        )
                    } else {
                        context.drawString(
                            font,
                            UiChrome.fitText(font, label, rowRight - layout.listRect.x - 18),
                            layout.listRect.x + 6,
                            drawY + 2,
                            if (selected) 0x55FF55 else if (hovered) 0xFFFFFF else 0xCCCCCC,
                            false
                        )
                    }
                    if (selected) context.drawString(font, ">", layout.listRect.right - 9, drawY + 2, 0x55FF55, false)
                }
                drawY += 12
            }

            layout.doneButtonRect?.let { doneRect ->
                val label = doneLabel()
                UiChrome.drawButton(
                    context = context,
                    font = font,
                    rect = doneRect,
                    label = UiChrome.fitText(font, label, (doneRect.width - 8).coerceAtLeast(0)),
                    hovered = doneRect.contains(mouseX, mouseY),
                    style = AdminUiTheme.successButton
                )
            }
            UiModalChrome.drawScrollIndicators(
                context = context,
                font = font,
                viewportRect = layout.listRect,
                scroll = state.scroll,
                maxScroll = maxScroll(font, state, displayLabel)
            )
        }
    }

    fun mouseClicked(font: Font, mouseX: Int, mouseY: Int, state: State, displayLabel: (String) -> String): Action {
        if (state.key == null) return Action.Dismiss
        val layout = layout(font, state, displayLabel)
        if (layout.doneButtonRect?.contains(mouseX, mouseY) == true) return Action.Apply
        if (layout.listRect.contains(mouseX, mouseY)) {
            val rowIndex = state.scroll + (mouseY - layout.listRect.y - 2) / 12
            if (rowIndex in state.options.indices) return Action.Toggle(state.options[rowIndex])
        }
        return Action.Dismiss
    }

    fun hoveredOption(font: Font, mouseX: Int, mouseY: Int, state: State, displayLabel: (String) -> String): String? {
        if (state.key == null) return null
        val layout = layout(font, state, displayLabel)
        if (!layout.listRect.contains(mouseX, mouseY)) {
            return null
        }
        val rowIndex = state.scroll + (mouseY - layout.listRect.y - 2) / 12
        return state.options.getOrNull(rowIndex)
    }

    fun bounds(font: Font, state: State, displayLabel: (String) -> String): Bounds {
        val layout = layout(font, state, displayLabel)
        val footerHeight = layout.frame.footerRect?.height ?: 0
        return Bounds(
            x = layout.frame.panelRect.x,
            y = layout.frame.panelRect.y,
            width = layout.frame.panelRect.width,
            height = layout.frame.panelRect.height - footerHeight,
            footerHeight = footerHeight
        )
    }

    fun maxScroll(font: Font, state: State, displayLabel: (String) -> String): Int {
        val layout = layout(font, state, displayLabel)
        return (state.options.size - layout.visibleRows).coerceAtLeast(0)
    }

    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int, val footerHeight: Int)

    private data class Layout(
        val frame: UiModalChrome.Layout,
        val listRect: UiRect,
        val visibleRows: Int,
        val doneButtonRect: UiRect?
    )

    private fun layout(font: Font, state: State, displayLabel: (String) -> String): Layout {
        val rawWidth = (state.options.maxOfOrNull {
            font.width(displayLabel(it)) + if (state.key?.startsWith("hazard_color_") == true) 12 else 0
        } ?: 60) + 28
        val width = rawWidth.coerceAtMost((state.guiWidth - 8).coerceAtLeast(40))
        val listHeight = (state.options.size * 12 + 4).coerceAtMost(180)
        val footerHeight = if (state.multiSelect) 18 else 0
        val padding = 2
        val frame = UiModalChrome.anchoredLayout(
            containerRect = UiRect(state.guiLeft, state.guiTop, state.guiWidth, state.guiHeight),
            anchorX = state.anchorX,
            anchorY = state.anchorY,
            width = width,
            height = listHeight + footerHeight + padding * 2,
            footerHeight = footerHeight,
            padding = padding
        )
        val listRect = frame.contentRect
        val visibleRows = ((listRect.height - 4) / 12).coerceAtLeast(1)
        val doneButtonRect = frame.footerRect?.let { footerRect ->
            UiModalChrome.centeredFooterButtonRect(font, footerRect, doneLabel(), horizontalPadding = 8)
        }
        return Layout(
            frame = frame,
            listRect = listRect,
            visibleRows = visibleRows,
            doneButtonRect = doneButtonRect
        )
    }
}
