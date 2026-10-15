// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.components

import fr.cobbledollars.commandshops.client.ui.BaseUiComponent
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

class UiSpacerComponent(
    override val preferredHeight: Int = 6
) : BaseUiComponent() {
    override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) = Unit
}
