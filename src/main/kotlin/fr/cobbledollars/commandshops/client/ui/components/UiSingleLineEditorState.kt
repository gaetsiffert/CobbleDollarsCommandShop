// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui.components

import fr.cobbledollars.commandshops.client.ui.UiRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.font.TextFieldHelper
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW
import java.util.function.Predicate
import kotlin.math.max
import kotlin.math.min

class UiSingleLineEditorState {
    enum class Action {
        NONE,
        COMMIT,
        CANCEL
    }

    private var text = ""
    private var viewOffset = 0
    private val clipboardGetter = TextFieldHelper.createClipboardGetter(Minecraft.getInstance())
    private val clipboardSetter = TextFieldHelper.createClipboardSetter(Minecraft.getInstance())

    private val helper = TextFieldHelper(
        { text },
        { text = it },
        { sanitizeSingleLine(clipboardGetter.get()) },
        { clipboardSetter.accept(it) },
        Predicate { candidate -> candidate.none { it == '\n' || it == '\r' || it.isISOControl() } }
    )

    val currentText: String
        get() = text

    fun reset(value: String) {
        text = value
        viewOffset = 0
        helper.setCursorToEnd()
    }

    fun keyPressed(keyCode: Int): Action {
        return when (keyCode) {
            GLFW.GLFW_KEY_ESCAPE -> Action.CANCEL
            GLFW.GLFW_KEY_ENTER,
            GLFW.GLFW_KEY_KP_ENTER -> Action.COMMIT
            else -> {
                helper.keyPressed(keyCode)
                Action.NONE
            }
        }
    }

    fun charTyped(char: Char): Boolean {
        if (char.isISOControl() || char == '\n' || char == '\r') return false
        return helper.charTyped(char)
    }

    fun moveCursorToMouse(font: Font, rect: UiRect, mouseX: Int) {
        val contentWidth = (rect.width - 4).coerceAtLeast(0)
        ensureCursorVisible(font, contentWidth)
        val localX = (mouseX - (rect.x + 2)).coerceAtLeast(0)
        helper.setCursorPos(cursorIndexForPixel(font, contentWidth, localX), Screen.hasShiftDown())
    }

    fun render(
        context: GuiGraphics,
        font: Font,
        rect: UiRect,
        textColor: Int,
        selectionColor: Int = 0x6688CCFF,
        caretColor: Int = 0xFFFFFFFF.toInt()
    ) {
        val contentWidth = (rect.width - 4).coerceAtLeast(0)
        ensureCursorVisible(font, contentWidth)

        val cursor = helper.cursorPos.coerceIn(0, text.length)
        val selection = selectionRange()
        val textX = rect.x + 2
        val visibleText = visibleText(font, contentWidth)
        val visibleStart = viewOffset
        val visibleEnd = visibleStart + visibleText.length

        if (selection != null) {
            val highlightStart = max(selection.first, visibleStart)
            val highlightEnd = min(selection.second, visibleEnd)
            if (highlightStart < highlightEnd) {
                val prefixWidth = font.width(text.substring(visibleStart, highlightStart))
                val selectionWidth = font.width(text.substring(highlightStart, highlightEnd))
                context.fill(
                    textX + prefixWidth,
                    rect.y + 1,
                    textX + prefixWidth + selectionWidth,
                    rect.bottom - 1,
                    selectionColor
                )
            }
        }

        context.drawString(font, visibleText, textX, rect.y + 2, textColor, false)

        val caretOffset = font.width(text.substring(visibleStart, min(cursor, visibleEnd)))
        val caretX = textX + caretOffset
        context.fill(caretX, rect.y + 2, caretX + 1, rect.bottom - 2, caretColor)
    }

    private fun visibleText(font: Font, contentWidth: Int): String {
        if (contentWidth <= 0) return ""
        return font.plainSubstrByWidth(text.substring(viewOffset), contentWidth)
    }

    private fun ensureCursorVisible(font: Font, contentWidth: Int) {
        val cursor = helper.cursorPos.coerceIn(0, text.length)
        viewOffset = viewOffset.coerceIn(0, cursor)

        while (viewOffset < cursor && font.width(text.substring(viewOffset, cursor)) > contentWidth) {
            viewOffset++
        }
        while (viewOffset > 0 && font.width(text.substring(viewOffset - 1, cursor)) <= contentWidth) {
            viewOffset--
        }
    }

    private fun cursorIndexForPixel(font: Font, contentWidth: Int, localX: Int): Int {
        val visibleText = visibleText(font, contentWidth)
        if (visibleText.isEmpty()) return viewOffset

        for (index in 1..visibleText.length) {
            val prevWidth = font.width(visibleText.substring(0, index - 1))
            val nextWidth = font.width(visibleText.substring(0, index))
            if (localX < (prevWidth + nextWidth) / 2) {
                return viewOffset + index - 1
            }
        }
        return viewOffset + visibleText.length
    }

    private fun selectionRange(): Pair<Int, Int>? {
        val start = helper.cursorPos.coerceIn(0, text.length)
        val end = helper.selectionPos.coerceIn(0, text.length)
        return if (start == end) null else min(start, end) to max(start, end)
    }

    private fun sanitizeSingleLine(value: String): String =
        value
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .filterNot(Char::isISOControl)
}
