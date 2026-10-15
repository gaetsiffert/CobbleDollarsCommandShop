package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fr.cobbledollars.commandshops.client.admin.AdminFeedbackPreview.PreviewStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdminFeedbackPreviewTest {
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Test
    fun `preview resolves unsaved explicit parameters independently of enabled sound channel`() {
        val root = json("""{"buy_success":{"channels":[],"sound":{"id":"minecraft:entity.villager.no","volume":0.6,"pitch":0.7}}}""")
        val before = root.deepCopy()
        val prepared = AdminFeedbackPreview.prepare(root, "buy_success", { true }, { true })
        assertEquals(PreviewStatus.PLAYED, prepared.result.status)
        assertEquals("minecraft:entity.villager.no", prepared.sound!!.id.toString())
        assertEquals(0.6f, prepared.sound.volume)
        assertEquals(0.7f, prepared.sound.pitch)
        assertEquals(before, root)
    }

    @Test
    fun `preview supports inherited defaults and simple format event volume`() {
        val inherited = AdminFeedbackPreview.prepare(JsonObject(), "buy_success", { true }, { true })
        assertEquals(PreviewStatus.PLAYED, inherited.result.status)
        assertEquals(0.25f, inherited.sound!!.volume)
        assertEquals(1.8f, inherited.sound.pitch)
        val simple = AdminFeedbackPreview.prepare(json("""{"buy_failure":{"sound":"minecraft:entity.experience_orb.pickup"}}"""), "buy_failure", { true }, { true })
        assertEquals(0.8f, simple.sound!!.volume)
        assertEquals(1.0f, simple.sound.pitch)
    }

    @Test
    fun `missing unknown and resource-less sounds have explanatory outcomes and never produce playback data`() {
        val silent = AdminFeedbackPreview.prepare(json("""{"buy_success":{"sound":null}}"""), "buy_success", { fail("No registry lookup for silence") }, { fail("No resource lookup for silence") })
        assertEquals(PreviewStatus.NO_SOUND, silent.result.status)
        assertNull(silent.sound)
        val unknown = AdminFeedbackPreview.prepare(json("""{"buy_success":{"sound":"example:absent"}}"""), "buy_success", { false }, { fail("No resource lookup for unknown id") })
        assertEquals(PreviewStatus.UNKNOWN_SOUND, unknown.result.status)
        assertEquals("example:absent", unknown.result.soundId)
        assertNull(unknown.sound)
        val unavailable = AdminFeedbackPreview.prepare(JsonObject(), "buy_success", { true }, { false })
        assertEquals(PreviewStatus.UNAVAILABLE_SOUND, unavailable.result.status)
        assertNull(unavailable.sound)
    }

    @Test
    fun `invalid draft identifiers and unsafe parameters fail locally without playback`() {
        val invalidSounds = listOf(
            """{"id":"Invalid ID"}""",
            """{"volume":0.5}""",
            """{"id":"minecraft:entity.villager.no","volume":"NaN"}""",
            """{"id":"minecraft:entity.villager.no","pitch":"Infinity"}""",
            """{"id":"minecraft:entity.villager.no","volume":-1}""",
            """{"id":"minecraft:entity.villager.no","pitch":0}""",
            "42"
        )
        invalidSounds.forEach { sound ->
            val result = AdminFeedbackPreview.prepare(json("""{"buy_success":{"sound":$sound}}"""), "buy_success", { fail("No registry lookup for invalid draft") }, { fail("No resource lookup for invalid draft") })
            assertEquals(PreviewStatus.INVALID_SOUND, result.result.status, sound)
            assertNull(result.sound)
        }
    }
}
