// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.overlay

import fr.cobbledollars.commandshops.client.ui.AdminUiTheme
import fr.cobbledollars.commandshops.client.ui.UiChrome
import fr.cobbledollars.commandshops.client.ui.UiModalChrome
import fr.cobbledollars.commandshops.client.ui.UiRect
import fr.cobbledollars.commandshops.client.ui.components.UiSingleLineEditorState
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

class UiItemPickerOverlay(
    private val title: () -> String,
    private val searchPlaceholder: () -> String
) {
    data class State(
        val search: String,
        val searchFocused: Boolean,
        val scroll: Int,
        val results: List<Item>,
        val guiLeft: Int,
        val guiTop: Int,
        val guiWidth: Int,
        val guiHeight: Int
    )

    sealed class Action {
        data class Select(val itemId: String) : Action()
        data object Close : Action()
        data object None : Action()
    }

    fun render(
        context: GuiGraphics,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        state: State,
        searchEditor: UiSingleLineEditorState? = null
    ) {
        val layout = layout(state)
        UiModalChrome.withPopupLayer(context) {
            UiModalChrome.drawScrim(context, containerRect(state))
            UiModalChrome.drawFrame(
                context = context,
                font = font,
                layout = layout.frame,
                mouseX = mouseX,
                mouseY = mouseY,
                title = title(),
                style = AdminUiTheme.modalOverlay
            )

            val searchRect = layout.searchRect
            UiChrome.drawInputBox(
                context = context,
                rect = searchRect,
                focused = state.searchFocused,
                backgroundColor = if (state.searchFocused) 0xFF333355.toInt() else 0xFF222233.toInt(),
                borderColor = if (state.searchFocused) 0xFFAAAAFF.toInt() else 0xFF555577.toInt()
            )
            if (!state.searchFocused) {
                UiChrome.drawHoverOverlay(
                    context,
                    UiRect(searchRect.x + 1, searchRect.y + 1, searchRect.width - 2, searchRect.height - 2),
                    searchRect.contains(mouseX, mouseY),
                    0x33444466
                )
            }
            if (searchEditor != null && state.searchFocused) {
                if (state.search.isEmpty()) {
                    context.drawString(font, searchPlaceholder(), searchRect.x + 2, searchRect.y + 2, 0x666666, false)
                }
                searchEditor.render(context, font, searchRect, 0xFFFFFF)
            } else {
                val searchDisplay = if (state.search.isEmpty()) searchPlaceholder() else state.search
                context.drawString(
                    font,
                    UiChrome.fitText(font, searchDisplay, searchRect.width - 4),
                    searchRect.x + 2,
                    searchRect.y + 2,
                    if (state.search.isEmpty()) 0x666666 else 0xFFFFFF,
                    false
                )
            }

            context.fill(layout.gridRect.x, layout.gridRect.y, layout.gridRect.right, layout.gridRect.bottom, 0xFF101018.toInt())
            context.renderOutline(layout.gridRect.x, layout.gridRect.y, layout.gridRect.width, layout.gridRect.height, 0xFF44445A.toInt())
            var index = state.scroll * layout.columns
            for (row in 0 until layout.visibleRows) {
                for (column in 0 until layout.columns) {
                    if (index >= state.results.size) break
                    val item = state.results[index]
                    val stack = ItemStack(item)
                    val x = layout.gridRect.x + 2 + column * layout.cellSize
                    val y = layout.gridRect.y + 2 + row * layout.cellSize
                    val hovered = mouseX in x..(x + 16) && mouseY in y..(y + 16)
                    if (hovered) {
                        context.fill(x, y, x + 16, y + 16, 0x44FFFFFF)
                    }
                    context.renderItem(stack, x, y)
                    if (hovered) {
                        context.renderTooltip(font, stack, mouseX, mouseY)
                    }
                    index++
                }
            }

            UiModalChrome.drawScrollIndicators(
                context = context,
                font = font,
                viewportRect = layout.gridRect,
                scroll = state.scroll,
                maxScroll = maxScroll(state)
            )
        }
    }

    fun mouseClicked(mouseX: Int, mouseY: Int, state: State): Action {
        val layout = layout(state)
        if (layout.frame.closeRect?.contains(mouseX, mouseY) == true) return Action.Close
        if (!layout.frame.panelRect.contains(mouseX, mouseY)) return Action.Close
        if (mouseX !in layout.gridRect.x..layout.gridRect.right || mouseY !in layout.gridRect.y..layout.gridRect.bottom) {
            return Action.None
        }
        val column = (mouseX - layout.gridRect.x - 2) / layout.cellSize
        val row = (mouseY - layout.gridRect.y - 2) / layout.cellSize
        if (column !in 0 until layout.columns || row !in 0 until layout.visibleRows) return Action.None
        val index = (state.scroll + row) * layout.columns + column
        if (index !in state.results.indices) return Action.None
        return Action.Select(BuiltInRegistries.ITEM.getKey(state.results[index]).toString())
    }

    fun maxScroll(state: State): Int {
        val layout = layout(state)
        return ((state.results.size + layout.columns - 1) / layout.columns - layout.visibleRows).coerceAtLeast(0)
    }

    fun searchRect(state: State): UiRect = layout(state).searchRect

    fun panelRect(state: State): UiRect = layout(state).frame.panelRect

    private fun containerRect(state: State): UiRect = UiRect(state.guiLeft, state.guiTop, state.guiWidth, state.guiHeight)

    private data class Layout(
        val frame: UiModalChrome.Layout,
        val searchRect: UiRect,
        val gridRect: UiRect,
        val columns: Int,
        val visibleRows: Int,
        val cellSize: Int
    )

    private fun layout(state: State): Layout {
        val frame = UiModalChrome.centeredLayout(
            containerRect = containerRect(state),
            width = (state.guiWidth * 0.85).toInt().coerceIn(200, 480),
            height = (state.guiHeight * 0.85).toInt().coerceIn(150, 340)
        )
        val searchRect = UiRect(
            x = frame.contentRect.x,
            y = frame.contentRect.y,
            width = frame.contentRect.width,
            height = 12
        )
        val cellSize = 18
        val gridRect = UiRect(
            x = frame.contentRect.x,
            y = searchRect.bottom + 2,
            width = frame.contentRect.width,
            height = (frame.contentRect.height - searchRect.height - 2).coerceAtLeast(0)
        )
        val columns = ((gridRect.width - 4) / cellSize).coerceAtLeast(1)
        val visibleRows = ((gridRect.height - 4) / cellSize).coerceAtLeast(1)
        return Layout(
            frame = frame,
            searchRect = searchRect,
            gridRect = gridRect,
            columns = columns,
            visibleRows = visibleRows,
            cellSize = cellSize
        )
    }
}
