// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

import net.minecraft.network.chat.Component

internal object UiText {
    private const val ADMIN_UI_PREFIX = "gui.cobbledollarscommandshops.admin.ui."

    fun tr(key: String, vararg args: Any): String = Component.translatable(key, *args).string

    fun admin(raw: String): String {
        val normalized = raw.trim()
        if (normalized.isBlank()) return normalized
        if (looksLikeTranslationKey(normalized)) {
            val translated = tr(normalized)
            return if (translated != normalized) translated else normalized
        }

        val key = ADMIN_UI_PREFIX + adminKey(normalized)
        val translated = tr(key)
        return if (translated != key) translated else normalized
    }

    private fun looksLikeTranslationKey(raw: String): Boolean =
        raw.startsWith("gui.") || raw.startsWith("block.") || raw.startsWith("item.") || raw.startsWith("outcome.")

    private fun adminKey(raw: String): String =
        raw
            .replace("+", "plus ")
            .replace("%", " percent ")
            .replace("/", " ")
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .lowercase()
}
