package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AdminHelpCoverageTest {
    @Test
    fun `financial audit settings preserve the approved defaults`() {
        val fields = AdminConfigSchema.audit.fields.getValue("events").schema.fields
        val expected = mapOf("player_transfer" to true, "balance_adjusted" to true,
            "battle_reward" to false, "native_buy_success" to false, "native_sell_success" to false)
        expected.forEach { (key, enabled) ->
            assertEquals(enabled, JsonParser.parseString(fields.getValue(key).schema.defaultJson).asBoolean, key)
        }
    }

    @Test
    fun `administrator messages have matching translations and arguments`() {
        val project = Path.of(System.getProperty("commandshops.projectDir", "."))
        val catalogs = listOf("en_us", "fr_fr").associateWith { language ->
            JsonParser.parseString(Files.readString(project.resolve("src/main/resources/assets/cobbledollarscommandshops/lang/$language.json"))).asJsonObject
        }
        val english = catalogs.getValue("en_us")
        val french = catalogs.getValue("fr_fr")
        assertEquals(english.keySet(), french.keySet(), "English and French must expose the same translation keys")
        val placeholder = Regex("""%\d+[$]s""")
        val messages = english.keySet().filter { it.startsWith("cobbledollarscommandshops.admin.message.") }
        assertTrue(messages.isNotEmpty(), "Expected translated administrator messages")
        messages.forEach { key ->
            val englishText = english.get(key).asString
            val frenchText = french.get(key).asString
            assertTrue(englishText.isNotBlank(), "Missing English text for $key")
            assertTrue(frenchText.isNotBlank(), "Missing French text for $key")
            assertEquals(
                placeholder.findAll(englishText).map { it.value }.sorted().toList(),
                placeholder.findAll(frenchText).map { it.value }.sorted().toList(),
                "Mismatched message arguments for $key"
            )
        }
        for (suffix in listOf("new_category", "access_denied")) {
            val key = "gui.cobbledollarscommandshops.admin.default.$suffix"
            catalogs.forEach { (language, catalog) ->
                assertTrue(catalog.has(key), "Missing $language default for $key")
                assertTrue(catalog.get(key).asString.isNotBlank())
            }
        }
    }

    @Test
    fun `every supported setting and action has an explanation in both languages`() {
        val fields = mutableSetOf<String>()
        fun visit(schema: ConfigSchema) {
            schema.fields.forEach { (key, field) -> fields += key; visit(field.schema) }
            schema.element?.let(::visit)
        }
        listOf(AdminConfigSchema.shop, AdminConfigSchema.bank, AdminConfigSchema.audit, AdminConfigSchema.commands, AdminConfigSchema.feedback).forEach(::visit)
        fields += listOf("match_type", "sound_format", "bank_format", "entry", "bank_price", "reward_count", "sound_id", "condition_type", "condition_rule", "condition_deny_message", "conditions_upgrade", "visibility_conditions", "sound_preview")
        val actions = listOf("edit", "move_up", "move_down", "duplicate", "delete", "add", "remove_option", "parent", "save", "reload", "new_shop", "new_bank", "close", "confirm", "cancel", "pick_item", "select", "bank_to_categories", "bank_to_flat", "bank_upgrade", "conditions_upgrade", "customize", "reset_defaults", "load_server_state")
        for (language in listOf("fr_fr", "en_us")) {
            val project = Path.of(System.getProperty("commandshops.projectDir", "."))
            val json = JsonParser.parseString(Files.readString(project.resolve("src/main/resources/assets/cobbledollarscommandshops/lang/$language.json"))).asJsonObject
            for ((kind, keys) in listOf("help" to fields, "tooltip" to actions)) {
                keys.forEach { field ->
                    val key = "gui.cobbledollarscommandshops.admin.$kind.$field"
                    assertTrue(json.has(key), "Missing $language explanation for $key")
                    assertTrue(json.get(key).asString.length > 20, "Expected an explanation for $key, not just a caption")
                    val caption = json.get("gui.cobbledollarscommandshops.admin.field.$field")?.asString
                    assertNotEquals(caption, json.get(key).asString)
                }
            }
        }
    }
}
