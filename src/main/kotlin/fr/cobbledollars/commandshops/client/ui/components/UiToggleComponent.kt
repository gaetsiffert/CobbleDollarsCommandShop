// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.components

import fr.cobbledollars.commandshops.client.ui.AdminUiTheme
import fr.cobbledollars.commandshops.client.ui.FieldBinding
import fr.cobbledollars.commandshops.client.ui.UiChrome
import fr.cobbledollars.commandshops.client.ui.UiRect
import fr.cobbledollars.commandshops.client.ui.UiText
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

class UiToggleComponent(
    label: String,
    private val binding: FieldBinding<Boolean>,
    private val trueLabel: String = "On",
    private val falseLabel: String = "Off",
    tooltipProvider: (() -> List<Component>?)? = null
) : UiLabeledRowComponent(label, tooltipProvider = tooltipProvider) {
    override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {
        drawLabel(context, font, mouseX, mouseY)

        val trueText = UiText.admin(trueLabel)
        val falseText = UiText.admin(falseLabel)
        val buttonLabel = if (binding.get()) trueText else falseText
        val buttonWidth = maxOf(font.width(trueText), font.width(falseText)) + 10
        val buttonRect = UiRect(bounds.right - buttonWidth - 4, bounds.y, buttonWidth, preferredHeight)
        val hovered = buttonRect.contains(mouseX, mouseY)
        val style = if (binding.get()) AdminUiTheme.successButton else AdminUiTheme.neutralButton
        UiChrome.drawButton(context, font, buttonRect, buttonLabel, hovered, style)
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (!bounds.contains(mouseX, mouseY)) return false
        if (super.tooltip(mouseX, mouseY) != null) return false
        binding.set(!binding.get())
        return true
    }
}
