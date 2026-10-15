// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

data class UiRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    val right: Int
        get() = x + width

    val bottom: Int
        get() = y + height

    fun contains(mouseX: Int, mouseY: Int): Boolean =
        mouseX in x..right && mouseY in y..bottom

    fun inset(horizontal: Int, vertical: Int): UiRect =
        UiRect(
            x = x + horizontal,
            y = y + vertical,
            width = (width - horizontal * 2).coerceAtLeast(0),
            height = (height - vertical * 2).coerceAtLeast(0)
        )

    companion object {
        val EMPTY = UiRect(0, 0, 0, 0)
    }
}
