// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.components

import fr.cobbledollars.commandshops.client.ui.BaseUiComponent
import fr.cobbledollars.commandshops.client.ui.UiHelpInlineLabel
import fr.cobbledollars.commandshops.client.ui.UiRect
import fr.cobbledollars.commandshops.client.ui.UiText
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

class UiSectionHeaderComponent(
    private val label: String,
    private val color: Int,
    private val tooltipProvider: (() -> List<Component>?)? = null,
    override val preferredHeight: Int = 14
) : BaseUiComponent() {
    private var tooltipBounds: UiRect? = null

    override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(bounds.x + 2, bounds.y, bounds.right - 2, bounds.bottom, color)
        tooltipBounds = UiHelpInlineLabel.render(
            context = context,
            font = font,
            bounds = bounds,
            mouseX = mouseX,
            mouseY = mouseY,
            labelText = UiText.admin(label),
            textColor = 0xFFFFFF,
            hasTooltip = tooltipProvider != null,
            startX = 5,
            yOffset = 3,
            maxRightX = bounds.right - 4,
            afterSpacing = 0,
            drawText = true,
            helpYOffset = 1
        ).tooltipRect
    }

    override fun tooltip(mouseX: Int, mouseY: Int): List<Component>? {
        val provider = tooltipProvider ?: return null
        return if (tooltipBounds?.contains(mouseX, mouseY) == true) provider() else null
    }
}
