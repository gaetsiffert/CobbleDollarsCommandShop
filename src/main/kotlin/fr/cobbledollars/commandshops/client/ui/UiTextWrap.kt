// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

import net.minecraft.client.gui.Font

object UiTextWrap {
    fun wrap(font: Font, text: String, maxWidth: Int): List<String> =
        wrap(font = font, text = text, maxWidth = maxWidth, maxLines = null)

    fun wrap(
        font: Font,
        text: String,
        maxWidth: Int,
        maxLines: Int?
    ): List<String> {
        if (text.isBlank() || maxWidth <= 0) return emptyList()
        if (maxLines != null && maxLines <= 0) return emptyList()

        val words = text.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        var index = 0

        while (index < words.size && (maxLines == null || lines.size < maxLines)) {
            val word = words[index]
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (font.width(candidate) <= maxWidth) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
                index++
                continue
            }

            if (current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder()
                continue
            }

            lines += font.plainSubstrByWidth(word, maxWidth).ifEmpty { word.take(1) }
            index++
        }

        if (current.isNotEmpty() && (maxLines == null || lines.size < maxLines)) {
            lines += current.toString()
        }

        if (maxLines != null && index < words.size && lines.isNotEmpty()) {
            val last = lines.removeLast()
            lines += font.plainSubstrByWidth("$last...", maxWidth)
        }

        return lines
    }

    fun lineBlockHeight(
        font: Font,
        lineCount: Int,
        lineGap: Int = 1
    ): Int {
        if (lineCount <= 0) return 0
        return lineCount * font.lineHeight + (lineCount - 1) * lineGap
    }
}
