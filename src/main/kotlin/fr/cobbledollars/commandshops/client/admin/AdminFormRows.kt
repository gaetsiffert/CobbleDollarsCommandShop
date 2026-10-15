package fr.cobbledollars.commandshops.client.admin

import fr.cobbledollars.commandshops.client.ui.*
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/** Navigation and collection actions drawn with the same chrome as the reference admin. */
internal class AdminActionRow(
    private val label: String,
    private val actions: List<Pair<String, () -> Unit>>,
    private val hint: String = "",
    private val color: Int = 0xCCCCCC,
    private val actionHints: List<String> = emptyList(),
    private val labelAboveButtons: Boolean = false
) : BaseUiComponent() {
    private val stacked = labelAboveButtons
    override val preferredHeight = if (stacked) 30 else 16
    private data class ActionButton(val rect: UiRect, val action: () -> Unit, val hint: String?)
    private var buttons = emptyList<ActionButton>()
    private var helpRect: UiRect? = null

    override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {
        val spacing = 3
        val widths = actions.map { (text, _) -> UiChrome.measureButtonWidth(font, text).coerceAtLeast(14) }.toMutableList()
        val availableWidth = (bounds.width - 8 - spacing * (actions.size - 1).coerceAtLeast(0)).coerceAtLeast(actions.size)
        // Preserve icon sizes and shrink long captions only when the complete button group will not fit.
        var excess = widths.sum() - availableWidth
        while (excess > 0) {
            val widest = widths.indices.maxByOrNull { widths[it] } ?: break
            if (widths[widest] <= 1) break
            widths[widest]--
            excess--
        }
        val groupWidth = widths.sum() + spacing * (actions.size - 1).coerceAtLeast(0)
        var nextX = bounds.right - 3 - groupWidth
        val labelRight = if (stacked || actions.isEmpty()) bounds.right - 4 else nextX - spacing
        helpRect = UiHelpInlineLabel.render(
            context, font, UiRect(bounds.x, bounds.y, bounds.width, 14), mouseX, mouseY,
            label, color, hint.isNotBlank(), startX = 5, yOffset = 3,
            maxRightX = labelRight, helpYOffset = 1
        ).tooltipRect
        buttons = actions.mapIndexed { index, (text, action) ->
            val rect = UiRect(nextX, bounds.y + if (stacked) 16 else 1, widths[index], 12)
            UiChrome.drawButton(context, font, rect, text, rect.contains(mouseX, mouseY), AdminUiTheme.neutralButton)
            nextX = rect.right + spacing
            ActionButton(rect, action, actionHints.getOrNull(index)?.takeIf(String::isNotBlank))
        }
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (button != 0 || !bounds.contains(mouseX, mouseY)) return false
        val target = buttons.firstOrNull { it.rect.contains(mouseX, mouseY) } ?: return false
        target.action()
        return true
    }

    override fun tooltip(mouseX: Int, mouseY: Int): List<Component>? {
        if (!bounds.contains(mouseX, mouseY)) return null
        buttons.firstOrNull { it.rect.contains(mouseX, mouseY) }?.let { button ->
            return button.hint?.let { listOf(Component.literal(it)) }
        }
        return if (helpRect?.contains(mouseX, mouseY) == true && hint.isNotBlank()) listOf(Component.literal(hint)) else null
    }
}

internal class AdminRemovableRow(
    private val content: UiComponent,
    private val remove: () -> Unit,
    private val removeHint: String? = null
) : BaseUiComponent() {
    // Keep existing trailing-lambda call sites source-compatible.
    constructor(content: UiComponent, remove: () -> Unit) : this(content, remove, null)

    override val preferredHeight get() = content.preferredHeight
    override val isFocusable get() = content.isFocusable
    private fun removeRect() = UiRect(bounds.right - 16, bounds.y, 13, 12)
    override fun layout(bounds: UiRect) {
        this.bounds = bounds
        content.layout(UiRect(bounds.x, bounds.y, (bounds.width - 18).coerceAtLeast(1), bounds.height))
    }
    override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {
        content.render(context, font, mouseX, mouseY, delta)
        val rect = removeRect()
        UiChrome.drawButton(context, font, rect, "−", rect.contains(mouseX, mouseY), AdminUiTheme.dangerButton)
    }
    override fun wantsFocus(mouseX: Int, mouseY: Int) = !removeRect().contains(mouseX, mouseY) && content.wantsFocus(mouseX, mouseY)
    override fun focusChanged(focused: Boolean) = content.focusChanged(focused)
    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (button == 0 && removeRect().contains(mouseX, mouseY)) { remove(); return true }
        return content.mouseClicked(mouseX, mouseY, button)
    }
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int) = content.keyPressed(keyCode, scanCode, modifiers)
    override fun charTyped(char: Char, modifiers: Int) = content.charTyped(char, modifiers)
    override fun tooltip(mouseX: Int, mouseY: Int): List<Component>? {
        if (!bounds.contains(mouseX, mouseY)) return null
        if (removeRect().contains(mouseX, mouseY)) return listOf(
            removeHint?.takeIf(String::isNotBlank)?.let(Component::literal)
                ?: Component.translatable("gui.cobbledollarscommandshops.admin.tooltip.remove_option")
        )
        return content.tooltip(mouseX, mouseY)
    }
}
