package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdminDraftValidationTest {
    private val shop = "shops/example/shop.json"
    private val offerPath = listOf("categories", "0", "offers", "0")

    @Test
    fun `valid legacy values retain arbitrary integer prices strings overnight ranges and optional nulls`() {
        val files = JsonParser.parseString("""{
            "$shop": {
                "conditions": {
                    "player_tags_any": [],
                    "time_ranges_any": [{"start_tick":"23000","end_tick":1000}],
                    "scores_all": [{"objective":"badges","min":"-1","max":null}]
                },
                "categories": [{"name":"A","offers":[{
                    "id":"stone","match":{"include":[{"item":"minecraft:stone"}]},
                    "price":"9007199254740993000000000000000000","count":"+1","stock":-1
                }]}]
            },
            "feedback.json": {"buy_success":{"sound":null},"buy_failure":{"sound":"minecraft:entity.villager.no"}}
        }""").asJsonObject
        assertEquals(emptyList<AdminDraftIssue>(), AdminDraftValidation.validate(files))
    }

    @Test
    fun `number failures identify the actual field without mutating the draft`() {
        val root = shopWithOffer(""""price": "not a price", "count": 1.5, "stock": -2""")
        val before = root.toString()
        val issues = AdminDraftValidation.validateFile(shop, root)
        assertEquals("validation.integer", issues.single { it.segments == offerPath + "price" }.key)
        assertEquals("validation.integer", issues.single { it.segments == offerPath + "count" }.key)
        val stock = issues.single { it.segments == offerPath + "stock" }
        assertEquals("validation.at_least", stock.key)
        assertEquals(listOf("-1"), stock.args)
        assertEquals(before, root.toString())
    }

    @Test
    fun `integer limits and positive requirements match field parsers`() {
        val root = shopWithOffer(""""price": -1, "count": "2147483648", "stock": 4,
            "restock":{"type":"interval","amount":0,"every_seconds":"9223372036854775808"}""")
        val issues = AdminDraftValidation.validateFile(shop, root)
        assertEquals("validation.at_least", issues.single { it.segments == offerPath + "price" }.key)
        assertEquals(listOf("-2147483648", "2147483647"), issues.single { it.segments == offerPath + "count" }.args)
        assertEquals(listOf("1"), issues.single { it.segments == offerPath + listOf("restock", "amount") }.args)
        assertEquals(listOf("-9223372036854775808", "9223372036854775807"),
            issues.single { it.segments == offerPath + listOf("restock", "every_seconds") }.args)
    }

    @Test
    fun `time ranges flag out of bounds and equal endpoints but allow a midnight wrap`() {
        val root = JsonParser.parseString("""{
            "conditions": [{"time_ranges_any":[
                {"start_tick":-1,"end_tick":24000},
                {"start_tick":4000,"end_tick":4000},
                {"start_tick":23000,"end_tick":1000}
            ]}], "categories":[]
        }""")
        val issues = AdminDraftValidation.validateFile(shop, root)
        assertEquals(3, issues.size)
        assertEquals(listOf("0", "23999"), issues[0].args)
        assertEquals(listOf("0", "23999"), issues[1].args)
        assertEquals("validation.time_distinct", issues[2].key)
        assertEquals(listOf("conditions", "0", "time_ranges_any", "1", "end_tick"), issues[2].segments)
    }

    @Test
    fun `empty ordered rules and incomplete scores have actionable container and field paths`() {
        val root = JsonParser.parseString("""{
            "conditions":[
                {"player_tags_any":[]},
                {"scores_all":[{"objective":"","min":null}]},
                {"deny_message":"Missing a condition"}
            ], "categories":[]
        }""")
        val issues = AdminDraftValidation.validateFile(shop, root)
        assertTrue(issues.any { it.key == "validation.condition_empty" && it.segments == listOf("conditions", "0", "player_tags_any") })
        assertTrue(issues.any { it.key == "validation.score_comparison" && it.segments == listOf("conditions", "1", "scores_all", "0") })
        assertTrue(issues.any { it.key == "validation.required" && it.segments.last() == "objective" })
        assertTrue(issues.any { it.key == "validation.condition_type" && it.segments == listOf("conditions", "2") })
        assertEquals(4, AdminDraftValidation.under(issues, shop, listOf("conditions")).size)
        assertEquals(1, AdminDraftValidation.at(issues, shop, listOf("conditions", "2")).size)
        assertTrue(AdminDraftValidation.under(issues, "global_bank.json", emptyList()).isEmpty())
    }

    @Test
    fun `inactive restock fields and unrecognized extension fields cannot reject valid drafts`() {
        val root = shopWithOffer(""""price":1,"stock":5,
            "restock":{"type":"INTERVAL","amount":1,"every_seconds":3600,"hour":99,"time_zone":"not a zone"},
            "extension":{"count":-9,"price":"not money"}""")
        assertTrue(AdminDraftValidation.validateFile(shop, root).isEmpty())
        root.getAsJsonArray("categories")[0].asJsonObject.getAsJsonArray("offers")[0].asJsonObject
            .add("restock", JsonParser.parseString("""{"type":"daily_reset","hour":2,"amount":-9,"every_seconds":"n/a"}"""))
        assertTrue(AdminDraftValidation.validateFile(shop, root).isEmpty())
    }

    @Test
    fun `active restock fields show missing values invalid times and invalid zones`() {
        val interval = shopWithOffer(""""price":1,"stock":5,"restock":{"type":"interval"}""")
        assertEquals(setOf("amount", "every_seconds"), AdminDraftValidation.validateFile(shop, interval).map { it.segments.last() }.toSet())
        val daily = shopWithOffer(""""price":1,"stock":5,"restock":{"type":"daily_reset","hour":24,"minute":60,"time_zone":"not a zone"}""")
        val issues = AdminDraftValidation.validateFile(shop, daily)
        assertEquals(setOf("hour", "minute", "time_zone"), issues.map { it.segments.last() }.toSet())
    }

    @Test
    fun `sound numeric errors do not impose limits absent from the server parser`() {
        val root = JsonParser.parseString("""{
            "buy_success":{"sound":{"id":"minecraft:entity.experience_orb.pickup","volume":"nope","pitch":-1.5}},
            "sell_success":{"sound":{"id":"minecraft:entity.experience_orb.pickup","volume":null,"pitch":"0.125"}}
        }""")
        val issues = AdminDraftValidation.validateFile("feedback.json", root)
        assertEquals(1, issues.size)
        assertEquals("validation.number", issues.single().key)
        assertEquals(listOf("buy_success", "sound", "volume"), issues.single().segments)
    }

    @Test
    fun `legacy bank arrays receive the same local numeric checks`() {
        val root = JsonParser.parseString("""[{"match":{"include":[{"item":"minecraft:stone"}]},"price":-1}]""")
        val issue = AdminDraftValidation.validateFile("global_bank.json", root).single()
        assertEquals("global_bank.json", issue.file)
        assertEquals(listOf("0", "price"), issue.segments)
        assertEquals("validation.at_least", issue.key)
    }

    private fun shopWithOffer(fields: String): JsonObject = JsonParser.parseString("""{
        "categories":[{"name":"A","offers":[{"id":"stone","match":{"include":[{"item":"minecraft:stone"}]},$fields}]}]
    }""").asJsonObject
}
