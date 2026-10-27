package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import fr.cobbledollars.commandshops.audit.AuditEventType

/** Editor metadata only. The existing server parsers remain the source of validation. */
data class ConfigField(val schema: ConfigSchema, val required: Boolean = false)

data class ConfigSchema(
    val defaultJson: String,
    val fields: Map<String, ConfigField> = emptyMap(),
    val element: ConfigSchema? = null,
    val choices: List<String> = emptyList(),
    val variants: List<String> = emptyList(),
    val defaultTextKey: String? = null
) {
    /** Resolve display text only when creating a value; persisted values are never translated. */
    fun newValue(defaultText: (String) -> String = AdminConfigSchema::defaultText): JsonElement =
        resolveDefault(JsonParser.parseString(defaultJson), defaultText)

    private fun resolveDefault(value: JsonElement, defaultText: (String) -> String): JsonElement {
        defaultTextKey?.let { return JsonPrimitive(defaultText(it)) }
        if (value.isJsonObject) fields.forEach { (key, field) ->
            value.asJsonObject.get(key)?.let { value.asJsonObject.add(key, field.schema.resolveDefault(it, defaultText)) }
        }
        if (value.isJsonArray) element?.let { schema ->
            value.asJsonArray.forEachIndexed { index, item ->
                value.asJsonArray.set(index, schema.resolveDefault(item, defaultText))
            }
        }
        return value
    }
}

object AdminConfigSchema {
    fun defaultText(key: String): String = when (key) {
        "new_category" -> "New category"
        "access_denied" -> "Access denied"
        else -> key
    }

    private fun text(value: String = "") = ConfigSchema(com.google.gson.Gson().toJson(value))
    private fun localizedText(key: String) = text(defaultText(key)).copy(defaultTextKey = key)
    private fun number(value: Number) = ConfigSchema(value.toString())
    private fun bool(value: Boolean) = ConfigSchema(value.toString())
    private fun list(value: ConfigSchema) = ConfigSchema("[]", element = value)
    private fun obj(vararg fields: Pair<String, ConfigField>, default: String = "{}") =
        ConfigSchema(default, linkedMapOf(*fields))
    private fun optional(schema: ConfigSchema) = ConfigField(schema)
    private fun required(schema: ConfigSchema) = ConfigField(schema, true)
    private fun choice(value: String, vararg options: String) = text(value).copy(choices = options.toList())

    private val conditionGroups = obj(
        "player_tags_all" to optional(list(text("tag"))),
        "player_tags_any" to optional(list(text("tag"))),
        "player_tags_none" to optional(list(text("tag"))),
        "advancements_all" to optional(list(text("minecraft:story/root"))),
        "advancements_any" to optional(list(text("minecraft:story/root"))),
        "dimensions_any" to optional(list(text("minecraft:overworld"))),
        "time_ranges_any" to optional(list(obj(
            "start_tick" to required(number(0)), "end_tick" to required(number(12000)),
            default = "{\"start_tick\":0,\"end_tick\":12000}"
        ))),
        "scores_all" to optional(list(obj(
            "objective" to required(text("objective")),
            "min" to optional(number(0)), "max" to optional(number(100)), "equals" to optional(number(0)),
            default = "{\"objective\":\"objective\",\"min\":0}"
        )))
    )

    /** Also defines the legacy evaluator order when converting an object to ordered rules. */
    val conditionTypes: List<String> = conditionGroups.fields.keys.toList()

    private val ruleFields = conditionGroups.fields.mapValues { (_, field) ->
        // A newly selected family starts with a valid, editable condition instead of an empty group.
        val item = field.schema.element!!.newValue()
        ConfigField(field.schema.copy(defaultJson = JsonArray().apply { add(item) }.toString()))
    }
    val visibilityConditionRule = ConfigSchema(
        "{\"player_tags_all\":[\"tag\"]}", fields = ruleFields, variants = conditionTypes
    )
    val accessConditionRule = visibilityConditionRule.copy(fields = linkedMapOf<String, ConfigField>().apply {
        putAll(ruleFields)
        put("deny_message", optional(text()))
    })

