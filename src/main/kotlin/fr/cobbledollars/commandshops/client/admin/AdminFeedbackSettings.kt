package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import fr.cobbledollars.commandshops.feedback.FeedbackChannel
import fr.cobbledollars.commandshops.feedback.FeedbackConfig
import java.util.Locale

/** Resolves inherited values without changing the draft or requiring its other events to be valid. */
object AdminFeedbackSettings {
    val eventKeys = listOf("buy_success", "buy_failure", "sell_success", "sell_failure", "shop_denied")

    /** Read the actual runtime defaults, rather than the editor's generic new-value templates. */
    fun defaultEvent(eventKey: String): JsonObject {
        val defaults = FeedbackConfig.defaults()
        val event = when (eventKey) {
            "buy_success" -> defaults.buySuccess()
            "buy_failure" -> defaults.buyFailure()
            "sell_success" -> defaults.sellSuccess()
            "sell_failure" -> defaults.sellFailure()
            "shop_denied" -> defaults.shopDenied()
            else -> throw IllegalArgumentException("Unknown feedback event: $eventKey")
        }
        return JsonObject().apply {
            add("channels", JsonArray().apply {
                FeedbackChannel.values().filter(event.channels()::contains).forEach { add(it.name.lowercase(Locale.ROOT)) }
            })
            add("sound", event.sound()?.let { sound ->
                JsonObject().apply {
                    addProperty("id", sound.sound().location.toString())
                    addProperty("volume", sound.volume())
                    addProperty("pitch", sound.pitch())
                }
            } ?: JsonNull.INSTANCE)
        }
    }

    /**
     * Mirrors FeedbackFiles inheritance. Invalid explicit values stay invalid, so displaying defaults
     * or opening a preview cannot silently repair or replace a user's unfinished draft.
     */
    fun effectiveEvent(root: JsonObject, eventKey: String): JsonObject {
        val defaults = defaultEvent(eventKey)
        val configured = root.get(eventKey)
        if (configured == null || configured.isJsonNull) return defaults
        require(configured.isJsonObject) { "Feedback event must be an object" }
        val event = configured.asJsonObject
        return JsonObject().apply {
            add("channels", event.get("channels")?.takeUnless(JsonElement::isJsonNull)?.deepCopy() ?: defaults.get("channels"))
            val rawSound = event.get("sound")
            val defaultSound = defaults.get("sound")
            add("sound", when {
                rawSound == null -> defaultSound
                rawSound.isJsonNull -> JsonNull.INSTANCE
                rawSound.isJsonPrimitive && rawSound.asJsonPrimitive.isString -> JsonObject().apply {
                    add("id", rawSound.deepCopy())
                    add("volume", defaultSound.asJsonObject.get("volume"))
                    add("pitch", defaultSound.asJsonObject.get("pitch"))
                }
                rawSound.isJsonObject -> rawSound.asJsonObject.deepCopy().apply {
                    listOf("volume", "pitch").forEach { key ->
                        if (!has(key) || get(key).isJsonNull) add(key, defaultSound.asJsonObject.get(key))
                    }
                }
                else -> rawSound.deepCopy()
            })
        }
    }

    fun effectiveField(root: JsonObject, eventKey: String, path: List<String>): JsonElement =
        read(effectiveEvent(root, eventKey), path)?.deepCopy() ?: JsonNull.INSTANCE

    fun effectiveField(root: JsonObject, eventKey: String, field: String): JsonElement =
        effectiveField(root, eventKey, listOf(field))

    /** An explicit event starts from all the values currently used by the server. */
    fun customizeEvent(root: JsonObject, eventKey: String): Boolean {
        if (root.get(eventKey)?.let { !it.isJsonNull } == true) return false
        root.add(eventKey, effectiveEvent(root, eventKey))
        return true
    }

    /** Add just the requested override, including volume/pitch nested inside a detailed sound. */
    fun customizeField(root: JsonObject, eventKey: String, path: List<String>): Boolean {
        require(path.isNotEmpty())
        val existing = root.get(eventKey)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val raw = existing?.let { read(it, path) }
        if (raw != null && (!raw.isJsonNull || path == listOf("sound"))) return false
        val value = effectiveField(root, eventKey, path)
        val event = existing ?: JsonObject().also { root.add(eventKey, it) }
        var parent = event
        path.dropLast(1).forEachIndexed { index, key ->
            val child = parent.get(key)
            parent = if (child?.isJsonObject == true) child.asJsonObject else {
                // Converting a simple sound must retain its id and effective volume/pitch.
                val resolved = effectiveField(root, eventKey, path.take(index + 1))
                val created = resolved.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: JsonObject()
                parent.add(key, created)
                created
            }
        }
        parent.add(path.last(), value)
        return true
    }

    fun customizeField(root: JsonObject, eventKey: String, field: String): Boolean =
        customizeField(root, eventKey, listOf(field))

    fun resetEvent(root: JsonObject, eventKey: String): Boolean = root.remove(eventKey) != null

    fun resetField(root: JsonObject, eventKey: String, path: List<String>): Boolean {
        require(path.isNotEmpty())
        val event = root.get(eventKey)?.takeIf(JsonElement::isJsonObject) ?: return false
        val parent = read(event, path.dropLast(1))?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return false
        return parent.remove(path.last()) != null
    }

    fun resetField(root: JsonObject, eventKey: String, field: String): Boolean =
        resetField(root, eventKey, listOf(field))

    fun soundFormat(root: JsonObject, eventKey: String): String {
        val raw = root.get(eventKey)?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("sound")
            ?: effectiveField(root, eventKey, "sound")
        return when {
            raw.isJsonNull -> "none"
            raw.isJsonObject -> "detailed"
            else -> "simple"
        }
    }

    /** Same-format selections are no-ops; simple/detailed conversions retain the selected id. */
    fun changeSoundFormat(root: JsonObject, eventKey: String, format: String): Boolean {
        require(format in listOf("none", "simple", "detailed"))
        if (soundFormat(root, eventKey) == format) return false
        val effective = effectiveField(root, eventKey, "sound")
        val sound = effective.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: defaultEvent(eventKey).getAsJsonObject("sound")
        val replacement = when (format) {
            "none" -> JsonNull.INSTANCE
            "simple" -> sound.get("id")?.deepCopy() ?: JsonPrimitive("")
            else -> sound.deepCopy()
        }
        val event = root.get(eventKey)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: JsonObject().also { root.add(eventKey, it) }
        event.add("sound", replacement)
        return true
    }

    private fun read(root: JsonElement, path: List<String>): JsonElement? {
        var value = root
        path.forEach { key ->
            if (!value.isJsonObject) return null
            value = value.asJsonObject.get(key) ?: return null
        }
        return value
    }
}
