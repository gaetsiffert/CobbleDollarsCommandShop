// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

object UiModalChrome {
    const val POPUP_LAYER_Z: Float = 280f
    private const val DEFAULT_HEADER_HEIGHT = 14
    private const val DEFAULT_CONTENT_PADDING = 5
    private const val DEFAULT_OUTER_MARGIN = 4
    private const val DEFAULT_TOP_MARGIN = 16
    private const val CLOSE_BUTTON_SIZE = 10

    data class Layout(
        val panelRect: UiRect,
        val headerRect: UiRect?,
        val contentRect: UiRect,
        val footerRect: UiRect?,
        val closeRect: UiRect?
    )

    data class FrameRenderState(
        val closeHovered: Boolean
    )

    inline fun withPopupLayer(
        context: GuiGraphics,
        z: Float = POPUP_LAYER_Z,
        block: () -> Unit
    ) {
        context.pose().pushPose()
        context.pose().translate(0f, 0f, z)
        block()
        context.pose().popPose()
    }

    fun drawScrim(
        context: GuiGraphics,
        containerRect: UiRect,
        color: Int = AdminUiTheme.modalScrimColor
    ) {
        context.fill(containerRect.x, containerRect.y, containerRect.right, containerRect.bottom, color)
    }

    fun centeredLayout(
        containerRect: UiRect,
        width: Int,
        height: Int,
        headerHeight: Int = DEFAULT_HEADER_HEIGHT,
        footerHeight: Int = 0,
        padding: Int = DEFAULT_CONTENT_PADDING,
        showCloseButton: Boolean = true
    ): Layout {
        val clampedWidth = width.coerceIn(0, containerRect.width)
        val clampedHeight = height.coerceIn(0, containerRect.height)
        val panelRect = UiRect(
            x = containerRect.x + (containerRect.width - clampedWidth) / 2,
            y = containerRect.y + (containerRect.height - clampedHeight) / 2,
            width = clampedWidth,
            height = clampedHeight
        )
        return layoutForPanel(
            panelRect = panelRect,
            headerHeight = headerHeight,
            footerHeight = footerHeight,
            padding = padding,
            showCloseButton = showCloseButton
        )
    }

    fun anchoredLayout(
        containerRect: UiRect,
        anchorX: Int,
        anchorY: Int,
        width: Int,
        height: Int,
        headerHeight: Int = 0,
        footerHeight: Int = 0,
        padding: Int = 2,
        showCloseButton: Boolean = false,
        outerMargin: Int = DEFAULT_OUTER_MARGIN,
        topMargin: Int = DEFAULT_TOP_MARGIN
    ): Layout {
        val clampedWidth = width.coerceAtMost((containerRect.width - outerMargin * 2).coerceAtLeast(0))
        val clampedHeight = height.coerceAtMost((containerRect.height - outerMargin * 2).coerceAtLeast(0))
        var x = anchorX
        var y = anchorY
        if (x + clampedWidth > containerRect.right - outerMargin) {
            x = containerRect.right - outerMargin - clampedWidth
        }
        if (x < containerRect.x + outerMargin) {
            x = containerRect.x + outerMargin
        }
        if (y + clampedHeight > containerRect.bottom - outerMargin) {
            y = containerRect.bottom - outerMargin - clampedHeight
        }
        if (y < containerRect.y + topMargin) {
            y = containerRect.y + topMargin
        }
        return layoutForPanel(
            panelRect = UiRect(x, y, clampedWidth, clampedHeight),
            headerHeight = headerHeight,
            footerHeight = footerHeight,
            padding = padding,
            showCloseButton = showCloseButton
        )
    }