    // New access and visibility conditions use ordered rules. Fields still describe existing objects.
    val conditions = conditionGroups.copy(defaultJson = "[]", element = visibilityConditionRule)
    val accessConditions = conditions.copy(element = accessConditionRule)

    fun isConditionRule(schema: ConfigSchema): Boolean =
        schema === visibilityConditionRule || schema === accessConditionRule

    fun isConditions(schema: ConfigSchema): Boolean = schema === conditions || schema === accessConditions

    /** Flattening may discard only known empty conditions, never extensions or malformed values. */
    fun canDiscardConditions(value: JsonElement?): Boolean = when {
        value == null || value.isJsonNull -> true
        value.isJsonArray -> value.asJsonArray.isEmpty
        value.isJsonObject -> value.asJsonObject.entrySet().all { (key, group) ->
            key in conditionTypes && (group.isJsonNull || group.isJsonArray && group.asJsonArray.isEmpty)
        }
        else -> false
    }

    /** Split independent requirements, keep alternatives grouped, and preserve the evaluator order. */
    fun orderedConditions(legacy: JsonObject): JsonArray {
        require(legacy.keySet().all(conditionTypes::contains)) { "Unknown legacy condition fields" }
        return JsonArray().apply {
            conditionTypes.forEach { type -> legacy.get(type)?.let { group ->
                if (!group.isJsonNull && (!group.isJsonArray || !group.asJsonArray.isEmpty)) {
                    if (group.isJsonArray && type in listOf("player_tags_all", "player_tags_none", "advancements_all", "scores_all")) {
                        group.asJsonArray.forEach { requirement ->
                            add(JsonObject().apply { add(type, JsonArray().apply { add(requirement.deepCopy()) }) })
                        }
                    } else add(JsonObject().apply { add(type, group.deepCopy()) })
                }
            } }
        }
    }

    val atom = obj(
        "item" to optional(text("minecraft:stone")),
        "stack" to optional(text("minecraft:stone")),
        "tag" to optional(text("minecraft:logs")),
        "mod" to optional(text("minecraft")),
        default = "{\"item\":\"minecraft:stone\"}"
    ).copy(variants = listOf("item", "stack", "tag", "mod"))

    val match = obj(
        "include" to required(list(atom)), "exclude" to optional(list(atom)),
        default = "{\"include\":[{\"item\":\"minecraft:stone\"}]}"
    )

    val restock = obj(
        "type" to required(choice("interval", "interval", "daily_reset")),
        "amount" to optional(number(1)), "every_seconds" to optional(number(3600)),
        "hour" to optional(number(0)), "minute" to optional(number(0)),
        "time_zone" to optional(text("Europe/Paris")),
        default = "{\"type\":\"interval\",\"amount\":1,\"every_seconds\":3600}"
    )

    val reward = obj(
        "item" to optional(text("minecraft:diamond")), "stack" to optional(text("minecraft:diamond")),
        "count" to optional(number(1)), default = "{\"item\":\"minecraft:diamond\",\"count\":1}"
    ).copy(variants = listOf("item", "stack"))

    val bonus = obj(
        "required_bundles" to required(number(1)), "rewards" to required(list(reward)),
        default = "{\"required_bundles\":1,\"rewards\":[{\"item\":\"minecraft:diamond\",\"count\":1}]}"
    )

    val shopOffer = obj(
        "id" to required(text("new_offer")), "match" to required(match),
        "count" to optional(number(1)), "price" to required(number(1)),
        "stock" to optional(number(-1)), "restock" to optional(restock),
        "conditions" to optional(conditions), "purchase_bonuses" to optional(list(bonus)),
        default = "{\"id\":\"new_offer\",\"match\":{\"include\":[{\"item\":\"minecraft:stone\"}]},\"count\":1,\"price\":1,\"stock\":-1}"
    )

    val bankOffer = obj(
        "match" to required(match), "price" to required(number(1)), "conditions" to optional(conditions),
        default = "{\"match\":{\"include\":[{\"item\":\"minecraft:stone\"}]},\"price\":1}"
    )

