// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

object UiVerticalList {
    fun layout(
        bounds: UiRect,
        components: List<UiComponent>,
        gap: Int = 0,
        topPadding: Int = 0,
        bottomPadding: Int = 0
    ): Int {
        var y = bounds.y + topPadding
        val width = bounds.width

        for (component in components) {
            component.layout(
                UiRect(
                    x = bounds.x,
                    y = y,
                    width = width,
                    height = component.preferredHeight
                )
            )
            y += component.preferredHeight + gap
        }

        return (y - bounds.y + bottomPadding - gap.coerceAtLeast(0)).coerceAtLeast(0)
    }
}