    fun drawFrame(
        context: GuiGraphics,
        font: Font,
        layout: Layout,
        mouseX: Int,
        mouseY: Int,
        title: String? = null,
        style: UiPanelStyle = AdminUiTheme.modalOverlay,
        titleCentered: Boolean = true
    ): FrameRenderState {
        UiChrome.drawPanel(context, layout.panelRect, style)
        layout.headerRect?.let { headerRect ->
            style.titleBarColor?.let { titleBarColor ->
                context.fill(headerRect.x, headerRect.y, headerRect.right, headerRect.bottom, titleBarColor)
            }
            title?.takeIf(String::isNotBlank)?.let { headerTitle ->
                val fittedTitle = UiChrome.fitText(font, headerTitle, (headerRect.width - 24).coerceAtLeast(0))
                val titleX = if (titleCentered) {
                    headerRect.x + (headerRect.width - font.width(fittedTitle)) / 2
                } else {
                    headerRect.x + 5
                }
                val titleY = headerRect.y + ((headerRect.height - font.lineHeight) / 2).coerceAtLeast(0)
                context.drawString(font, fittedTitle, titleX, titleY, style.titleColor, true)
            }
        }

        val closeHovered = layout.closeRect?.contains(mouseX, mouseY) == true
        layout.closeRect?.let { closeRect ->
            context.drawString(
                font,
                "x",
                closeRect.x,
                closeRect.y,
                if (closeHovered) 0xFF7777 else 0xCCCCCC,
                false
            )
        }
        return FrameRenderState(closeHovered = closeHovered)
    }

    fun centeredFooterButtonRect(
        font: Font,
        footerRect: UiRect,
        label: String,
        horizontalPadding: Int = 10,
        height: Int = 12
    ): UiRect {
        val width = UiChrome.measureButtonWidth(font, label, horizontalPadding)
            .coerceAtMost(footerRect.width.coerceAtLeast(0))
        return UiRect(
            x = footerRect.x + (footerRect.width - width) / 2,
            y = footerRect.y + ((footerRect.height - height) / 2).coerceAtLeast(0),
            width = width,
            height = height.coerceAtMost(footerRect.height.coerceAtLeast(0))
        )
    }

    fun drawScrollIndicators(
        context: GuiGraphics,
        font: Font,
        viewportRect: UiRect,
        scroll: Int,
        maxScroll: Int,
        color: Int = 0x888888
    ) {
        UiScroll.drawIndicators(context, font, viewportRect, scroll, maxScroll, color)
    }

    fun panelLayout(
        panelRect: UiRect,
        headerHeight: Int = DEFAULT_HEADER_HEIGHT,
        footerHeight: Int = 0,
        padding: Int = DEFAULT_CONTENT_PADDING,
        showCloseButton: Boolean = true
    ): Layout {
        return layoutForPanel(
            panelRect = panelRect,
            headerHeight = headerHeight,
            footerHeight = footerHeight,
            padding = padding,
            showCloseButton = showCloseButton
        )
    }

    private fun layoutForPanel(
        panelRect: UiRect,
        headerHeight: Int,
        footerHeight: Int,
        padding: Int,
        showCloseButton: Boolean
    ): Layout {
        val normalizedHeaderHeight = headerHeight.coerceAtLeast(0)
        val normalizedFooterHeight = footerHeight.coerceAtLeast(0)
        val normalizedPadding = padding.coerceAtLeast(0)
        val headerRect = if (normalizedHeaderHeight > 0) {
            UiRect(
                x = panelRect.x + 1,
                y = panelRect.y + 1,
                width = (panelRect.width - 2).coerceAtLeast(0),
                height = normalizedHeaderHeight
            )
        } else {
            null
        }
        val footerRect = if (normalizedFooterHeight > 0) {
            UiRect(
                x = panelRect.x + normalizedPadding,
                y = panelRect.bottom - normalizedPadding - normalizedFooterHeight,
                width = (panelRect.width - normalizedPadding * 2).coerceAtLeast(0),
                height = normalizedFooterHeight
            )
        } else {
            null
        }
        val contentTop = panelRect.y + if (normalizedHeaderHeight > 0) {
            normalizedHeaderHeight + normalizedPadding + 1
        } else {
            normalizedPadding
        }
        val contentBottom = panelRect.bottom - if (normalizedFooterHeight > 0) {
            normalizedFooterHeight + normalizedPadding
        } else {
            normalizedPadding
        }
        val contentRect = UiRect(
            x = panelRect.x + normalizedPadding,
            y = contentTop,
            width = (panelRect.width - normalizedPadding * 2).coerceAtLeast(0),
            height = (contentBottom - contentTop).coerceAtLeast(0)
        )
        val closeRect = if (showCloseButton && headerRect != null) {
            UiRect(
                x = panelRect.right - 14,
                y = panelRect.y + 3,
                width = CLOSE_BUTTON_SIZE,
                height = CLOSE_BUTTON_SIZE
            )
        } else {
            null
        }
        return Layout(
            panelRect = panelRect,
            headerRect = headerRect,
            contentRect = contentRect,
            footerRect = footerRect,
            closeRect = closeRect
        )
    }
}
