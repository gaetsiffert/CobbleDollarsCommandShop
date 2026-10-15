// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

data class UiPanelStyle(
    val backgroundColor: Int,
    val borderColor: Int,
    val titleColor: Int = borderColor,
    val titleBarColor: Int? = null,
    val borderThickness: Int = 1
)

data class UiButtonStyle(
    val normalBackgroundColor: Int,
    val hoverBackgroundColor: Int,
    val borderColor: Int,
    val textColor: Int,
    val hoverTextColor: Int = textColor
)

object AdminUiTheme {
    val boardFrame = UiPanelStyle(
        backgroundColor = 0xEE1A1A2E.toInt(),
        borderColor = 0xFFCC8800.toInt(),
        borderThickness = 2
    )

    val boardSection = UiPanelStyle(
        backgroundColor = 0x88000000.toInt(),
        borderColor = 0xFF55AA55.toInt()
    )

    val adminPanel = UiPanelStyle(
        backgroundColor = 0x88000000.toInt(),
        borderColor = 0xFF666666.toInt()
    )

    val adminOverlay = UiPanelStyle(
        backgroundColor = 0xF0181828.toInt(),
        borderColor = 0xFF8888AA.toInt(),
        titleBarColor = 0xDD000000.toInt()
    )

    val modalOverlay = UiPanelStyle(
        backgroundColor = 0xFF181828.toInt(),
        borderColor = 0xFF8888AA.toInt(),
        titleBarColor = 0xFF000000.toInt()
    )

    const val modalScrimColor: Int = 0xAA000000.toInt()

    val successButton = UiButtonStyle(
        normalBackgroundColor = 0xFF223322.toInt(),
        hoverBackgroundColor = 0xFF335533.toInt(),
        borderColor = 0xFF55AA55.toInt(),
        textColor = 0x55FF55
    )

    val neutralButton = UiButtonStyle(
        normalBackgroundColor = 0xFF222233.toInt(),
        hoverBackgroundColor = 0xFF333355.toInt(),
        borderColor = 0xFF5555AA.toInt(),
        textColor = 0x5555FF
    )

    val itemPickerButton = UiButtonStyle(
        normalBackgroundColor = 0xFF222233.toInt(),
        hoverBackgroundColor = 0xFF333355.toInt(),
        borderColor = 0xFF5588CC.toInt(),
        textColor = 0x88CCFF,
        hoverTextColor = 0x88CCFF
    )

    val dangerButton = UiButtonStyle(
        normalBackgroundColor = 0xFF332222.toInt(),
        hoverBackgroundColor = 0xFF553333.toInt(),
        borderColor = 0xFFAA5555.toInt(),
        textColor = 0xFF5555
    )

    val activeDangerButton = UiButtonStyle(
        normalBackgroundColor = 0xFF882222.toInt(),
        hoverBackgroundColor = 0xFFAA2222.toInt(),
        borderColor = 0xFFAA5555.toInt(),
        textColor = 0xFF5555
    )

    val disabledButton = UiButtonStyle(
        normalBackgroundColor = 0xFF2A2A2F.toInt(),
        hoverBackgroundColor = 0xFF2F2F35.toInt(),
        borderColor = 0xFF555555.toInt(),
        textColor = 0x777777,
        hoverTextColor = 0xAAAAAA
    )
}
