package fr.cobbledollars.commandshops.client.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UiColorCodecTest {
    @Test
    fun `a color can be edited as hex and picked as independent RGB channels`() {
        val color = UiColorCodec.parseHex("#12AbFF")!!

        assertEquals(18, UiColorCodec.red(color))
        assertEquals(171, UiColorCodec.green(color))
        assertEquals(255, UiColorCodec.blue(color))
        assertEquals("#12ABFF", UiColorCodec.formatHex(color))
        assertEquals(color, UiColorCodec.rgb(18, 171, 255))
    }

    @Test
    fun `invalid edits are rejected without inventing a color`() {
        listOf("", "#", "#12345", "#123456789", "#GGAA33", "#12345Z")
            .forEach { assertNull(UiColorCodec.parseHex(it), it) }
    }

    @Test
    fun `RGB slider input cannot overflow another channel`() {
        val color = UiColorCodec.rgb(-5, 128, 260)

        assertEquals("#0080FF", UiColorCodec.formatHex(color))
    }
}
