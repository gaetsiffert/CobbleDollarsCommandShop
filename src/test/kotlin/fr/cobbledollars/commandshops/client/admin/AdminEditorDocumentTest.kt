package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdminEditorDocumentTest {
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
}
