package fr.cobbledollars.commandshops.client.admin

import com.google.gson.*
import fr.cobbledollars.commandshops.audit.AuditQuery
import fr.cobbledollars.commandshops.client.ui.*
import fr.cobbledollars.commandshops.client.ui.components.*
import fr.cobbledollars.commandshops.client.ui.form.UiForm
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/** Read-only browser. All state belongs to this screen, independently of configuration drafts. */
internal class AdminAuditPanel(private val screen: CommandShopAdminScreen) {
    val sections = listOf("summary", "shops", "players", "items", "offers", "journal")
    var view = "summary"; private set
    private var window = "all"
    private var search = ""
    private var shop = ""
    private var player = ""
    private var item = ""
    private var offer = ""
    private var event = "all"
    private var sort = "volume"
    private var page = 0
    private var cursor = ""
    private val previousCursors = mutableListOf<String>()
    private var detail: JsonObject? = null
    private var autoDetail: String? = null
    private var filtersExpanded = false
    private var operation: UUID? = null
    private var requestedQuery = ""
    private var queuedRefresh = false
    private var queued = false
    private var since = 0L
    var pending = false; private set
    var needsRebuild = false
    private var error = ""
    private var data: JsonObject? = null

    fun text(key: String, vararg args: Any): String = Component.translatable("gui.cobbledollarscommandshops.admin.audit.$key", *args).string
    fun sectionName(value: String) = text("view.$value")
    private fun locale() = Locale.forLanguageTag(Minecraft.getInstance().languageManager.selected.replace('_','-'))
    private fun date(millis: Long) = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale())
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
    private fun option(value: String): String = translated("option.$value", value)
    private fun translated(key: String, fallback: String) =
        if (net.minecraft.client.resources.language.I18n.exists("gui.cobbledollarscommandshops.admin.audit.$key")) text(key) else fallback
    private fun query() = AuditQuery(view, window, search, shop, player, item, offer, event, sort, page, cursor).json()
    fun scrollKey(): List<String> = listOf("audit", view, window, search, shop, player, item, offer,
        event, sort, page.toString(), cursor, detail?.get("id")?.asString.orEmpty())
    fun hasScrollContent(): Boolean = !pending && !queued && data?.get("view")?.asString == view
    private fun resetPage() { page = 0; cursor = ""; previousCursors.clear(); detail = null }

    fun open() { if (data == null) load() }
    fun select(section: String) {
        if (section !in sections) return
        if (view == section) return
        screen.commitAuditFields()
        view = section; search = ""; sort = if (view == "journal") "recent" else "volume"
        resetPage(); data = null; load()
    }
    fun resetFilters() {
        screen.commitAuditFields()
        search = ""; shop = ""; player = ""; item = ""; offer = ""; event = "all"
        resetPage(); load()
    }
    fun refresh() { screen.commitAuditFields(); resetPage(); load(true) }

    private fun load(refresh: Boolean = false, commitFields: Boolean = true) {
        if (commitFields) screen.commitAuditFields()
        if (pending) { queued = true; queuedRefresh = queuedRefresh || refresh; needsRebuild = true; return }
        val request = runCatching { query() }.getOrElse { error = text("invalid_filter"); needsRebuild = true; return }
        val id = UUID.randomUUID()
        operation = id; requestedQuery = request; pending = true; since = System.currentTimeMillis(); error = ""
        needsRebuild = true
        if (AdminAuditClient.request(screen, request, refresh, id) == null) {
            pending = false; operation = null; data = null; error = text("unsupported")
        }
    }

    fun accept(id: UUID, json: String) {
        if (id != operation) return
        operation = null; pending = false
        val result = AdminAuditClient.result(json)
        // A changed selection cannot receive the preceding selection's data.
        if (!queued && requestedQuery == runCatching { query() }.getOrDefault("")) {
            if (result.success) {
                data = result.state; page = result.state.get("page").asInt; error = ""
                autoDetail?.let { selected -> detail = result.state.getAsJsonArray("rows")
                    .firstOrNull { it.asJsonObject.get("id").asString == selected }?.asJsonObject }
                autoDetail = null
            } else { error = result.message; data = null }
        }
        needsRebuild = true
        if (queued) { val refresh = queuedRefresh; queued = false; queuedRefresh = false; load(refresh, false) }
    }

    fun tick() {
        if (pending && System.currentTimeMillis() - since > 35_000L) {
            AdminAuditClient.invalidate(screen); operation = null; pending = false; queued = false
            error = text("timeout"); needsRebuild = true
        }
    }

    private fun choice(label: String, value: String, options: List<String>, help: String, apply: (String) -> Unit): UiComponent =
        UiSelectComponent(label, LambdaFieldBinding(label, { value }, {}), displayText = ::option,
            onRequestSelection = { rect -> screen.openAuditDropdown(options, value, rect, ::option) { selected ->
                apply(selected)
            } }, tooltipProvider = { listOf(Component.literal(text(help))) })

    fun buildForm(): UiForm {
        val rows = mutableListOf<UiComponent>()
        fun info(label: String, hint: String = text("help.read_only"), color: Int = 0xBBBBBB) {
            rows += AdminActionRow(label, emptyList(), hint, color)
        }
        fun field(label: String, value: String, help: String = text("help.read_only") + "\n" + value) {
            rows += UiTextFieldComponent(label, LambdaFieldBinding(label, { value }, {}), editable = false,
                tooltipProvider = { help.lines().map(Component::literal) })
        }
        fun recordedField(key: String, value: JsonElement) {
            val helpKey = "gui.cobbledollarscommandshops.admin.audit.help.$key"
            val explanation = if (net.minecraft.client.resources.language.I18n.exists(helpKey)) text("help.$key") else text("help.read_only")
            val formatted = if (value.isJsonObject || value.isJsonArray) GsonBuilder().setPrettyPrinting().create().toJson(value) else valueText(key,value)
            field(translated("field.$key", key), valueText(key,value), explanation + "\n" + formatted)
        }
        rows += UiSectionHeaderComponent(sectionName(view), 0xFFCC88,
            tooltipProvider = { listOf(Component.translatable("gui.cobbledollarscommandshops.admin.help.tab.audit")) })
        rows += choice(text("period"), window, listOf("all","24h","7d","30d"), "help.period") {
            window = it; resetPage(); data = null; load()
        }
        val filterCount = listOf(shop,player,item,offer).count { it.isNotBlank() } + if (event == "all") 0 else 1
        rows += AdminActionRow(text("filters", filterCount), listOf(text(if (filtersExpanded) "hide_filters" else "show_filters") to {
            screen.commitAuditFields(); filtersExpanded = !filtersExpanded; needsRebuild = true
        }, text("reset") to ::resetFilters), text("help.filters"), actionHints = listOf(text("help.filters"),text("help.reset")))
        if (filtersExpanded) {
            fun input(key: String, get: () -> String, set: (String) -> Unit) {
                rows += UiTextFieldComponent(text(key), LambdaFieldBinding("audit.$key", get, set),
                    tooltipProvider = { listOf(Component.literal(text("help.$key"))) })
            }
            input("shop", { shop }, { shop = it }); input("player", { player }, { player = it })
            input("item", { item }, { item = it }); input("offer", { offer }, { offer = it })
            rows += choice(text("event"), event, listOf("all","buy_success","buy_failure","sell_success","sell_failure","visibility_changed","stock_changed","config_changed"),"help.event") { event = it; needsRebuild = true }
            rows += AdminActionRow("", listOf(text("apply_filters") to { screen.commitAuditFields(); resetPage(); data = null; load() }),
                actionHints = listOf(text("help.filters")))
        }
        if (view != "summary" && detail == null) {
            rows += UiTextFieldComponent(text("search"), LambdaFieldBinding("audit.search", { search }, { search = it }),
                tooltipProvider = { listOf(Component.literal(text("help.search"))) })
            rows += AdminActionRow("", listOf(text("search") to { screen.commitAuditFields(); resetPage(); data = null; load() }), actionHints = listOf(text("help.search")))
            if (view != "journal") rows += choice(text("sort"), sort, listOf("volume","count","name"), "help.sort") {
                sort = it; resetPage(); data = null; load()
            }
        }
        if (pending) info(text("loading"))
        if (error.isNotBlank()) info(error, error, 0xFF7777)
        val state = data?.takeUnless { pending || queued || it.get("view").asString != view }
        if (state == null) return UiForm(rows, gap = 3)
        info(text("loaded_at", date(state.get("loadedAt").asLong)))
        if (!state.get("auditEnabled").asBoolean) info(text("disabled"), text("help.recording"), 0xFFCC88)
        val missing = listOf("buy_success","buy_failure","sell_success","sell_failure","visibility_changed","stock_changed","config_changed")
            .filterNot { key -> state.getAsJsonArray("enabledEvents").any { it.asString == key } }
        if (missing.isNotEmpty()) info(text("not_recorded", missing.joinToString(", ", transform = ::option)), text("help.recording"), 0xFFCC88)
        val invalid = state.get("invalidLines").asInt; val unreadable = state.get("unreadableFiles").asInt
        if (invalid > 0 || unreadable > 0) info(text("partial", invalid, unreadable), text("help.partial"), 0xFF7777)
        if (view == "summary") {
            state.getAsJsonObject("summary").entrySet().forEach { (key,value) -> recordedField(key,value) }
            rows += AdminActionRow(text("view.journal"), listOf(text("transactions") to { select("journal") }),
                text("help.transactions"), actionHints = listOf(text("help.transactions")))
        } else if (detail != null) {
            val selected = detail!!
            rows += AdminActionRow(text("details"), listOf(text("back") to { detail = null; needsRebuild = true }),
                text("help.back"), actionHints = listOf(text("help.back")))
            selected.get("operation_id")?.takeIf(JsonElement::isJsonPrimitive)?.asString?.let { id ->
                rows += AdminActionRow(text("field.operation_id"), listOf(text("view_operation") to { showOperation(id) }),
                    text("help.view_operation"), actionHints = listOf(text("help.view_operation")))
            }
            if (selected.entrySet().any { (key,value) -> key.endsWith("_truncated") && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && value.asBoolean }) {
                info(text("truncated"),text("help.truncated"),0xFFCC88)
            }
            val hidden = setOf("id","name","operationCount")
            selected.entrySet().filterNot { it.key in hidden }.forEach { (key,value) ->
                if (key in setOf("item_lines","bonus_lines") && value.isJsonArray) {
                    rows += UiSectionHeaderComponent(text("field.$key"), 0xFFCC88,
                        tooltipProvider = { listOf(Component.literal(text("help.items"))) })
                    value.asJsonArray.forEachIndexed { index, line ->
                        rows += UiSectionHeaderComponent(text("item_line", index + 1), 0xBBBBBB,
                            tooltipProvider = { listOf(Component.literal(text("help.items"))) })
                        if (line.isJsonObject) line.asJsonObject.entrySet().forEach { (lineKey,lineValue) ->
                            recordedField(lineKey,lineValue)
                        } else recordedField("value",line)
                    }
                } else recordedField(key,value)
            }
            if (view != "journal") rows += AdminActionRow(text("view.journal"), listOf(text("transactions") to { showJournal(selected) }),
                text("help.transactions"), actionHints = listOf(text("help.transactions")))
            else {
                fun related(target: String, id: String) {
                    rows += AdminActionRow(sectionName(target) + ": " + id,
                        listOf(text("details") to { jump(target,id) }), text("help.related"), actionHints = listOf(text("help.related")))
                }
                selected.get("shop_id")?.asString?.let { related("shops",it) }
                selected.getAsJsonArray("shop_ids")?.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
                    ?.distinct()?.forEach { related("shops",it) }
                selected.get("player_uuid")?.asString?.let { related("players",it) }
                selected.get("offer_id")?.asString?.let { id -> selected.get("shop_id")?.asString?.let { related("offers", "$it/$id") } }
                selected.getAsJsonArray("item_lines")?.mapNotNull { it.asJsonObject.get("item")?.asString }?.distinct()?.forEach { related("items",it) }
                selected.get("item_id")?.takeIf(JsonElement::isJsonPrimitive)?.asString?.let { related("items",it) }
            }
        } else {
            val resultRows = state.getAsJsonArray("rows")
            if (resultRows.isEmpty) info(text("empty"), text("help.recording"))
            val pageActions = mutableListOf<Pair<String, () -> Unit>>()
            if (page > 0) pageActions += text("previous") to {
                page--; if (view == "journal") cursor = previousCursors.removeLast(); detail = null; data = null; load()
            }
            if (state.get("hasNext").asBoolean) pageActions += text("next") to {
                page++; if (view == "journal") { previousCursors += cursor; cursor = state.get("nextCursor").asString }
                detail = null; data = null; load()
            }
            rows += AdminActionRow(text("page", page + 1, state.get("total").asLong), pageActions,
                text("help.pages"), actionHints = pageActions.map { text("help.pages") })
            resultRows.forEach { element ->
                val row = element.asJsonObject
                val label = if (view == "journal") {
                    date(Instant.parse(row.get("timestamp").asString).toEpochMilli()) + " · " + option(row.get("type").asString) +
                        (row.get("phase")?.takeIf(JsonElement::isJsonPrimitive)?.asString?.let { " · " + option(it) } ?: "")
                } else displayName(row) + " · " + valueText("volume", row.get("volume"))
                rows += AdminActionRow(label, listOf(text("details") to { detail = row.deepCopy(); needsRebuild = true }),
                    rowHint(row, label), actionHints = listOf(text("help.details")))
            }
        }
        return UiForm(rows, gap = 3)
    }

    private fun rowHint(row: JsonObject, label: String): String = buildList {
        add(label)
        add(row.get("id").asString)
        if (view == "journal") {
            listOf("actor_name","player_name","shop_id","offer_id","stock_before","stock_after","file","path","change","phase")
                .forEach { key -> row.get(key)?.let { add(translated("field.$key",key) + ": " + valueText(key,it)) } }
        } else {
            add(text("field.volume") + ": " + valueText("volume", row.get("volume")))
            when (view) {
                "players" -> {
                    add(text("preview.player_in", valueText("earned", row.get("earned"))))
                    add(text("preview.player_out", valueText("spent", row.get("spent"))))
                }
                "shops" -> {
                    add(text("preview.shop_in", valueText("spent", row.get("spent"))))
                    add(text("preview.shop_out", valueText("earned", row.get("earned"))))
                }
                else -> {
                    row.get("spent")?.let { add(text("field.spent") + ": " + valueText("spent", it)) }
                    row.get("earned")?.let { add(text("field.earned") + ": " + valueText("earned", it)) }
                }
            }
        }
        add(text("help.details"))
    }.joinToString("\n")

    private fun displayName(row: JsonObject): String {
        val name = row.get("name").asString
        return when (view) {
            "items" -> localItem(row.get("id").asString, name)
            "offers" -> row.get("shopId").asString + "/" + name
            else -> name
        }
    }
    private fun localItem(id: String, fallback: String): String = net.minecraft.resources.ResourceLocation.tryParse(id)?.let {
        net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(it).orElse(null)?.description?.string
    } ?: fallback
    private fun valueText(key: String, value: JsonElement): String {
        if (value.isJsonNull) return text("json_null")
        if (key == "before" || key == "after") return value.toString()
        if (!value.isJsonPrimitive) return value.toString()
        if (key in setOf("before_present","after_present")) return text(if (value.asBoolean) "present" else "absent")
        if (key == "timestamp") return runCatching { date(Instant.parse(value.asString).toEpochMilli()) }.getOrDefault(value.asString)
        if (key in setOf("type","currency_flow","action","source","phase","change")) return option(value.asString)
        if (key == "enabled") return text(if (value.asBoolean) "enabled" else "disabled_shop")
        if (key == "item" || key == "itemId" || key == "item_id") return localItem(value.asString, value.asString) + " (" + value.asString + ")"
        if (key == "reason") {
            val reason = value.asString
            val family = if (reason == "nothing_sellable") "sell_failure" else "buy_failure"
            val translation = "cobbledollarscommandshops.feedback.$family.$reason"
            if (net.minecraft.client.resources.language.I18n.exists(translation)) return Component.translatable(translation).string
        }
        val raw = value.asString
        val numeric = key in setOf("spent","earned","netFlow","volume","currency_amount","bundle_count","item_count_total",
            "stock_before","stock_after","count","unit_price","line_total","schema_version","change_count") || key.endsWith("Count")
        return if (numeric) runCatching { java.text.NumberFormat.getIntegerInstance(locale()).format(BigInteger(raw)) }.getOrDefault(raw) else raw
    }

    private fun showJournal(row: JsonObject) {
        when (view) {
            "shops" -> shop = row.get("id").asString
            "players" -> player = row.get("id").asString
            "items" -> item = row.get("id").asString
            "offers" -> { shop = row.get("shopId").asString; offer = row.get("offerId").asString }
        }
        select("journal")
    }
    private fun showOperation(id: String) {
        screen.commitAuditFields()
        view = "journal"; search = id; sort = "recent"
        shop = ""; player = ""; item = ""; offer = ""; event = "all"
        resetPage(); data = null; load()
    }
    private fun jump(target: String, id: String) {
        shop = ""; player = ""; item = ""; offer = ""; event = "all"
        when (target) { "shops" -> shop = id; "players" -> player = id; "items" -> item = id
            "offers" -> { shop = id.substringBefore('/'); offer = id.substringAfter('/') } }
        autoDetail = id; select(target)
    }
}
