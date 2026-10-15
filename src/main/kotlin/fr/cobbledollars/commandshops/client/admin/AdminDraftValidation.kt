package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigInteger
import java.time.ZoneId
import java.util.Locale

/** A local, certain error. Registry-dependent and complete validation remains on the server. */
data class AdminDraftIssue(
    val file: String,
    val segments: List<String>,
    val key: String,
    val args: List<String> = emptyList()
)

object AdminDraftValidation {
    fun validate(document: AdminEditorDocument): List<AdminDraftIssue> = validate(document.files)

    fun validate(files: JsonObject): List<AdminDraftIssue> = files.entrySet().flatMap { (file, root) ->
        validateFile(file, root)
    }

    fun validateFile(file: String, root: JsonElement): List<AdminDraftIssue> {
        val issues = mutableListOf<AdminDraftIssue>()
        visit(file, emptyList(), root, AdminConfigSchema.forFile(file, root), issues)
        return issues
    }

    fun at(issues: List<AdminDraftIssue>, file: String, segments: List<String>): List<AdminDraftIssue> =
        issues.filter { it.file == file && it.segments == segments }

    fun under(issues: List<AdminDraftIssue>, file: String, segments: List<String>): List<AdminDraftIssue> =
        issues.filter { it.file == file && it.segments.take(segments.size) == segments }

    private fun visit(
        file: String, path: List<String>, value: JsonElement, schema: ConfigSchema,
        issues: MutableList<AdminDraftIssue>
    ) {
        // Optional nulls include inherited feedback settings and absent score comparisons.
        if (value.isJsonNull) return
        if (value.isJsonArray) {
            schema.element?.let { element ->
                value.asJsonArray.forEachIndexed { index, child -> visit(file, path + index.toString(), child, element, issues) }
            }
            return
        }
        if (value.isJsonObject) {
            val node = value.asJsonObject
            if (AdminConfigSchema.isConditionRule(schema)) {
                val types = AdminConfigSchema.conditionTypes.filter(node::has)
                if (types.size != 1) issues.add(file, path, "condition_type")
                types.forEach { type ->
                    val group = node.get(type)
                    if (group.isJsonArray && group.asJsonArray.isEmpty) issues.add(file, path + type, "condition_empty")
                }
            }
            if (path.dropLast(1).lastOrNull() == "scores_all" && listOf("min", "max", "equals").none {
                    node.has(it) && !node.get(it).isJsonNull
                }) issues.add(file, path, "score_comparison")
            if (path.dropLast(1).lastOrNull() == "time_ranges_any") {
                val start = integer(node.get("start_tick"))
                val end = integer(node.get("end_tick"))
                if (start != null && start == end && start >= BigInteger.ZERO && start < DAY_TICKS)
                    issues.add(file, path + "end_tick", "time_distinct")
            }
            val restockType = if (schema === AdminConfigSchema.restock)
                node.get("type")?.takeIf(JsonElement::isJsonPrimitive)?.asString?.lowercase(Locale.ROOT) else null
            if (schema === AdminConfigSchema.restock) {
                val required = when (restockType) {
                    "interval" -> listOf("amount", "every_seconds")
                    "daily_reset" -> listOf("hour")
                    else -> emptyList()
                }
                required.filterNot(node::has).forEach { issues.add(file, path + it, "required") }
            }
            // Do not infer constraints for unrecognized fields; existing configurations keep them.
            schema.fields.forEach { (key, field) ->
                if (restockType == "interval" && key in listOf("hour", "minute", "time_zone") ||
                    restockType == "daily_reset" && key in listOf("amount", "every_seconds")) return@forEach
                val child = node.get(key)
                if (child == null || child.isJsonNull) {
                    if (field.required) issues.add(file, path + key, "required")
                } else if (field.required && isText(field.schema) &&
                    (!child.isJsonPrimitive || !child.asJsonPrimitive.isString || child.asString.isBlank())) {
                    issues.add(file, path + key, "required")
                } else visit(file, path + key, child, field.schema, issues)
            }
            return
        }
        if (isNumber(schema)) {
            validateNumber(file, path, value, issues)
        } else if (path.dropLast(1).lastOrNull() in STRING_CONDITIONS &&
            (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isBlank())) {
            issues.add(file, path, "required")
        } else if (path.lastOrNull() == "time_zone" && value.isJsonPrimitive &&
            runCatching { ZoneId.of(value.asString) }.isFailure) {
            issues.add(file, path, "time_zone")
        }
    }

    private fun validateNumber(file: String, path: List<String>, value: JsonElement, issues: MutableList<AdminDraftIssue>) {
        val key = path.lastOrNull()
        if (key == "volume" || key == "pitch") {
            // Mirror Float.parseFloat: no additional volume/pitch limits are imposed by the parser.
            if (!value.isJsonPrimitive || runCatching { java.lang.Float.parseFloat(value.asString) }.isFailure)
                issues.add(file, path, "number")
            return
        }
        val number = integer(value)
        if (number == null) {
            issues.add(file, path, "integer")
            return
        }
        val range = when (key) {
            "price" -> null
            "every_seconds" -> LONG_MIN to LONG_MAX
            else -> INT_MIN to INT_MAX
        }
        if (range != null && (number < range.first || number > range.second)) {
            issues.add(file, path, "range", range.first.toString(), range.second.toString())
            return
        }
        when (key) {
            "count", "required_bundles", "amount", "every_seconds" ->
                if (number <= BigInteger.ZERO) issues.add(file, path, "at_least", "1")
            "price" -> if (number < BigInteger.ZERO) issues.add(file, path, "at_least", "0")
            "stock" -> if (number < BigInteger.valueOf(-1)) issues.add(file, path, "at_least", "-1")
            "start_tick", "end_tick" -> if (number < BigInteger.ZERO || number >= DAY_TICKS)
                issues.add(file, path, "range", "0", "23999")
            "hour" -> if (number < BigInteger.ZERO || number > BigInteger.valueOf(23))
                issues.add(file, path, "range", "0", "23")
            "minute" -> if (number < BigInteger.ZERO || number > BigInteger.valueOf(59))
                issues.add(file, path, "range", "0", "59")
        }
    }

    private fun integer(value: JsonElement?): BigInteger? =
        value?.takeIf(JsonElement::isJsonPrimitive)?.let { runCatching { BigInteger(it.asString) }.getOrNull() }

    private fun isNumber(schema: ConfigSchema): Boolean = JsonParser.parseString(schema.defaultJson).let {
        it.isJsonPrimitive && it.asJsonPrimitive.isNumber
    }

    private fun isText(schema: ConfigSchema): Boolean = JsonParser.parseString(schema.defaultJson).let {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }

    private fun MutableList<AdminDraftIssue>.add(file: String, path: List<String>, key: String, vararg args: String) {
        add(AdminDraftIssue(file, path.toList(), "validation.$key", args.toList()))
    }

    private val STRING_CONDITIONS = setOf("player_tags_all", "player_tags_any", "player_tags_none", "advancements_all", "advancements_any", "dimensions_any")
    private val INT_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    private val INT_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
    private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    private val DAY_TICKS = BigInteger.valueOf(24000)
}
