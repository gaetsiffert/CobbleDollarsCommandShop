// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

import net.minecraft.client.gui.GuiGraphics

class UiScrollPanel(
    private val scrollStep: Int = 12
) {
    var bounds: UiRect = UiRect.EMPTY
        private set

    var scrollOffset: Int = 0
        private set

    private var contentHeight: Int = 0

    val hasScrollAbove: Boolean
        get() = scrollOffset > 0

    val hasScrollBelow: Boolean
        get() = scrollOffset < maxScroll()

    fun layout(bounds: UiRect) {
        this.bounds = bounds
        clampScroll()
    }

    fun setContentHeight(contentHeight: Int) {
        this.contentHeight = contentHeight.coerceAtLeast(0)
        clampScroll()
    }

    fun reset() {
        scrollOffset = 0
    }

    fun render(
        context: GuiGraphics,
        contentHeight: Int = this.contentHeight,
        renderContent: (Int) -> Unit
    ) {
        setContentHeight(contentHeight)
        UiScroll.withViewport(context, bounds) {
            renderContent(scrollOffset)
        }
    }

    fun mouseScrolled(mouseX: Int, mouseY: Int, verticalAmount: Double): Boolean {
        if (!bounds.contains(mouseX, mouseY)) return false
        val nextOffset = (scrollOffset - verticalAmount.toInt() * scrollStep).coerceIn(0, maxScroll())
        if (nextOffset == scrollOffset) return false
        scrollOffset = nextOffset
        return true
    }

    private fun clampScroll() {
        scrollOffset = UiScroll.clamp(scrollOffset, contentHeight, bounds.height)
    }

    private fun maxScroll(): Int = UiScroll.maxOffset(contentHeight, bounds.height)
}
