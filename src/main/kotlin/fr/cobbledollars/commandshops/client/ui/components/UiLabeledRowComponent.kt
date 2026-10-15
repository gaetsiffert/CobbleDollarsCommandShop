// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.components

import fr.cobbledollars.commandshops.client.ui.BaseUiComponent
import fr.cobbledollars.commandshops.client.ui.UiHelpInlineLabel
import fr.cobbledollars.commandshops.client.ui.UiText
import fr.cobbledollars.commandshops.client.ui.UiRect
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

abstract class UiLabeledRowComponent(
    protected val label: String,
    private val tooltipProvider: (() -> List<Component>?)? = null,
    open override val preferredHeight: Int = 12,
    private val labelColor: Int = 0xAAAAFF
) : BaseUiComponent() {
    private var tooltipBounds: UiRect? = null

    protected fun drawLabel(
        context: GuiGraphics,
        font: Font,
        mouseX: Int,
        mouseY: Int
    ): Int {
        val labelRender = UiHelpInlineLabel.render(
            context = context,
            font = font,
            bounds = bounds,
            mouseX = mouseX,
            mouseY = mouseY,
            labelText = "${UiText.admin(label)}: ",
            textColor = labelColor,
            hasTooltip = tooltipProvider != null,
            startX = 4,
            yOffset = 2,
            maxRightX = bounds.x + (bounds.width * 45 / 100).coerceAtLeast(48),
            afterSpacing = 2
        )
        tooltipBounds = labelRender.tooltipRect
        return labelRender.contentStartX
    }

    override fun tooltip(mouseX: Int, mouseY: Int): List<Component>? {
        val provider = tooltipProvider ?: return null
        return if (tooltipBounds?.contains(mouseX, mouseY) == true) provider() else null
    }
}
