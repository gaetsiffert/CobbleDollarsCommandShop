// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.components

import fr.cobbledollars.commandshops.client.ui.BaseUiComponent
import fr.cobbledollars.commandshops.client.ui.AdminUiTheme
import fr.cobbledollars.commandshops.client.ui.FieldBinding
import fr.cobbledollars.commandshops.client.ui.UiChrome
import fr.cobbledollars.commandshops.client.ui.UiHelpInlineLabel
import fr.cobbledollars.commandshops.client.ui.UiIcons
import fr.cobbledollars.commandshops.client.ui.UiRect
import fr.cobbledollars.commandshops.client.ui.UiText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

class UiRewardCommandsComponent(
    private val binding: FieldBinding<List<RewardCommandEntry>>,
    private val outcomeOptions: List<String>,
    private val colorProvider: (String) -> Int,
    private val openDropdown: (
        key: String,
        options: List<String>,
        selected: Set<String>,
        multi: Boolean,
        anchor: UiRect,
        onApply: (Set<String>) -> Unit
    ) -> Unit,
    private val defaultOutcomes: List<String>,
    private val placeholders: List<String>,
    private val tooltipProvider: (() -> List<Component>?)? = null
) : BaseUiComponent() {
    private companion object {
        const val HEADER_HEIGHT = 12
        const val CARD_HEIGHT = 54
        const val CARD_GAP = 4
        const val SECTION_GAP = 2
    }

    data class RewardCommandEntry(
        val outcomes: List<String>,
        val command: String
    )

    private data class RowLayout(
        val cardRect: UiRect,
        val deleteRect: UiRect,
        val outcomesValueRect: UiRect,
        val outcomesButtonRect: UiRect,
        val commandRect: UiRect
    )

    private var addButtonRect: UiRect = UiRect.EMPTY
    private var headerRect: UiRect = UiRect.EMPTY
    private var tooltipRect: UiRect? = null
    private var rowLayouts: List<RowLayout> = emptyList()
    private var activeCommandIndex: Int? = null
    private var expanded = false
    private val editor = UiSingleLineEditorState()

    override val preferredHeight: Int
        get() {
            if (!expanded) return HEADER_HEIGHT
            val cardsHeight = binding.get().size * (CARD_HEIGHT + CARD_GAP)
            return HEADER_HEIGHT + SECTION_GAP + cardsHeight + HEADER_HEIGHT
        }

    override val isFocusable: Boolean
        get() = true

    override fun layout(bounds: UiRect) {
        super.layout(bounds)
        headerRect = UiRect(bounds.x + 2, bounds.y, bounds.width - 4, HEADER_HEIGHT)
        if (!expanded) {
            rowLayouts = emptyList()
            addButtonRect = UiRect.EMPTY
            return
        }
        val rows = binding.get()
        var y = bounds.y + HEADER_HEIGHT + SECTION_GAP
        val font = Minecraft.getInstance().font
        val deleteLabel = UiText.tr("gui.cobbledollarscommandshops.admin.ui.delete")
        val deleteWidth = (font.width(deleteLabel) + 8)
            .coerceAtLeast(36)
            .coerceAtMost((bounds.width / 3).coerceAtLeast(24))
        rowLayouts = rows.map {
            val cardRect = UiRect(bounds.x + 2, y, bounds.width - 4, 54)
            val deleteRect = UiRect(cardRect.right - deleteWidth - 4, cardRect.y + 2, deleteWidth, 12)
            val outcomesLabelWidth = 54
            val outcomesButtonRect = UiRect(cardRect.right - UiChrome.SMALL_ICON_BUTTON_WIDTH - 4, cardRect.y + 15, UiChrome.SMALL_ICON_BUTTON_WIDTH, 12)
            val outcomesValueRect = UiRect(cardRect.x + outcomesLabelWidth + 6, cardRect.y + 14, outcomesButtonRect.x - (cardRect.x + outcomesLabelWidth + 8), 12)
            val commandRect = UiRect(cardRect.x + 55, cardRect.y + 28, cardRect.width - 61, 12)
            y += CARD_HEIGHT + CARD_GAP
            RowLayout(cardRect, deleteRect, outcomesValueRect, outcomesButtonRect, commandRect)
        }
        val addLabel = UiText.tr("gui.cobbledollarscommandshops.admin.ui.add_command")
        val addWidth = (font.width(addLabel) + 10)
            .coerceAtLeast(92)
            .coerceAtMost((bounds.width - 8).coerceAtLeast(24))
        addButtonRect = UiRect(bounds.x + 4, y, addWidth, HEADER_HEIGHT)
    }

    override fun render(context: GuiGraphics, font: Font, mouseX: Int, mouseY: Int, delta: Float) {
        layout(bounds)
        renderCollapsibleHeader(
            context = context,
            font = font,
            rect = headerRect,
            hovered = headerRect.contains(mouseX, mouseY),
            expanded = expanded,
            title = UiText.tr("gui.cobbledollarscommandshops.admin.ui.reward_commands_count", binding.get().size)
        )
        tooltipRect = UiHelpInlineLabel.render(
            context = context,
            font = font,
            bounds = headerRect,
            mouseX = mouseX,
            mouseY = mouseY,
            labelText = UiText.tr("gui.cobbledollarscommandshops.admin.ui.reward_commands_count", binding.get().size),
            textColor = 0xBBBBCC,
            hasTooltip = tooltipProvider != null,
            startX = 5,
            yOffset = 2,
            maxRightX = headerRect.right - 4,
            afterSpacing = 0,
            drawText = false
        ).tooltipRect
        if (!expanded) return

        val commands = binding.get()
        for ((index, entry) in commands.withIndex()) {
            val layout = rowLayouts.getOrNull(index) ?: continue
            val hasError = entry.command.isBlank() || entry.outcomes.isEmpty()
            val borderColor = if (hasError) 0xFFAA5555.toInt() else 0xFF555577.toInt()
            context.fill(layout.cardRect.x, layout.cardRect.y, layout.cardRect.right, layout.cardRect.bottom, 0x44222233)
            UiChrome.drawThickBorder(context, layout.cardRect, borderColor, 1)

            context.drawString(
                font,
                UiText.tr("gui.cobbledollarscommandshops.admin.ui.command_index", index + 1),
                layout.cardRect.x + 4,
                layout.cardRect.y + 3,
                0xFFFFFF,
                false
            )
            UiChrome.drawButton(
                context = context,
                font = font,
                rect = layout.deleteRect,
                label = UiText.tr("gui.cobbledollarscommandshops.admin.ui.delete"),
                hovered = layout.deleteRect.contains(mouseX, mouseY),
                style = AdminUiTheme.dangerButton
            )

            context.drawString(font, UiText.tr("gui.cobbledollarscommandshops.admin.ui.outcomes_label"), layout.cardRect.x + 4, layout.cardRect.y + 16, 0xAAAAFF, false)
            renderOutcomeChips(context, font, mouseX, mouseY, layout.outcomesValueRect, entry.outcomes)
            UiChrome.drawIconButton(
                context = context,
                font = font,
                rect = layout.outcomesButtonRect,
                label = UiIcons.SELECT_MANY,
                hovered = layout.outcomesButtonRect.contains(mouseX, mouseY),
                style = AdminUiTheme.neutralButton
            )

            context.drawString(font, UiText.tr("gui.cobbledollarscommandshops.admin.ui.command_label"), layout.cardRect.x + 4, layout.cardRect.y + 30, 0xAAAAFF, false)
            val editingThis = activeCommandIndex == index
            if (editingThis) {
                UiChrome.drawInputBox(context, layout.commandRect, true)
                editor.render(context, font, layout.commandRect, 0xFFFFFF)
            } else {
                UiChrome.drawInputBox(context, layout.commandRect, false)
                UiChrome.drawHoverOverlay(
                    context,
                    UiRect(layout.commandRect.x + 1, layout.commandRect.y + 1, layout.commandRect.width - 2, layout.commandRect.height - 2),
                    layout.commandRect.contains(mouseX, mouseY)
                )
                val text = entry.command.ifBlank { UiText.tr("gui.cobbledollarscommandshops.admin.ui.empty_command") }
                val color = if (entry.command.isBlank()) 0xAA7777 else 0xCCCCCC
                context.drawString(font, font.plainSubstrByWidth(text, layout.commandRect.width - 4), layout.commandRect.x + 2, layout.commandRect.y + 2, color, false)
            }

            val placeholdersText = UiText.tr("gui.cobbledollarscommandshops.admin.ui.placeholders", placeholders.joinToString(", "))
            context.drawString(
                font,
                font.plainSubstrByWidth(placeholdersText, layout.cardRect.width - 8),
                layout.cardRect.x + 4,
                layout.cardRect.y + 43,
                0x777777,
                false
            )
        }

        UiChrome.drawButton(
            context = context,
            font = font,
            rect = addButtonRect,
            label = UiText.tr("gui.cobbledollarscommandshops.admin.ui.add_command"),
            hovered = addButtonRect.contains(mouseX, mouseY),
            style = AdminUiTheme.successButton
        )
    }

    override fun wantsFocus(mouseX: Int, mouseY: Int): Boolean =
        rowLayouts.any { it.commandRect.contains(mouseX, mouseY) }

    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (!bounds.contains(mouseX, mouseY)) return false

        if (headerRect.contains(mouseX, mouseY)) {
            commitActiveEdit()
            activeCommandIndex = null
            expanded = !expanded
            layout(bounds)
            return true
        }
        if (!expanded) return true

        val commands = binding.get().toMutableList()
        for ((index, entry) in commands.withIndex()) {
            val layout = rowLayouts.getOrNull(index) ?: continue
            when {
                layout.deleteRect.contains(mouseX, mouseY) -> {
                    commitActiveEdit()
                    activeCommandIndex = null
                    commands.removeAt(index)
                    binding.set(commands)
                    layout(bounds)
                    return true
                }

                layout.outcomesValueRect.contains(mouseX, mouseY) || layout.outcomesButtonRect.contains(mouseX, mouseY) -> {
                    commitActiveEdit()
                    activeCommandIndex = null
                    openDropdown(
                        "reward_commands.$index.outcomes",
                        outcomeOptions,
                        entry.outcomes.toSet(),
                        true,
                        layout.outcomesButtonRect
                    ) { selected ->
                        val normalized = outcomeOptions.filter { it in selected }
                        val updated = binding.get().toMutableList()
                        if (index in updated.indices) {
                            updated[index] = updated[index].copy(outcomes = normalized)
                            binding.set(updated)
                        }
                    }
                    return true
                }

                layout.commandRect.contains(mouseX, mouseY) -> {
                    activateCommandEditor(index, layout.commandRect, mouseX)
                    return true
                }
            }
        }

        if (addButtonRect.contains(mouseX, mouseY)) {
            commitActiveEdit()
            activeCommandIndex = null
            commands += RewardCommandEntry(outcomes = defaultOutcomes, command = "")
            binding.set(commands)
            layout(bounds)
            return true
        }

        commitActiveEdit()
        activeCommandIndex = null
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (activeCommandIndex == null) return false
        when (editor.keyPressed(keyCode)) {
            UiSingleLineEditorState.Action.CANCEL -> {
                val index = activeCommandIndex ?: return true
                val current = binding.get().getOrNull(index) ?: return true
                editor.reset(current.command)
            }

            UiSingleLineEditorState.Action.COMMIT -> commitActiveEdit()
            UiSingleLineEditorState.Action.NONE -> {}
        }
        return true
    }

    override fun charTyped(char: Char, modifiers: Int): Boolean =
        activeCommandIndex != null && editor.charTyped(char)

    override fun focusChanged(focused: Boolean) {
        if (!focused) {
            commitActiveEdit()
            activeCommandIndex = null
        }
    }

    override fun tooltip(mouseX: Int, mouseY: Int): List<Component>? {
        val provider = tooltipProvider ?: return null
        return if (tooltipRect?.contains(mouseX, mouseY) == true) provider() else null
    }

    private fun activateCommandEditor(index: Int, rect: UiRect, mouseX: Int) {
        if (activeCommandIndex != index) {
            commitActiveEdit()
            activeCommandIndex = index
            val value = binding.get().getOrNull(index)?.command.orEmpty()
            editor.reset(value)
        }
        editor.moveCursorToMouse(Minecraft.getInstance().font, rect, mouseX)
    }

    private fun commitActiveEdit() {
        val index = activeCommandIndex ?: return
        val updated = binding.get().toMutableList()
        if (index !in updated.indices) return
        updated[index] = updated[index].copy(command = editor.currentText.trim())
        binding.set(updated)
    }

    private fun renderOutcomeChips(
        context: GuiGraphics,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        rect: UiRect,
        outcomes: List<String>
    ) {
        UiChrome.drawHoverOverlay(context, rect, rect.contains(mouseX, mouseY))

        if (outcomes.isEmpty()) {
            context.drawString(font, UiText.admin("(none)"), rect.x + 1, rect.y + 2, 0xAA7777, false)
            return
        }

        var chipX = rect.x + 1
        val chipBottom = rect.bottom - 1
        for (outcome in outcomes) {
            val text = font.plainSubstrByWidth(
                UiText.admin(outcome),
                rect.width
            )
            val chipWidth = font.width(text) + 4
            if (chipX + chipWidth > rect.right) break
            context.fill(chipX, rect.y + 1, chipX + chipWidth, chipBottom, (0xCC000000L or colorProvider(outcome).toLong()).toInt())
            context.drawString(font, text, chipX + 2, rect.y + 2, 0xFFFFFF, true)
            chipX += chipWidth + 2
        }
    }

    private fun renderCollapsibleHeader(
        context: GuiGraphics,
        font: Font,
        rect: UiRect,
        hovered: Boolean,
        expanded: Boolean,
        title: String
    ) {
        UiChrome.drawCollapsibleHeader(context, font, rect, title, expanded, hovered)
    }
}
