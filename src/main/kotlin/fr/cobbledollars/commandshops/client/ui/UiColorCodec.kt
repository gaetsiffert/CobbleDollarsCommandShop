// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

object UiColorCodec {
    fun parseHex(text: String): Int? {
        val cleaned = text.trim()
            .removePrefix("#")
            .removePrefix("0x")
            .removePrefix("0X")
        val parsed = if (cleaned.length in 6..8) cleaned.toIntOrNull(16) else null
        return parsed?.and(0xFFFFFF)
    }

    fun formatHex(color: Int): String =
        "#${(color and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')}"

    fun red(color: Int): Int = (color shr 16) and 0xFF

    fun green(color: Int): Int = (color shr 8) and 0xFF

    fun blue(color: Int): Int = color and 0xFF

    fun rgb(red: Int, green: Int, blue: Int): Int =
        ((red.coerceIn(0, 255) and 0xFF) shl 16) or
            ((green.coerceIn(0, 255) and 0xFF) shl 8) or
            (blue.coerceIn(0, 255) and 0xFF)
}
