// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

object UiScroll {
    fun maxOffset(contentHeight: Int, viewportHeight: Int): Int =
        (contentHeight - viewportHeight).coerceAtLeast(0)

    fun clamp(offset: Int, contentHeight: Int, viewportHeight: Int): Int =
        offset.coerceIn(0, maxOffset(contentHeight, viewportHeight))

    inline fun withViewport(
        context: GuiGraphics,
        viewportRect: UiRect,
        renderContent: () -> Unit
    ) {
        context.enableScissor(viewportRect.x, viewportRect.y, viewportRect.right, viewportRect.bottom)
        renderContent()
        context.disableScissor()
    }

    fun drawIndicators(
        context: GuiGraphics,
        font: Font,
        viewportRect: UiRect,
        scroll: Int,
        maxScroll: Int,
        color: Int = 0x888888
    ) {
        if (maxScroll <= 0) return
        if (scroll > 0) {
            context.drawString(font, "^", viewportRect.right - 8, viewportRect.y, color, false)
        }
        if (scroll < maxScroll) {
            context.drawString(font, "v", viewportRect.right - 8, viewportRect.bottom - 8, color, false)
        }
    }
}
