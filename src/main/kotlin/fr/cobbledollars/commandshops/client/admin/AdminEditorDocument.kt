package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Detached draft: navigation, resizing and server errors never discard unsaved changes. */
class AdminEditorDocument(snapshot: String) {
    private val original = JsonParser.parseString(snapshot).asJsonObject
    val files: JsonObject = original.getAsJsonObject("files").deepCopy()
    val dirtyCount: Int
        get() = (original.getAsJsonObject("files").keySet() + files.keySet()).count {
            original.getAsJsonObject("files").get(it)?.toString() != files.get(it)?.toString()
        }

    fun serialize(): String = JsonObject().apply {
        add("revision", original.get("revision"))
        add("files", files.deepCopy())
    }.toString()

    fun createShop(id: String, copyFrom: String? = null, defaultText: (String) -> String = AdminConfigSchema::defaultText): String {
        require(id.matches(Regex("[a-z0-9_-]+"))) { "invalid_id" }
        val path = "shops/$id/shop.json"
        require(files.keySet().none { it.equals(path, ignoreCase = true) }) { "duplicate_id" }
        files.add(path, copyFrom?.let { files.get(it).deepCopy() } ?: AdminConfigSchema.shop.newValue(defaultText))
        if (copyFrom != null) {
            val sourceBank = copyFrom.replace("/shop.json", "/bank.json")
            if (files.has(sourceBank)) files.add("shops/$id/bank.json", files.get(sourceBank).deepCopy())
        }
        return path
    }

    fun deleteFile(path: String) {
        require(path.startsWith("shops/"))
        files.remove(path)
        if (path.endsWith("/shop.json")) files.remove(path.replace("/shop.json", "/bank.json"))
    }

    fun resolve(path: String, segments: List<String>): JsonElement {
        var result = files.get(path)
        segments.forEach { result = if (result.isJsonArray) result.asJsonArray[it.toInt()] else result.asJsonObject.get(it) }
        return result
    }

    fun uniqueOfferIds(path: String, value: JsonElement): JsonElement {
        if (!path.endsWith("/shop.json")) return value
        val ids = files.getAsJsonObject(path).getAsJsonArray("categories")
            ?.flatMap { it.asJsonObject.getAsJsonArray("offers") ?: com.google.gson.JsonArray() }
            ?.mapNotNull { it.asJsonObject.get("id")?.asString }?.toMutableSet() ?: mutableSetOf()
        fun visit(node: JsonElement) {
            if (node.isJsonArray) node.asJsonArray.forEach(::visit)
            else if (node.isJsonObject) {
                val obj = node.asJsonObject
                if (obj.has("id") && obj.has("match")) {
                    var suffix = ids.size + 1
                    while ("offer_$suffix" in ids) suffix++
                    val id = "offer_$suffix"
                    obj.addProperty("id", id); ids += id
                } else obj.entrySet().forEach { visit(it.value) }
            }
        }
        visit(value)
        return value
    }
}
