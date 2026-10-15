package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fr.cobbledollars.commandshops.feedback.FeedbackFiles
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdminFeedbackSettingsTest {
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Test
    fun `inherited event values equal the runtime defaults and resolve without dirtying the document`() {
        val document = AdminEditorDocument("""{"revision":"rev","files":{"feedback.json":{}}}""")
        val root = document.files.getAsJsonObject("feedback.json")
        val runtime = FeedbackFiles.parseConfig(root, "test")
        AdminFeedbackSettings.eventKeys.forEach { key ->
            val effective = AdminFeedbackSettings.effectiveEvent(root, key)
            val explicit = json("""{"$key":$effective}""")
            assertEquals(runtime, FeedbackFiles.parseConfig(explicit, "test"), key)
        }
        assertEquals(0, document.dirtyCount)
        assertEquals(json("""{"channels":["action_bar","sound"],"sound":{"id":"minecraft:entity.experience_orb.pickup","volume":0.25,"pitch":1.8}}""").toString(),
            AdminFeedbackSettings.effectiveEvent(root, "buy_success").toString())
    }

    @Test
    fun `null event channels and detailed parameters inherit but null sound explicitly silences`() {
        val root = json("""{"buy_success":null,"buy_failure":{"channels":null,"sound":{"id":"minecraft:entity.experience_orb.pickup","volume":null}},"shop_denied":{"sound":null}}""")
        assertEquals(AdminFeedbackSettings.defaultEvent("buy_success"), AdminFeedbackSettings.effectiveEvent(root, "buy_success"))
        val failure = AdminFeedbackSettings.effectiveEvent(root, "buy_failure")
        assertEquals(0.8f, failure.getAsJsonObject("sound").get("volume").asFloat)
        assertEquals(1.0f, failure.getAsJsonObject("sound").get("pitch").asFloat)
        assertTrue(AdminFeedbackSettings.effectiveField(root, "shop_denied", "sound").isJsonNull)
        assertEquals(FeedbackFiles.parseConfig(root, "test"), FeedbackFiles.parseConfig(json("""{
            "buy_success":${AdminFeedbackSettings.effectiveEvent(root, "buy_success")},
            "buy_failure":$failure,
            "shop_denied":${AdminFeedbackSettings.effectiveEvent(root, "shop_denied")}
        }"""), "test"))
    }

    @Test
    fun `simple sound uses defaults for its specific event and unrelated invalid drafts do not interfere`() {
        val root = json("""{"buy_failure":{"sound":"minecraft:entity.experience_orb.pickup"},"sell_success":false}""")
        val sound = AdminFeedbackSettings.effectiveField(root, "buy_failure", "sound").asJsonObject
        assertEquals("minecraft:entity.experience_orb.pickup", sound.get("id").asString)
        assertEquals(0.8f, sound.get("volume").asFloat)
        assertEquals(1.0f, sound.get("pitch").asFloat)
        assertFalse(root.get("sell_success").asBoolean)
        assertThrows(IllegalArgumentException::class.java) { AdminFeedbackSettings.effectiveEvent(root, "sell_success") }
    }

    @Test
    fun `customizing and resetting events preserves runtime values without sharing mutable data`() {
        val root = json("""{"buy_failure":null}""")
        val defaults = FeedbackFiles.parseConfig(root, "test")
        assertTrue(AdminFeedbackSettings.customizeEvent(root, "buy_failure"))
        assertFalse(AdminFeedbackSettings.customizeEvent(root, "buy_failure"))
        assertEquals(defaults, FeedbackFiles.parseConfig(root, "test"))
        root.getAsJsonObject("buy_failure").getAsJsonObject("sound").addProperty("volume", 0.1)
        assertEquals(0.8f, AdminFeedbackSettings.defaultEvent("buy_failure").getAsJsonObject("sound").get("volume").asFloat)
        assertTrue(AdminFeedbackSettings.resetEvent(root, "buy_failure"))
        assertFalse(AdminFeedbackSettings.resetEvent(root, "buy_failure"))
        assertEquals(defaults, FeedbackFiles.parseConfig(root, "test"))
    }

    @Test
    fun `customizing inherited fields copies actual values while preserving other overrides`() {
        val root = json("""{"shop_denied":{"channels":[],"sound":{"id":"minecraft:entity.experience_orb.pickup"}}}""")
        val before = FeedbackFiles.parseConfig(root, "test")
        assertTrue(AdminFeedbackSettings.customizeField(root, "shop_denied", listOf("sound", "volume")))
        assertEquals(0.8f, root.getAsJsonObject("shop_denied").getAsJsonObject("sound").get("volume").asFloat)
        assertEquals(before, FeedbackFiles.parseConfig(root, "test"))
        assertFalse(AdminFeedbackSettings.customizeField(root, "shop_denied", listOf("sound", "volume")))
        assertTrue(AdminFeedbackSettings.resetField(root, "shop_denied", listOf("sound", "volume")))
        assertEquals(before, FeedbackFiles.parseConfig(root, "test"))
        assertEquals(0, root.getAsJsonObject("shop_denied").getAsJsonArray("channels").size())
        assertTrue(AdminFeedbackSettings.customizeField(root, "buy_success", "sound"))
        assertEquals(AdminFeedbackSettings.defaultEvent("buy_success").get("sound"), root.getAsJsonObject("buy_success").get("sound"))
        assertFalse(root.getAsJsonObject("buy_success").has("channels"))
    }

    @Test
    fun `customizing sound subfields converts simple sound without losing id or playback values`() {
        val root = json("""{"buy_failure":{"sound":"minecraft:entity.experience_orb.pickup"}}""")
        val before = FeedbackFiles.parseConfig(root, "test")
        assertTrue(AdminFeedbackSettings.customizeField(root, "buy_failure", listOf("sound", "pitch")))
        assertEquals(before, FeedbackFiles.parseConfig(root, "test"))
        assertEquals("minecraft:entity.experience_orb.pickup", root.getAsJsonObject("buy_failure").getAsJsonObject("sound").get("id").asString)
    }

    @Test
    fun `selecting active sound format never resets a detailed draft`() {
        val root = json("""{"buy_success":{"channels":[],"sound":{"id":"minecraft:entity.villager.no","volume":0.7,"pitch":0.9}}}""")
        val before = root.deepCopy()
        assertFalse(AdminFeedbackSettings.changeSoundFormat(root, "buy_success", "detailed"))
        assertEquals(before, root)
        assertTrue(AdminFeedbackSettings.changeSoundFormat(root, "buy_success", "simple"))
        assertEquals("minecraft:entity.villager.no", root.getAsJsonObject("buy_success").get("sound").asString)
        assertTrue(AdminFeedbackSettings.changeSoundFormat(root, "buy_success", "detailed"))
        val sound = root.getAsJsonObject("buy_success").getAsJsonObject("sound")
        assertEquals("minecraft:entity.villager.no", sound.get("id").asString)
        assertEquals(0.25f, sound.get("volume").asFloat)
        assertEquals(1.8f, sound.get("pitch").asFloat)
        assertEquals(0, root.getAsJsonObject("buy_success").getAsJsonArray("channels").size())
    }

    @Test
    fun `enabling a disabled sound restores that event defaults`() {
        val root = json("""{"buy_failure":{"sound":null}}""")
        assertFalse(AdminFeedbackSettings.changeSoundFormat(root, "buy_failure", "none"))
        assertTrue(AdminFeedbackSettings.changeSoundFormat(root, "buy_failure", "detailed"))
        assertEquals(AdminFeedbackSettings.defaultEvent("buy_failure").get("sound"), root.getAsJsonObject("buy_failure").get("sound"))
    }
}
