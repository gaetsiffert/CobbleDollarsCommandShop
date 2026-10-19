package fr.cobbledollars.commandshops.client.admin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdminViewScrollStateTest {
    private val list = listOf("audit", "journal", "page0")
    private val detail = listOf("audit", "journal", "page0", "event1")

    @Test
    fun `list and details keep separate positions and same-view changes retain their position`() {
        val state = AdminViewScrollState()
        assertEquals(0, state.show(list, 0, false, true))
        assertEquals(216, state.show(list, 216, true, true))
        assertEquals(0, state.show(detail, 216, true, true))
        assertEquals(216, state.show(list, 48, true, true))
        assertEquals(48, state.show(detail, 216, true, true))
    }

    @Test
    fun `short loading forms and unlaid-out rebuilds do not replace the complete view position`() {
        val state = AdminViewScrollState()
        state.show(list, 0, false, true)
        assertEquals(216, state.show(list, 216, true, false))
        assertEquals(216, state.show(list, 0, true, false))
        assertEquals(216, state.show(list, 0, true, true))
        assertEquals(216, state.show(list, 0, false, true))
    }

    @Test
    fun `deleted views cannot be remembered again and navigation history is bounded`() {
        val state = AdminViewScrollState(2)
        state.show(list, 0, false, true)
        state.show(detail, 216, true, true)
        state.discardWhere { it == detail }
        assertEquals(216, state.show(list, 48, true, true))
        assertEquals(0, state.show(detail, 216, true, true))
        state.show(listOf("third"), 48, true, true)
        assertEquals(0, state.show(list, 72, true, true))
    }
}
