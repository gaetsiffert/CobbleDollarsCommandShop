// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.overlay

import fr.cobbledollars.commandshops.client.ui.AdminUiTheme
import fr.cobbledollars.commandshops.client.ui.UiChrome
import fr.cobbledollars.commandshops.client.ui.UiColorCodec
import fr.cobbledollars.commandshops.client.ui.UiModalChrome
import fr.cobbledollars.commandshops.client.ui.UiRect
import fr.cobbledollars.commandshops.client.ui.UiText
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

class UiColorPickerOverlay {
    enum class Channel { RED, GREEN, BLUE }

    data class State(
        val title: String,
        val color: Int,
        val guiLeft: Int,
        val guiTop: Int,
        val guiWidth: Int,
        val guiHeight: Int
    )

    sealed class Action {
        data class SetColor(val color: Int) : Action()
        data class SetChannel(val channel: Channel, val value: Int) : Action()
        data object Apply : Action()
        data object Close : Action()
        data object None : Action()
    }

    private data class SliderLayout(
        val channel: Channel,
        val labelRect: UiRect,
        val trackRect: UiRect,
        val valueRect: UiRect
    )

    private data class Layout(
        val frame: UiModalChrome.Layout,
        val previewLabelRect: UiRect,
        val previewSwatchRect: UiRect,
        val paletteRects: List<Pair<UiRect, Int>>,
        val sliderLayouts: List<SliderLayout>,
        val applyRect: UiRect
    )

    fun render(
        context: GuiGraphics,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        state: State
    ) {
        val layout = layout(font, state)
        val red = UiColorCodec.red(state.color)
        val green = UiColorCodec.green(state.color)
        val blue = UiColorCodec.blue(state.color)

        UiModalChrome.withPopupLayer(context) {
            UiModalChrome.drawScrim(context, containerRect(state))
            UiModalChrome.drawFrame(
                context = context,
                font = font,
                layout = layout.frame,
                mouseX = mouseX,
                mouseY = mouseY,
                title = state.title,
                style = AdminUiTheme.modalOverlay
            )

            context.drawString(
                font,
                UiText.admin("Preview"),
                layout.previewLabelRect.x,
                layout.previewLabelRect.y + 2,
                0xBBBBCC,
                false
            )
            drawSwatch(context, layout.previewSwatchRect, state.color, true)
            context.drawString(
                font,
                UiColorCodec.formatHex(state.color),
                layout.previewSwatchRect.right + 6,
                layout.previewSwatchRect.y + 5,
                0xFFFFFF,
                false
            )

            for ((rect, color) in layout.paletteRects) {
                drawSwatch(context, rect, color, mouseX in rect.x..rect.right && mouseY in rect.y..rect.bottom)
            }

            for (slider in layout.sliderLayouts) {
                val channelValue = when (slider.channel) {
                    Channel.RED -> red
                    Channel.GREEN -> green
                    Channel.BLUE -> blue
                }
                context.drawString(
                    font,
                    UiText.admin(
                        when (slider.channel) {
                            Channel.RED -> "Red"
                            Channel.GREEN -> "Green"
                            Channel.BLUE -> "Blue"
                        }
                    ),
                    slider.labelRect.x,
                    slider.labelRect.y + 2,
                    0xBBBBCC,
                    false
                )
                drawChannelSlider(
                    context = context,
                    rect = slider.trackRect,
                    channel = slider.channel,
                    red = red,
                    green = green,
                    blue = blue,
                    selectedValue = channelValue
                )
                context.drawString(
                    font,
                    channelValue.toString(),
                    slider.valueRect.x,
                    slider.valueRect.y + 2,
                    0xFFFFFF,
                    false
                )
            }

            UiChrome.drawButton(
                context = context,
                font = font,
                rect = layout.applyRect,
                label = UiText.admin("Apply"),
                hovered = layout.applyRect.contains(mouseX, mouseY),
                style = AdminUiTheme.successButton
            )
        }
    }

    fun mouseClicked(mouseX: Int, mouseY: Int, state: State): Action {
        val layout = layout(null, state)
        if (layout.frame.closeRect?.contains(mouseX, mouseY) == true) return Action.Close
        if (!layout.frame.panelRect.contains(mouseX, mouseY)) return Action.Close
        if (layout.applyRect.contains(mouseX, mouseY)) return Action.Apply
        layout.paletteRects.firstOrNull { (rect, _) -> rect.contains(mouseX, mouseY) }?.let { (_, color) ->
            return Action.SetColor(color)
        }
        layout.sliderLayouts.firstOrNull { it.trackRect.contains(mouseX, mouseY) }?.let { slider ->
            return Action.SetChannel(
                channel = slider.channel,
                value = valueAt(slider.trackRect, mouseX)
            )
        }
        return Action.None
    }

    fun panelRect(state: State): UiRect = layout(null, state).frame.panelRect

