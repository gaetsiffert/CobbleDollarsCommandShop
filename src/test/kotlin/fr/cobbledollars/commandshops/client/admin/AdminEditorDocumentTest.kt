package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdminEditorDocumentTest {
    @Test
    fun `command access setting stays in the draft until it is saved`() {
        val doc = AdminEditorDocument("""{"revision":"commands-rev","files":{"commands.json":{"allow_player_open":true}}}""")
        val schema = AdminConfigSchema.forFile("commands.json", doc.files.get("commands.json"))
        assertTrue(schema.fields.getValue("allow_player_open").schema.newValue().asBoolean)
        doc.files.getAsJsonObject("commands.json").addProperty("allow_player_open", false)
        assertEquals(1, doc.dirtyCount)
        assertFalse(AdminEditorDocument(doc.serialize()).resolve("commands.json", listOf("allow_player_open")).asBoolean)
        assertTrue(AdminDraftValidation.validate(doc).isEmpty())
    }

    private val snapshot = """{"revision":"rev-1","files":{"audit.json":{},"feedback.json":{},"global_bank.json":[],"shops/example/shop.json":{"categories":[{"name":"A","offers":[{"id":"offer_1","match":{"include":[{"item":"minecraft:stone"}]},"price":9007199254740992}]},{"name":"B","offers":[]}]},"shops/example/bank.json":{"offers":[]}}}"""

    @Test
    fun `creating and deleting a copied shop includes its independent local bank`() {
        val doc = AdminEditorDocument(snapshot)
        val created = doc.createShop("copy", "shops/example/shop.json")
        doc.resolve(created, listOf("categories", "0")).asJsonObject.addProperty("name", "changed")
        assertEquals("A", doc.resolve("shops/example/shop.json", listOf("categories", "0", "name")).asString)
        assertTrue(doc.files.has("shops/copy/bank.json"))
        assertEquals(2, doc.dirtyCount)
        doc.deleteFile(created)
        assertFalse(doc.files.has("shops/copy/bank.json"))
        assertEquals(0, doc.dirtyCount)
    }

    @Test
    fun `large price changes remain dirty and serialize without loss`() {
        val doc = AdminEditorDocument(snapshot)
        doc.resolve("shops/example/shop.json", listOf("categories", "0", "offers", "0")).asJsonObject
            .add("price", JsonParser.parseString("9007199254740993"))
        assertEquals(1, doc.dirtyCount)
        val saved = JsonParser.parseString(doc.serialize()).asJsonObject
        assertEquals("rev-1", saved.get("revision").asString)
        assertTrue(doc.serialize().contains("9007199254740993"))
    }

    @Test
    fun `copied categories and new offers receive ids unique across the whole shop`() {
        val doc = AdminEditorDocument(snapshot)
        val shop = "shops/example/shop.json"
        val category = doc.uniqueOfferIds(shop, doc.resolve(shop, listOf("categories", "0")).deepCopy())
        assertEquals("offer_2", category.asJsonObject.getAsJsonArray("offers")[0].asJsonObject.get("id").asString)
        doc.resolve(shop, listOf("categories")).asJsonArray.add(category)
        val offer = doc.uniqueOfferIds(shop, AdminConfigSchema.shopOffer.newValue())
        assertEquals("offer_3", offer.asJsonObject.get("id").asString)
    }

    @Test
    fun `missing optional settings are discoverable without changing the document`() {
        val doc = AdminEditorDocument(snapshot)
        val shop = doc.files.getAsJsonObject("shops/example/shop.json")
        assertTrue(AdminConfigSchema.visibleFields(AdminConfigSchema.shop, shop).keys.containsAll(listOf("conditions", "deny_message", "categories")))
        assertEquals(0, doc.dirtyCount)
        assertFalse(shop.has("conditions"))
        assertTrue(AdminConfigSchema.conditions.fields.keys.containsAll(listOf("scores_all", "time_ranges_any", "dimensions_any", "advancements_all", "advancements_any", "player_tags_all", "player_tags_any", "player_tags_none")))
    }

    @Test
    fun `legacy banks and all matching modes have editable metadata`() {
        val doc = AdminEditorDocument(snapshot)
        assertSame(AdminConfigSchema.bankOffer, AdminConfigSchema.forFile("global_bank.json", doc.files.get("global_bank.json")).element)
        assertEquals(listOf("item", "stack", "tag", "mod"), AdminConfigSchema.atom.variants)
        assertTrue(AdminConfigSchema.restock.fields.keys.containsAll(listOf("type", "amount", "every_seconds", "hour", "minute", "time_zone")))
        assertFalse(AdminConfigSchema.audit.fields.getValue("events").schema.fields.getValue("buy_failure").schema.newValue().asBoolean)
    }

    @Test
    fun `shop identifiers cannot escape configuration directory or replace another shop`() {
        val doc = AdminEditorDocument(snapshot)
        assertThrows(IllegalArgumentException::class.java) { doc.createShop("../outside") }
        assertThrows(IllegalArgumentException::class.java) { doc.createShop("example") }
        assertEquals(0, doc.dirtyCount)
    }

    @Test
    fun `new shop category and denial defaults use the supplied language without translating existing text`() {
        val doc = AdminEditorDocument(snapshot)
        val frenchText = { key: String -> when (key) {
            "new_category" -> "Nouvelle catégorie"
            "access_denied" -> "Accès refusé"
            else -> fail<String>("Unexpected default text key: $key")
        } }
        val created = doc.createShop("french", defaultText = frenchText)
        assertEquals("Nouvelle catégorie", doc.resolve(created, listOf("categories", "0", "name")).asString)
        assertEquals("A", doc.resolve("shops/example/shop.json", listOf("categories", "0", "name")).asString)
        assertFalse(doc.files.getAsJsonObject(created).has("deny_message"))
        val category = AdminConfigSchema.shop.fields.getValue("categories").schema.element!!.newValue(frenchText)
        assertEquals("Nouvelle catégorie", category.asJsonObject.get("name").asString)
        val bankCategory = AdminConfigSchema.bank.fields.getValue("categories").schema.element!!.newValue(frenchText)
        assertEquals("Nouvelle catégorie", bankCategory.asJsonObject.get("name").asString)
        assertEquals("Accès refusé", AdminConfigSchema.shop.fields.getValue("deny_message").schema.newValue(frenchText).asString)
        assertEquals(1, doc.dirtyCount)
    }

    @Test
    fun `new values retain English defaults when no language resolver is provided`() {
        val doc = AdminEditorDocument(snapshot)
        val created = doc.createShop("english")
        assertEquals("New category", doc.resolve(created, listOf("categories", "0", "name")).asString)
        assertEquals("Access denied", AdminConfigSchema.shop.fields.getValue("deny_message").schema.newValue().asString)
        assertEquals("new_offer", AdminConfigSchema.shopOffer.newValue().asJsonObject.get("id").asString)
        val bank = AdminConfigSchema.bank.newValue()
        assertTrue(bank.isJsonObject, "A new bank must not start as a legacy flat array")
        assertTrue(bank.asJsonObject.has("categories"))
        assertFalse(bank.asJsonObject.has("offers"))
    }

    @Test
    fun `duplicating a shop preserves text even when it matches the English defaults`() {
        val doc = AdminEditorDocument(snapshot)
        val source = "shops/example/shop.json"
        doc.resolve(source, listOf("categories", "0")).asJsonObject.addProperty("name", "New category")
        doc.files.getAsJsonObject(source).addProperty("deny_message", "Access denied")
        val created = doc.createShop("copy", source) { fail<String>("A copy must not resolve default text") }
        assertEquals("New category", doc.resolve(created, listOf("categories", "0", "name")).asString)
        assertEquals("Access denied", doc.files.getAsJsonObject(created).get("deny_message").asString)
        assertEquals("B", doc.resolve(created, listOf("categories", "1", "name")).asString)
    }

    @Test
    fun `legacy condition conversion splits independent requirements and preserves grouped alternatives`() {
        val legacy = JsonParser.parseString("""{
            "scores_all":[{"objective":"badges","min":4}],
            "player_tags_any":["vip","staff"],
            "player_tags_all":["member","trusted"],
            "dimensions_any":[]
        }""").asJsonObject
        val rules = AdminConfigSchema.orderedConditions(legacy)
        assertEquals(listOf("player_tags_all", "player_tags_all", "player_tags_any", "scores_all"), rules.map { it.asJsonObject.keySet().single() })
        assertEquals("member", rules[0].asJsonObject.getAsJsonArray("player_tags_all")[0].asString)
        assertEquals("trusted", rules[1].asJsonObject.getAsJsonArray("player_tags_all")[0].asString)
        assertEquals(listOf("vip", "staff"), rules[2].asJsonObject.getAsJsonArray("player_tags_any").map { it.asString })
        rules[2].asJsonObject.getAsJsonArray("player_tags_any").remove(0)
        assertEquals(2, legacy.getAsJsonArray("player_tags_any").size(), "Converted rules must not share mutable values with the original")
        assertThrows(IllegalArgumentException::class.java) {
            AdminConfigSchema.orderedConditions(JsonParser.parseString("""{"extension":true}""").asJsonObject)
        }
    }

    @Test
    fun `condition metadata traverses both legacy groups and ordered access rules`() {
        val path = "shops/example/shop.json"
        val legacy = JsonParser.parseString("""{"conditions":{"scores_all":[{"objective":"badges","min":4}]},"categories":[]}""")
        val score = AdminConfigSchema.atPath(path, legacy, listOf("conditions", "scores_all", "0"))
        assertTrue(score.fields.keys.containsAll(listOf("objective", "min", "max", "equals")))
        val ordered = JsonParser.parseString("""{"conditions":[{"scores_all":[{"objective":"badges","min":4}],"deny_message":"Four badges needed"}],"categories":[]}""")
        assertSame(AdminConfigSchema.accessConditionRule, AdminConfigSchema.atPath(path, ordered, listOf("conditions", "0")))
        assertTrue(AdminConfigSchema.atPath(path, ordered, listOf("conditions", "0", "scores_all", "0")).fields.containsKey("objective"))
        assertEquals("", AdminConfigSchema.accessConditionRule.fields.getValue("deny_message").schema.newValue().asString)
    }

    @Test
    fun `new conditions at every access and visibility level use ordered rules`() {
        for (schema in listOf(AdminConfigSchema.shop, AdminConfigSchema.bank)) {
            val rootConditions = schema.fields.getValue("conditions").schema
            assertTrue(rootConditions.newValue().isJsonArray)
            assertSame(AdminConfigSchema.accessConditionRule, rootConditions.element)
            assertTrue(schema.fields.containsKey("deny_message"))
        }
        val shopCategory = AdminConfigSchema.shop.fields.getValue("categories").schema.element!!
        val bankCategory = AdminConfigSchema.bank.fields.getValue("categories").schema.element!!
        for (schema in listOf(shopCategory, bankCategory, AdminConfigSchema.shopOffer, AdminConfigSchema.bankOffer)) {
            val conditions = schema.fields.getValue("conditions").schema
            assertTrue(conditions.newValue().isJsonArray, "Adding category or offer conditions must not require conversion")
            assertTrue(conditions.newValue().asJsonArray.isEmpty)
            assertSame(AdminConfigSchema.visibilityConditionRule, conditions.element)
            assertFalse(conditions.element!!.fields.containsKey("deny_message"))
        }
        assertEquals(AdminConfigSchema.conditionTypes, AdminConfigSchema.accessConditionRule.variants)
        for (type in AdminConfigSchema.conditionTypes) {
            val values = AdminConfigSchema.accessConditionRule.fields.getValue(type).schema.newValue().asJsonArray
            assertEquals(1, values.size(), "A new $type rule must contain an editable condition")
        }
    }

    @Test
    fun `opening and duplicating existing files preserves legacy condition and bank syntax`() {
        val legacySnapshot = """{"revision":"legacy","files":{
            "global_bank.json":[{"match":{"include":[{"item":"minecraft:stone"}]},"price":1}],
            "shops/legacy/shop.json":{"conditions":{"player_tags_all":["member"]},"categories":[
              {"name":"Old category","conditions":{"dimensions_any":["minecraft:overworld"]},"offers":[]}
            ]},
            "shops/legacy/bank.json":[{"match":{"include":[{"item":"minecraft:stone"}]},"price":2}]
        }}"""
        val doc = AdminEditorDocument(legacySnapshot)
        val before = doc.serialize()
        val source = "shops/legacy/shop.json"
        assertSame(AdminConfigSchema.accessConditions,
            AdminConfigSchema.atPath(source, doc.files.get(source), listOf("conditions")))
        assertSame(AdminConfigSchema.conditions,
            AdminConfigSchema.atPath(source, doc.files.get(source), listOf("categories", "0", "conditions")))
        AdminConfigSchema.visibleFields(AdminConfigSchema.shop, doc.files.getAsJsonObject(source))
        assertSame(AdminConfigSchema.bankOffer, AdminConfigSchema.forFile("global_bank.json", doc.files.get("global_bank.json")).element)
        assertEquals(before, doc.serialize(), "Reading an old configuration must not convert it")
        assertEquals(0, doc.dirtyCount)

        val copied = doc.createShop("copy", source)
        assertEquals(doc.files.get(source), doc.files.get(copied))
        assertEquals(doc.files.get("shops/legacy/bank.json"), doc.files.get("shops/copy/bank.json"))
        assertTrue(doc.resolve(copied, listOf("conditions")).isJsonObject)
        assertTrue(doc.files.get("shops/copy/bank.json").isJsonArray)
        assertEquals(JsonParser.parseString(legacySnapshot).asJsonObject.getAsJsonObject("files").get(source), doc.files.get(source))
    }

    @Test
    fun `bank flattening can discard only recognized conditions that impose no restriction`() {
        assertTrue(AdminConfigSchema.canDiscardConditions(null))
        for (json in listOf("null", "[]", "{}", """{"player_tags_all":[],"dimensions_any":null,"scores_all":[]}""")) {
            val value = JsonParser.parseString(json)
            val before = value.deepCopy()
            assertTrue(AdminConfigSchema.canDiscardConditions(value), "Harmless conditions must permit flattening: $json")
            assertEquals(before, value, "Checking a conversion must not change the source")
        }
        for (json in listOf(
            """[{"player_tags_all":["member"]}]""", """{"player_tags_all":["member"]}""",
            """{"extension":[]}""", """{"deny_message":"keep"}""", """{"scores_all":{}}""",
            """[{"player_tags_all":[]}]""", "[{}]", "true", "0", "\"invalid\""
        )) assertFalse(AdminConfigSchema.canDiscardConditions(JsonParser.parseString(json)),
            "Active, unknown or malformed conditions must be protected: $json")
    }
}