    private fun category(offer: ConfigSchema) = obj(
        "name" to required(localizedText("new_category")), "conditions" to optional(conditions),
        "offers" to required(list(offer)), default = "{\"name\":\"New category\",\"offers\":[]}"
    )

    val shop = obj(
        "deny_message" to optional(localizedText("access_denied")), "conditions" to optional(accessConditions),
        "categories" to required(list(category(shopOffer))),
        default = "{\"categories\":[{\"name\":\"New category\",\"offers\":[]}]}"
    )

    val bank = obj(
        "deny_message" to optional(localizedText("access_denied")),
        "conditions" to optional(accessConditions), "offers" to optional(list(bankOffer)),
        "categories" to optional(list(category(bankOffer))), default = "{\"categories\":[]}"
    )

    val sound = obj(
        "id" to required(text("minecraft:entity.experience_orb.pickup")),
        "volume" to optional(number(0.25)), "pitch" to optional(number(1.8)),
        default = "{\"id\":\"minecraft:entity.experience_orb.pickup\",\"volume\":0.25,\"pitch\":1.8}"
    )

    private fun feedbackEvent(failure: Boolean, denied: Boolean = false): ConfigSchema = obj(
        "channels" to optional(list(choice("chat", "action_bar", "chat", "sound"))),
        "sound" to optional(if (failure) sound.copy(defaultJson = "{\"id\":\"minecraft:entity.villager.no\",\"volume\":0.8,\"pitch\":1.0}") else sound),
        default = "{\"channels\":[\"${if (denied) "chat" else "action_bar"}\",\"sound\"]}"
    )
    val feedback = obj(*listOf("buy_success", "buy_failure", "sell_success", "sell_failure", "shop_denied")
        .map { it to optional(feedbackEvent(it.endsWith("failure") || it == "shop_denied", it == "shop_denied")) }.toTypedArray())
    val audit = obj(
        "enabled" to optional(bool(true)), "flush_each_entry" to optional(bool(true)),
        "events" to optional(obj(*AuditEventType.entries
            .map { it.configKey() to optional(bool(it.enabledByDefault())) }.toTypedArray()))
    )
    val commands = obj("allow_player_open" to optional(bool(true)))

    fun forFile(path: String, value: JsonElement): ConfigSchema = when {
        path == "audit.json" -> audit
        path == "commands.json" -> commands
        path == "feedback.json" -> feedback
        path.endsWith("shop.json") -> shop
        value.isJsonArray -> list(bankOffer) // Legacy flat bank format is preserved.
        else -> bank
    }

    fun atPath(path: String, root: JsonElement, segments: List<String>): ConfigSchema {
        var node = root
        var schema = forFile(path, root)
        segments.forEach { segment ->
            schema = if (node.isJsonArray) schema.element ?: ConfigSchema("{}")
                else schema.fields[segment]?.schema ?: ConfigSchema("{}")
            node = if (node.isJsonArray) node.asJsonArray[segment.toInt()] else node.asJsonObject.get(segment)
        }
        return schema
    }

    /** Missing optional fields stay absent until explicitly added in the form. */
    fun visibleFields(schema: ConfigSchema, value: JsonObject): Map<String, ConfigField> {
        val result = LinkedHashMap(schema.fields)
        if (schema.variants.isNotEmpty()) {
            val variant = schema.variants.firstOrNull(value::has) ?: schema.variants.first()
            schema.variants.filter { it != variant && !value.has(it) }.forEach(result::remove)
        }
        if (schema === bank) {
            if (value.has("categories")) result.remove("offers")
            if (value.has("offers")) result.remove("categories")
        }
        if (schema === restock) {
            val hidden = if (value.get("type")?.asString == "daily_reset") listOf("amount", "every_seconds")
                else listOf("hour", "minute", "time_zone")
            hidden.filterNot(value::has).forEach(result::remove)
        }
        value.keySet().filterNot(result::containsKey).forEach { key ->
            result[key] = optional(ConfigSchema(value.get(key).toString()))
        }
        return result
    }
}