    private fun drawSwatch(context: GuiGraphics, rect: UiRect, color: Int, hovered: Boolean) {
        context.fill(rect.x, rect.y, rect.right, rect.bottom, 0xFF000000.toInt())
        context.fill(rect.x + 1, rect.y + 1, rect.right - 1, rect.bottom - 1, 0xFF000000.toInt() or color)
        if (hovered) {
            UiChrome.drawHoverOverlay(
                context = context,
                rect = UiRect(rect.x + 1, rect.y + 1, rect.width - 2, rect.height - 2),
                hovered = true,
                overlayColor = 0x22FFFFFF
            )
        }
    }

    private fun drawChannelSlider(
        context: GuiGraphics,
        rect: UiRect,
        channel: Channel,
        red: Int,
        green: Int,
        blue: Int,
        selectedValue: Int
    ) {
        context.fill(rect.x, rect.y, rect.right, rect.bottom, 0xFF000000.toInt())
        val innerWidth = (rect.width - 2).coerceAtLeast(1)
        for (offset in 0 until innerWidth) {
            val value = (offset * 255) / (innerWidth - 1).coerceAtLeast(1)
            val color = when (channel) {
                Channel.RED -> UiColorCodec.rgb(value, green, blue)
                Channel.GREEN -> UiColorCodec.rgb(red, value, blue)
                Channel.BLUE -> UiColorCodec.rgb(red, green, value)
            }
            context.fill(rect.x + 1 + offset, rect.y + 1, rect.x + 2 + offset, rect.bottom - 1, 0xFF000000.toInt() or color)
        }
        val markerX = rect.x + 1 + ((selectedValue * (innerWidth - 1)) / 255)
        context.fill(markerX, rect.y, markerX + 1, rect.bottom, 0xFFFFFFFF.toInt())
        if (markerX + 1 < rect.right) {
            context.fill(markerX + 1, rect.y + 1, markerX + 2, rect.bottom - 1, 0xFF000000.toInt())
        }
    }

    private fun valueAt(trackRect: UiRect, mouseX: Int): Int {
        val innerWidth = (trackRect.width - 2).coerceAtLeast(1)
        val localX = (mouseX - (trackRect.x + 1)).coerceIn(0, innerWidth - 1)
        return (localX * 255) / (innerWidth - 1).coerceAtLeast(1)
    }

    private fun containerRect(state: State): UiRect =
        UiRect(state.guiLeft, state.guiTop, state.guiWidth, state.guiHeight)

    private fun layout(font: Font?, state: State): Layout {
        val frame = UiModalChrome.centeredLayout(
            containerRect = containerRect(state),
            width = (state.guiWidth * 0.56).toInt().coerceIn(220, 300),
            height = 174,
            footerHeight = 18
        )
        val previewLabelRect = UiRect(frame.contentRect.x, frame.contentRect.y, 46, 12)
        val previewSwatchRect = UiRect(previewLabelRect.right + 4, frame.contentRect.y, 24, 12)

        val paletteTop = previewSwatchRect.bottom + 8
        val swatchSize = 12
        val swatchGap = 4
        val paletteRects = PALETTE_COLORS.mapIndexed { index, color ->
            val column = index % 6
            val row = index / 6
            UiRect(
                x = frame.contentRect.x + column * (swatchSize + swatchGap),
                y = paletteTop + row * (swatchSize + swatchGap),
                width = swatchSize,
                height = swatchSize
            ) to color
        }

        val sliderStartY = paletteTop + 2 * (swatchSize + swatchGap) + 6
        val sliderTrackWidth = (frame.contentRect.width - 64).coerceAtLeast(90)
        val sliderLayouts = Channel.entries.mapIndexed { index, channel ->
            val rowY = sliderStartY + index * 16
            val labelRect = UiRect(frame.contentRect.x, rowY, 28, 12)
            val trackRect = UiRect(labelRect.right + 4, rowY + 2, sliderTrackWidth, 8)
            val valueRect = UiRect(trackRect.right + 4, rowY, 24, 12)
            SliderLayout(channel, labelRect, trackRect, valueRect)
        }

        val footerRect = frame.footerRect ?: UiRect.EMPTY
        val applyLabel = UiText.admin("Apply")
        val applyRect = UiModalChrome.centeredFooterButtonRect(
            font = font ?: net.minecraft.client.Minecraft.getInstance().font,
            footerRect = footerRect,
            label = applyLabel
        )

        return Layout(
            frame = frame,
            previewLabelRect = previewLabelRect,
            previewSwatchRect = previewSwatchRect,
            paletteRects = paletteRects,
            sliderLayouts = sliderLayouts,
            applyRect = applyRect
        )
    }

    companion object {
        private val PALETTE_COLORS = listOf(
            0xE05555, 0xF39C12, 0xF1C40F, 0x2ECC71, 0x3498DB, 0x9B59B6,
            0xFFFFFF, 0xBFBFBF, 0x808080, 0x4D4D4D, 0x1E1E1E, 0x000000
        )
    }
}
