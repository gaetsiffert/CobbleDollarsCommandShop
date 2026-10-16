package fr.cobbledollars.commandshops.client.admin

import com.google.gson.*
import fr.cobbledollars.commandshops.admin.network.AdminNetwork
import fr.cobbledollars.commandshops.client.ui.*
import fr.cobbledollars.commandshops.client.ui.components.*
import fr.cobbledollars.commandshops.client.ui.form.UiForm
import fr.cobbledollars.commandshops.client.ui.overlay.UiDropdownOverlay
import fr.cobbledollars.commandshops.client.ui.overlay.UiItemPickerOverlay
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.Item
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID

class CommandShopAdminScreen(snapshot: String) : Screen(Component.translatable("gui.cobbledollarscommandshops.admin.title")) {
    private var document = AdminEditorDocument(snapshot)
    private var selectedFile = document.files.keySet().sorted().firstOrNull { it.endsWith("/shop.json") } ?: "audit.json"
    private var tab = when { selectedFile.endsWith("/shop.json") -> 0; selectedFile.endsWith("bank.json") -> 1; else -> 3 }
    private val segments = mutableListOf<String>()
    private var form = UiForm(emptyList())
    private var fileScroll = 0
    private var search = ""
    private var searchForm = UiForm(emptyList())
    private var status = ""
    private var statusError = false
    private var pending = false
    private var pendingSince = 0L
    private var pendingOperation: UUID? = null
    private var deferredSnapshot: String? = null
    private var validationIssues = emptyList<AdminDraftIssue>()
    private var lastDirtyCount = 0
    private var liveOverview: JsonObject? = null
    private var liveStock: JsonObject? = null
    private var liveOperation: UUID? = null
    private var liveAction = ""
    private var livePending = false
    private var livePendingSince = 0L
    private var liveRefreshRequired = false
    private var liveFormDirty = false
    private var stockTarget = ""
    private var stockAllOnline = false
    private var stockPage = 0
    private val stockValues = mutableMapOf<String, String>()
    private data class ScreenAction(val rect: UiRect, val action: () -> Unit, val hint: String?)
    private data class FooterAction(val label: String, val action: () -> Unit, val hint: String)
    private val clickActions = mutableListOf<ScreenAction>()
    private var modal: Modal? = null
    private val dropdown = UiDropdownOverlay { tr("done") }
    private var dropdownState: UiDropdownOverlay.State? = null
    private var dropdownApply: ((Set<String>) -> Unit)? = null
    private val itemPicker = UiItemPickerOverlay({ tr("pick_item") }, { tr("search") })
    private val itemSearch = UiSingleLineEditorState()
    private var itemResults = emptyList<Item>()
    private var itemScroll = 0
    private var itemSearchFocused = true
    private var itemApply: ((String) -> Unit)? = null

    private data class Modal(val title: String, val form: UiForm, val confirm: () -> Unit)
    private fun tr(key: String, vararg args: Any): String = Component.translatable("gui.cobbledollarscommandshops.admin.$key", *args).string
    private fun defaultText(key: String): String =
        if (net.minecraft.client.resources.language.I18n.exists("gui.cobbledollarscommandshops.admin.default.$key")) tr("default.$key")
        else AdminConfigSchema.defaultText(key)
    private fun fieldLabel(key: String): String {
        val labelKey = if (key == "deny_message" && segments.isEmpty()) "deny_message_fallback" else key
        val translation = "gui.cobbledollarscommandshops.admin.field.$labelKey"
        return if (net.minecraft.client.resources.language.I18n.exists(translation)) Component.translatable(translation).string else key
    }
    private fun help(key: String): String {
        val contextualKey = when {
            key == "deny_message" && segments.size >= 2 && segments[segments.size - 2] == "conditions" -> "condition_deny_message"
            key == "conditions" && segments.isNotEmpty() && segments != listOf("conditions") -> "visibility_conditions"
            key == "price" && selectedFile.endsWith("bank.json") -> "bank_price"
            key == "count" && "rewards" in segments -> "reward_count"
            key == "id" && segments.lastOrNull() == "sound" -> "sound_id"
            else -> key
        }
        val translation = "gui.cobbledollarscommandshops.admin.help.$contextualKey"
        return if (net.minecraft.client.resources.language.I18n.exists(translation)) Component.translatable(translation).string else tr("help.value")
    }
    private fun hints(vararg actions: String): List<String> = actions.map { tr("tooltip.$it") }
    private fun playClick() {
        minecraft?.soundManager?.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f))
    }

    override fun init() {
        searchForm = UiForm(listOf(UiTextFieldComponent(tr("search"), LambdaFieldBinding("search", { search }, {
            search = it; fileScroll = 0
        }))))
        rebuildForm()
        if (liveOverview == null && !livePending && AdminLiveClient.supported()) requestLive("overview")
    }

    fun acceptSnapshot(json: String) {
        document = AdminEditorDocument(json)
        if (!document.files.has(selectedFile)) selectedFile = visibleFiles().firstOrNull() ?: document.files.keySet().sorted().first()
        segments.clear()
        pending = false
        pendingOperation = null
        deferredSnapshot = null
        invalidateLiveState()
        if (statusError) { status = ""; statusError = false }
        rebuildForm()
        if (!livePending && AdminLiveClient.supported()) requestLive("overview")
    }

    fun acceptResult(success: Boolean, message: String) {
        pending = success
        if (success) pendingSince = System.currentTimeMillis()
        statusError = !success
        status = if (success) tr("saved") else message
    }

    // Save/reload commit focus before starting. Receiving an old reply must not interrupt typing.
    fun currentDraft(): String = document.serialize()

    fun completeOperation(id: UUID) {
        if (pendingOperation == id) { pending = false; pendingOperation = null }
    }

    fun acceptDeferredSnapshot(json: String, success: Boolean, message: String) {
        deferredSnapshot = json
        if (!pending) { status = message.ifBlank { tr("late_snapshot") }; statusError = !success }
        invalidateLiveState()
        if (AdminLiveClient.supported()) requestLive("overview")
    }

    fun acceptDeferredResult(success: Boolean, message: String) {
        if (!pending) { status = message; statusError = !success }
    }

    override fun removed() {
        form.clearFocus(); searchForm.clearFocus()
        AdminClientBridge.detach(this)
        super.removed()
    }

    override fun isPauseScreen() = false

    override fun tick() {
        minecraft?.player?.input?.let {
            it.forwardImpulse = 0f; it.leftImpulse = 0f
            it.up = false; it.down = false; it.left = false; it.right = false
            it.jumping = false; it.shiftKeyDown = false
        }
        if (pending && System.currentTimeMillis() - pendingSince > 45000) {
            pendingOperation?.let { AdminClientBridge.expireOperation(this, it) }
            pendingOperation = null
            pending = false; statusError = true; status = tr("timeout")
        }
        lastDirtyCount = document.dirtyCount
        if (livePending && System.currentTimeMillis() - livePendingSince > 45000) {
            AdminLiveClient.invalidate(this)
            liveOperation = null; livePending = false; liveRefreshRequired = true
            statusError = true; status = Component.translatable("cobbledollarscommandshops.admin.message.live.timeout").string
            liveFormDirty = true
        }
        // A runtime reply must not commit or interrupt a configuration field being edited.
        if (liveFormDirty && !form.hasFocusedComponent && modal == null && dropdownState == null && itemApply == null) {
            liveFormDirty = false; rebuildForm()
        }
    }

    private fun visibleFiles(): List<String> {
        if (tab == 2 && liveOverview != null) return liveOverview!!.getAsJsonArray("shops")
            .map { "shops/${it.asJsonObject.get("id").asString}/shop.json" }
            .filter { it.contains(search, ignoreCase = true) }.sorted()
        return document.files.keySet().filter { path ->
        val matchesTab = when (tab) {
            0, 2 -> path.endsWith("/shop.json")
            1 -> path == "global_bank.json" || path.endsWith("/bank.json")
            else -> path == "audit.json" || path == "feedback.json"
        }
        matchesTab && path.contains(search, ignoreCase = true)
        }.sorted()
    }

    private fun selectedSchema(): ConfigSchema =
        AdminConfigSchema.atPath(selectedFile, document.files.get(selectedFile), segments)

    private fun rebuildForm() {
        form.clearFocus()
        refreshValidation()
        if (tab == 2) { buildStockForm(); return }
        if (!document.files.has(selectedFile)) { form = UiForm(emptyList()); return }
        val node = document.resolve(selectedFile, segments)
        val schema = selectedSchema()
        val rows = mutableListOf<UiComponent>()
        if (segments.isNotEmpty()) rows += AdminActionRow(tr("parent"), listOf("←" to { segments.removeLast(); rebuildForm() }), actionHints = hints("parent"))
        val nodeName = if (node.isJsonObject) listOf("name", "id", "objective", "item", "stack", "tag", "mod")
            .firstNotNullOfOrNull { node.asJsonObject.get(it)?.takeIf(JsonElement::isJsonPrimitive)?.asString } else null
        val sectionHelp = if (AdminConfigSchema.isConditionRule(schema)) "condition_rule" else segments.lastOrNull()?.takeUnless { it.toIntOrNull() != null }
            ?: if (segments.isNotEmpty()) "entry" else when {
                selectedFile.endsWith("/shop.json") -> "shop"
                selectedFile.endsWith("bank.json") -> "bank"
                else -> selectedFile.removeSuffix(".json")
            }
        rows += UiSectionHeaderComponent(nodeName ?: segments.lastOrNull()?.let { if (it.toIntOrNull() != null) "${tr("entry")} ${it.toInt() + 1}" else fieldLabel(it) } ?: selectedFile, 0xFFCC88,
            tooltipProvider = { listOf(Component.literal(help(sectionHelp))) })
        if (tab == 0 && segments.isEmpty() && selectedFile.endsWith("/shop.json")) addVisibility(rows)
        if (validationIssues.any { it.file == selectedFile && it.segments == segments }) {
            val file = selectedFile
            val path = segments.toList()
            rows += AdminValidatedRow(UiSpacerComponent(0)) {
                validationIssues.firstOrNull { it.file == file && it.segments == path }?.let { tr(it.key, *it.args.toTypedArray()) }
            }
        }
        if (selectedFile == "feedback.json" && segments.firstOrNull() in AdminFeedbackSettings.eventKeys
            && (segments.size == 1 || segments == listOf(segments.first(), "sound"))) {
            val event = segments.first()
            val noSound = runCatching { AdminFeedbackSettings.effectiveField(feedbackRoot(), event, "sound").isJsonNull }.getOrDefault(false)
            val hint = tr("help.sound_preview") + if (noSound) "\n" + tr("preview.no_sound") else ""
            rows += AdminActionRow(tr("sound_preview"), listOf(tr("listen") to { previewSound(event) }), hint,
                actionHints = listOf(hint), enabled = !noSound)
        }
        if (node.isJsonArray) {
            if (segments.isEmpty()) rows += AdminActionRow(tr("bank_format"), listOf(tr("bank_upgrade") to {
                document.files.add(selectedFile, JsonObject().apply { add("offers", node) }); rebuildForm()
            }), help("bank_format"), actionHints = hints("bank_upgrade"))
            buildArray(rows, node.asJsonArray, schema)
        }
        else if (node.isJsonObject) buildObject(rows, node.asJsonObject, schema)
        else rows += primitive(tr("value"), schema, { node }, { replacement -> replaceCurrent(replacement); rebuildForm() }, "value", segments.toList())
        form = UiForm(rows, gap = 3)
    }

    private fun refreshValidation() { validationIssues = AdminDraftValidation.validate(document.files) }

    private fun validated(component: UiComponent, path: List<String>): UiComponent {
        val file = selectedFile
        return AdminValidatedRow(component) {
            validationIssues.firstOrNull { it.file == file && it.segments.take(path.size) == path }
                ?.let { tr(it.key, *it.args.toTypedArray()) }
        }
    }

    private fun feedbackRoot() = document.files.getAsJsonObject("feedback.json")

    private fun isInheritedFeedbackField(key: String): Boolean = selectedFile == "feedback.json" && when {
        segments.isEmpty() -> key in AdminFeedbackSettings.eventKeys
        segments.size == 1 -> key in listOf("channels", "sound")
        segments.size == 2 && segments[1] == "sound" -> key in listOf("volume", "pitch")
        else -> false
    }

    private fun feedbackSummary(value: JsonElement): String = when {
        value.isJsonNull -> tr("option.none")
        value.isJsonArray -> value.asJsonArray.joinToString(", ") { optionLabel(it.asString) }.ifEmpty { tr("option.none") }
        value.isJsonObject -> value.asJsonObject.entrySet().joinToString(", ") { (key, entry) -> "${fieldLabel(key)}: ${feedbackSummary(entry)}" }
        else -> value.asString
    }

    private fun addInheritedFeedback(rows: MutableList<UiComponent>, key: String) {
        val event = segments.firstOrNull() ?: key
        val path = if (segments.isEmpty()) emptyList() else segments.drop(1) + key
        val effective = if (path.isEmpty()) AdminFeedbackSettings.effectiveEvent(feedbackRoot(), event)
            else AdminFeedbackSettings.effectiveField(feedbackRoot(), event, path)
        rows += AdminActionRow(fieldLabel(key), listOf(tr("customize") to {
            if (path.isEmpty()) {
                AdminFeedbackSettings.customizeEvent(feedbackRoot(), event)
                segments += key
            } else AdminFeedbackSettings.customizeField(feedbackRoot(), event, path)
            rebuildForm()
        }), help(key) + "\n" + tr("defaults_used"), 0x888888, actionHints = listOf(tr("tooltip.customize")))
        fun summary(label: String, value: JsonElement) {
            val text = feedbackSummary(value)
            rows += UiTextFieldComponent(label, LambdaFieldBinding("inherited.$key", { text }, {}),
                editable = false, valueColor = 0x888888,
                tooltipProvider = { listOf(Component.literal(tr("defaults_used") + "\n" + text)) })
        }
        if (path.isEmpty()) {
            rows += AdminActionRow(tr("defaults_used"), emptyList(), tr("tooltip.customize"), 0x888888)
            val data = effective.asJsonObject
            summary(fieldLabel("channels"), data.get("channels"))
            val sound = data.get("sound")
            summary(fieldLabel("sound"), if (sound.isJsonObject) sound.asJsonObject.get("id") else sound)
            if (sound.isJsonObject) {
                summary(fieldLabel("volume"), sound.asJsonObject.get("volume"))
                summary(fieldLabel("pitch"), sound.asJsonObject.get("pitch"))
            }
        } else summary(tr("defaults_used"), effective)
    }

    private fun previewSound(event: String) {
        form.clearFocus()
        val result = AdminFeedbackPreview.play(feedbackRoot(), event, minecraft ?: return)
        statusError = result.status != AdminFeedbackPreview.PreviewStatus.PLAYED
        status = tr("preview.${result.status.name.lowercase(Locale.ROOT)}", result.soundId ?: "")
    }

    private fun replaceCurrent(value: JsonElement) {
        if (segments.isEmpty()) { document.files.add(selectedFile, value); return }
        val parent = document.resolve(selectedFile, segments.dropLast(1))
        if (parent.isJsonArray) parent.asJsonArray.set(segments.last().toInt(), value)
        else parent.asJsonObject.add(segments.last(), value)
    }

    private fun buildObject(rows: MutableList<UiComponent>, node: JsonObject, schema: ConfigSchema) {
        if (AdminConfigSchema.isConditions(schema)) rows += AdminActionRow(tr("condition_order"), listOf(tr("conditions_upgrade") to {
            runCatching { AdminConfigSchema.orderedConditions(node) }.onSuccess(::replaceCurrent).onFailure {
                statusError = true; status = tr("condition_conversion_failed")
            }
            rebuildForm()
        }), help("conditions_upgrade"), actionHints = hints("conditions_upgrade"))
        if (schema.variants.isNotEmpty()) {
            val active = schema.variants.firstOrNull(node::has) ?: schema.variants.first()
            val selector = if (AdminConfigSchema.isConditionRule(schema)) "condition_type" else "match_type"
            rows += select(tr(selector), active, schema.variants, selector) { selected ->
                if (selected != active) {
                    schema.variants.forEach(node::remove)
                    node.add(selected, schema.fields.getValue(selected).schema.newValue(::defaultText)); rebuildForm()
                }
            }
        }
        val bankAction = if (node.has("offers")) "bank_to_categories" else "bank_to_flat"
        if (schema === AdminConfigSchema.bank) rows += AdminActionRow(tr("bank_format"), listOf(tr(bankAction) to {
            if (node.has("offers")) {
                val category = JsonObject().apply { addProperty("name", tr("default_category")); add("offers", node.remove("offers")) }
                node.add("categories", JsonArray().apply { add(category) })
            } else {
                val categories = node.getAsJsonArray("categories")
                if (categories != null && categories.any { it.asJsonObject.has("conditions") }) {
                    statusError = true; status = tr("category_conditions"); return@to
                }
                val offers = JsonArray()
                categories?.forEach { it.asJsonObject.getAsJsonArray("offers")?.forEach(offers::add) }
                node.remove("categories"); node.add("offers", offers)
            }
            rebuildForm()
        }), help("bank_format"), actionHints = hints(bankAction))
        AdminConfigSchema.visibleFields(schema, node).forEach { (key, spec) ->
            val value = node.get(key)
            val label = fieldLabel(key)
            if (value == null || (value.isJsonNull && key != "sound")) {
                if (isInheritedFeedbackField(key)) { addInheritedFeedback(rows, key); return@forEach }
                rows += validated(AdminActionRow(label, listOf(tr("add") to {
                    node.add(key, spec.schema.newValue(::defaultText))
                    if (key == "restock" && node.get("stock")?.let { runCatching { it.asInt }.getOrDefault(-1) < 0 } != false) node.addProperty("stock", 64)
                    rebuildForm()
                }), help(key) + "\n" + tr("optional_hint", label), 0x888888, actionHints = hints("add")), segments.toList() + key)
            } else {
                if (key == "sound") {
                    val mode = if (value.isJsonNull) "none" else if (value.isJsonObject) "detailed" else "simple"
                    rows += select(tr("sound_format"), mode, listOf("none", "simple", "detailed"), "sound_format") { selected ->
                        if (selected != mode && AdminFeedbackSettings.changeSoundFormat(feedbackRoot(), segments.first(), selected)) rebuildForm()
                    }
                }
                val remove = { node.remove(key); rebuildForm() }
                val resetDefault = isInheritedFeedbackField(key)
                val removeCaption = if (resetDefault) tr("reset_defaults") else "−"
                val removeTooltip = if (resetDefault) tr("tooltip.reset_defaults") else tr("tooltip.remove_option")
                if (value.isJsonObject || value.isJsonArray) {
                    val actions = mutableListOf<Pair<String, () -> Unit>>(tr("edit") to { segments += key; rebuildForm() })
                    if (!spec.required && key !in schema.variants) actions += removeCaption to remove
                    rows += validated(AdminActionRow("$label (${if (value.isJsonArray) value.asJsonArray.size() else value.asJsonObject.size()})", actions, help(key),
                        actionHints = if (actions.size > 1) listOf(tr("tooltip.edit"), removeTooltip) else hints("edit")), segments.toList() + key)
                } else if (value.isJsonNull) {
                    rows += AdminActionRow(label, listOf(removeCaption to remove), help(key), actionHints = listOf(removeTooltip))
                } else {
                    val component = if (key == "type" && schema === AdminConfigSchema.restock) {
                        select(label, value.asString, spec.schema.choices, key) { selected ->
                            if (selected != value.asString) {
                                node.addProperty("type", selected)
                                listOf("amount", "every_seconds", "hour", "minute", "time_zone").forEach(node::remove)
                                if (selected == "interval") { node.addProperty("amount", 1); node.addProperty("every_seconds", 3600) }
                                else { node.addProperty("hour", 0); node.addProperty("minute", 0); node.addProperty("time_zone", "Europe/Paris") }
                                rebuildForm()
                            }
                        }
                    } else primitive(label, spec.schema, { node.get(key) }, { node.add(key, it) }, key)
                    rows += if (!resetDefault && !spec.required && key !in schema.variants) AdminRemovableRow(component, remove, removeTooltip) else component
                    if (resetDefault) rows += AdminActionRow(label, listOf(tr("reset_defaults") to remove),
                        help(key), actionHints = listOf(removeTooltip))
                }
            }
        }
    }

    private fun buildArray(rows: MutableList<UiComponent>, array: JsonArray, schema: ConfigSchema) {
        val itemSchema = schema.element ?: ConfigSchema("\"\"")
        val conditionList = AdminConfigSchema.isConditions(schema)
        array.forEachIndexed { index, value ->
            if (value.isJsonObject || value.isJsonArray) {
                val name = if (conditionList && value.isJsonObject) AdminConfigSchema.conditionTypes.firstOrNull(value.asJsonObject::has)?.let(::fieldLabel)
                    else if (value.isJsonObject) listOf("name", "id", "item", "stack", "tag", "mod", "objective")
                    .firstNotNullOfOrNull { value.asJsonObject.get(it)?.takeIf(JsonElement::isJsonPrimitive)?.asString } else null
                rows += validated(AdminActionRow("${index + 1}. ${name ?: tr("entry")}", listOf(
                    tr("edit") to { segments += index.toString(); rebuildForm() },
                    "↑" to { move(array, index, -1) }, "↓" to { move(array, index, 1) },
                    "+" to { array.add(uniqueOfferIds(value.deepCopy())); rebuildForm() },
                    "−" to { removeArrayEntry(array, index) }
                ), help(if (conditionList) "condition_rule" else "entry"), actionHints = hints("edit", "move_up", "move_down", "duplicate", "delete")), segments.toList() + index.toString())
            } else {
                rows += AdminRemovableRow(primitive("${index + 1}", itemSchema, { array[index] }, { array.set(index, it) }, segments.lastOrNull() ?: "", segments.toList() + index.toString()), {
                    removeArrayEntry(array, index)
                }, tr("tooltip.delete"))
            }
        }
        rows += AdminActionRow(tr(if (conditionList) "condition" else "entry"), listOf(tr("add") to {
            val value = itemSchema.newValue(::defaultText)
            array.add(uniqueOfferIds(value)); rebuildForm()
        }), help(if (conditionList) "condition_rule" else "entry"), actionHints = hints("add"))
    }

    private fun uniqueOfferIds(value: JsonElement) = document.uniqueOfferIds(selectedFile, value)

    private fun removeArrayEntry(array: JsonArray, index: Int) {
        val rulePath = segments.dropLast(1)
        val ruleSchema = if (rulePath.isNotEmpty()) AdminConfigSchema.atPath(selectedFile, document.files.get(selectedFile), rulePath) else null
        if (array.size() == 1 && ruleSchema != null && AdminConfigSchema.isConditionRule(ruleSchema)) {
            val rulesPath = rulePath.dropLast(1)
            val rules = document.resolve(selectedFile, rulesPath).asJsonArray
            val ruleIndex = rulePath.last().toInt()
            confirm(tr("confirm_delete_condition")) {
                rules.remove(ruleIndex)
                segments.clear(); segments.addAll(rulesPath); rebuildForm()
            }
        } else { array.remove(index); rebuildForm() }
    }

    private fun move(array: JsonArray, index: Int, delta: Int) {
        val target = index + delta
        if (target !in 0 until array.size()) return
        val value = array[index]; array.set(index, array[target]); array.set(target, value); rebuildForm()
    }

    private fun primitive(label: String, schema: ConfigSchema, getter: () -> JsonElement, setter: (JsonElement) -> Unit, key: String,
                          path: List<String> = segments.toList() + key): UiComponent {
        val initial = getter()
        if (initial.isJsonPrimitive && initial.asJsonPrimitive.isBoolean) return validated(UiToggleComponent(label,
            LambdaFieldBinding(key, { getter().asBoolean }, { setter(JsonPrimitive(it)); refreshValidation() }), tr("on"), tr("off"),
            tooltipProvider = { listOf(Component.literal(help(key))) }), path)
        val binding = LambdaFieldBinding(key, { getter().asString }, { raw: String ->
            val numeric = schema.newValue().let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            setter(if (numeric) runCatching { JsonPrimitive(BigDecimal(raw)) }.getOrElse { JsonPrimitive(raw) } else JsonPrimitive(raw))
            refreshValidation()
        })
        if (schema.choices.isNotEmpty()) return validated(UiSelectComponent(label, binding, displayText = ::optionLabel, onRequestSelection = { rect ->
            openDropdown(key, schema.choices, setOf(binding.get()), false, rect) { values -> values.firstOrNull()?.let(binding::set) }
        }, tooltipProvider = { listOf(Component.literal(help(key))) }), path)
        if (key == "item") return validated(UiItemFieldComponent(label, binding, displayText = { it }, onRequestPicker = {
            form.clearFocus(); itemSearch.reset(""); itemScroll = 0; itemSearchFocused = true; updateItemResults(); itemApply = binding::set
        }, tooltipProvider = { listOf(Component.literal(help(key))) }), path)
        return validated(UiTextFieldComponent(label, binding, tooltipProvider = { listOf(Component.literal(help(key))) }), path)
    }

    private fun select(label: String, value: String, options: List<String>, helpKey: String, apply: (String) -> Unit): UiComponent =
        UiSelectComponent(label, LambdaFieldBinding(label, { value }, {}), displayText = ::optionLabel, onRequestSelection = { rect ->
            openDropdown(label, options, setOf(value), false, rect) { values -> values.firstOrNull()?.let(apply) }
        }, tooltipProvider = { listOf(Component.literal(help(helpKey))) })

    private fun selectedShopId(): String = selectedFile.takeIf { it.startsWith("shops/") }?.split('/')?.getOrNull(1) ?: ""

    private fun invalidateLiveState() {
        AdminLiveClient.invalidate(this)
        liveOperation = null; livePending = false; liveRefreshRequired = true
        liveOverview = null; liveStock = null; stockValues.clear(); liveFormDirty = true
    }

    private fun liveShop(): JsonObject? = liveOverview?.getAsJsonArray("shops")
        ?.firstOrNull { it.asJsonObject.get("id").asString == selectedShopId() }?.asJsonObject

    private fun requestLive(action: String, shopId: String = "", playerId: String = "", offerId: String = "",
                            stockKey: String = "", amount: Int = 0, enabled: Boolean = true,
                            message: String = "", allPlayers: Boolean = false) {
        if (pending || livePending) return
        val mutation = action !in listOf("overview", "stock")
        if (mutation && liveRefreshRequired) { statusError = true; status = tr("live.refresh_required"); return }
        val id = UUID.randomUUID()
        liveOperation = id; liveAction = action; livePending = true; livePendingSince = System.currentTimeMillis()
        liveFormDirty = true
        if (AdminLiveClient.request(this, action, shopId, playerId, offerId, stockKey, amount, enabled, message, allPlayers, id) == null) {
            liveOperation = null; livePending = false; statusError = true; status = tr("live.unavailable")
        }
    }

    fun acceptLiveResult(operationId: UUID, json: String) {
        if (operationId != liveOperation) return
        val action = liveAction
        livePending = false; liveOperation = null
        val result = AdminLiveClient.result(json)
        status = result.message; statusError = !result.success
        if (result.success) {
            liveRefreshRequired = false
            if (result.state.has("shops")) {
                liveOverview = result.state
                if (tab == 2 && liveShop() == null) {
                    selectedFile = visibleFiles().firstOrNull() ?: ""
                    liveStock = null; stockValues.clear(); stockPage = 0
                }
            }
            if (result.state.has("offers") && result.state.get("shopId").asString == selectedShopId()) {
                liveStock = result.state; stockValues.clear()
                stockTarget = result.state.get("playerId").asString
            }
        } else liveRefreshRequired = true
        liveFormDirty = true
        if (result.success && tab == 2 && (action in listOf("overview", "visibility") || liveStock == null)) refreshStock()
    }

    private fun refreshStock() {
        if (livePending || pending) return
        if (liveOverview == null) { requestLive("overview"); return }
        if (liveShop() == null) { liveStock = null; liveFormDirty = true; return }
        requestLive("stock", selectedShopId(), stockTarget)
    }

    private fun addVisibility(rows: MutableList<UiComponent>) {
        val current = liveShop()
        val text = when {
            liveOverview == null -> tr("live.loading")
            current == null -> tr("live.save_first")
            current.get("enabled").asBoolean -> tr("live.enabled")
            else -> tr("live.disabled")
        }
        val actions = if (current == null || liveRefreshRequired) listOf(tr("live.refresh") to { requestLive("overview") })
            else listOf(tr(if (current.get("enabled").asBoolean) "live.disable" else "live.enable") to {
                // Use the latest received state even if a text edit deferred rebuilding this row.
                val latest = liveShop()
                if (latest != null && !livePending) {
                    val id = selectedShopId()
                    if (!latest.get("enabled").asBoolean) requestLive("visibility", id, enabled = true)
                    else {
                        var reason = ""
                        modal = Modal(tr("live.disable_title", id), UiForm(listOf(UiTextFieldComponent(tr("live.reason"),
                            LambdaFieldBinding("live.reason", { reason }, { reason = it }),
                            tooltipProvider = { listOf(Component.literal(tr("live.help.reason"))) })))) {
                            modal = null; requestLive("visibility", id, enabled = false, message = reason)
                        }
                    }
                }
            }, tr("live.refresh") to { requestLive("overview") })
        rows += AdminActionRow("${tr("live.visibility")}: $text", actions, tr("live.help.visibility"),
            actionHints = if (current == null || liveRefreshRequired) listOf(tr("live.help.refresh"))
                else listOf(tr("live.help.visibility"), tr("live.help.refresh")),
            enabled = !livePending)
        current?.get("message")?.asString?.takeIf { it.isNotBlank() && !current.get("enabled").asBoolean }?.let { reason ->
            rows += UiTextFieldComponent(tr("live.reason"), LambdaFieldBinding("live.reason", { reason }, {}), editable = false,
                tooltipProvider = { listOf(Component.literal(reason)) })
        }
    }

    private fun buildStockForm() {
        val rows = mutableListOf<UiComponent>()
        fun info(label: String, hint: String = "", color: Int = 0xBBBBBB) {
            rows += AdminActionRow(label, emptyList(), hint, color)
        }
        rows += UiSectionHeaderComponent(tr("stocks"), 0xFFCC88,
            tooltipProvider = { listOf(Component.literal(tr("live.immediate"))) })
        info(tr("live.immediate"), tr("live.dirty_notice"))
        if (liveRefreshRequired) info(tr("live.refresh_required"), tr("live.help.refresh"), 0xFF7777)
        if (liveShop() == null) {
            info(tr(if (liveOverview == null && livePending) "live.loading" else "live.save_first"))
            form = UiForm(rows, gap = 3); return
        }
        val players = liveOverview?.getAsJsonArray("players")?.map { it.asJsonObject.get("id").asString }.orEmpty()
        rows += UiSelectComponent(tr("live.player"), LambdaFieldBinding("live.player", { stockTarget }, {}),
            displayText = ::optionLabel, emptyText = minecraft?.player?.name?.string ?: "",
            onRequestSelection = { rect ->
                if (!livePending) openDropdown("live.player", players, setOf(stockTarget), false, rect) { values ->
                    values.firstOrNull()?.let { stockTarget = it; stockAllOnline = false; stockPage = 0; liveStock = null; refreshStock() }
                }
            }, tooltipProvider = { listOf(Component.literal(tr("live.help.player"))) })
        rows += UiTextFieldComponent(tr("live.target"), LambdaFieldBinding("live.target", { stockTarget }, {
            if (stockTarget != it) { stockTarget = it; stockAllOnline = false; liveFormDirty = true }
        }),
            editable = !livePending, tooltipProvider = { listOf(Component.literal(tr("live.help.target"))) })
        rows += AdminActionRow("", listOf(tr("live.show") to {
            form.clearFocus(); stockAllOnline = false; stockPage = 0; liveStock = null; refreshStock()
        }), actionHints = listOf(tr("live.help.show")), enabled = !livePending)
        rows += UiToggleComponent(tr("live.all_online"), LambdaFieldBinding("live.all_online", { stockAllOnline }, {
            if (!livePending) stockAllOnline = it
        }), tr("on"), tr("off"), tooltipProvider = { listOf(Component.literal(tr("live.help.all_online"))) })
        val data = liveStock?.takeIf { it.get("shopId").asString == selectedShopId() }
        if (data == null) {
            info(tr("live.loading")); form = UiForm(rows, gap = 3); return
        }
        info(tr("live.viewing", data.get("playerName").asString), data.get("playerId").asString)
        val targetLoaded = isStockTargetLoaded(data)
        if (!targetLoaded) info(tr("live.target_changed"), tr("live.help.show"), 0xFF7777)
        val offers = data.getAsJsonArray("offers").map { it.asJsonObject }
        if (offers.isEmpty()) info(tr("live.no_stock"))
        else {
            rows += AdminActionRow(tr("stocks"), listOf(tr("live.refill_shop") to {
                mutateStock("restock_shop", tr("live.refill_shop"), data)
            }), tr("live.help.refill_shop"), actionHints = listOf(tr("live.help.refill_shop")),
                enabled = !livePending && !liveRefreshRequired && targetLoaded)
            val pageSize = 20
            val pages = (offers.size + pageSize - 1) / pageSize
            stockPage = stockPage.coerceIn(0, pages - 1)
            if (pages > 1) {
                val actions = mutableListOf<Pair<String, () -> Unit>>()
                if (stockPage > 0) actions += tr("live.previous") to { stockPage--; rebuildForm() }
                if (stockPage < pages - 1) actions += tr("live.next") to { stockPage++; rebuildForm() }
                rows += AdminActionRow(tr("live.page", stockPage + 1, pages), actions,
                    actionHints = actions.map { it.first }, enabled = !livePending)
            }
            offers.drop(stockPage * pageSize).take(pageSize).forEach { offer ->
                val key = offer.get("stockKey").asString
                val max = offer.get("max").asInt
                val current = offer.get("current").asInt
                val source = offer.get("offerId").asString
                val itemId = offer.get("itemId").asString
                val itemName = offer.get("itemNameComponent")?.let { component -> runCatching {
                    Component.Serializer.fromJson(component.toString(), minecraft?.level?.registryAccess()
                        ?: net.minecraft.core.RegistryAccess.EMPTY)?.string
                }.getOrNull() } ?: offer.get("itemName").asString
                val count = offer.get("count")?.asInt ?: 1
                val detail = tr("live.offer_detail", source, itemId, count) + "\n" + tr("live.stock_identity", key)
                rows += UiSectionHeaderComponent("$itemName ×$count — $current / $max", 0xFFCC88,
                    tooltipProvider = { listOf(Component.literal(detail)) })
                val binding = LambdaFieldBinding("live.stock.$key", { stockValues[key] ?: current.toString() }, { stockValues[key] = it })
                rows += AdminValidatedRow(UiTextFieldComponent(tr("live.remaining"), binding,
                    editable = !livePending && !liveRefreshRequired && targetLoaded,
                    tooltipProvider = { listOf(Component.literal(tr("live.help.remaining"))) })) {
                    if (binding.get().toIntOrNull()?.let { it in 0..max } == true) null else tr("live.range", max)
                }
                rows += AdminActionRow(source, listOf(
                    tr("live.apply") to {
                        form.clearFocus()
                        val amount = binding.get().toIntOrNull()
                        if (amount == null || amount !in 0..max) { statusError = true; status = tr("live.range", max) }
                        else mutateStock("set_stock", tr("live.apply"), data, source, key, amount)
                    },
                    tr("live.refill") to { mutateStock("restock_offer", tr("live.refill"), data, source, key) },
                    tr("live.refill_source") to { mutateStock("restock_offer", tr("live.refill_source"), data, source) }
                ), detail, actionHints = listOf(tr("live.help.apply"), tr("live.help.refill"), tr("live.help.refill_source")),
                    enabled = !livePending && !liveRefreshRequired && targetLoaded)
                val next = offer.get("nextRestockAt")?.asLong ?: 0L
                val schedule = if (offer.get("restockType")?.asString.isNullOrBlank()) tr("live.never")
                else if (next <= 0L) tr("live.no_pending_restock") else {
                    val date = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                        .withLocale(Locale.forLanguageTag(minecraft?.languageManager?.selected?.replace('_', '-') ?: "en-US"))
                        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(next))
                    tr("live.next_restock", offer.get("nextRestockAmount").asInt, date)
                }
                info(schedule, tr("live.help.remaining"))
            }
        }
        form = UiForm(rows, gap = 3)
    }

    private fun isStockTargetLoaded(data: JsonObject): Boolean = stockTarget.equals(data.get("playerId").asString, ignoreCase = true)
        || stockTarget.equals(data.get("playerName").asString, ignoreCase = true)

    private fun mutateStock(action: String, label: String, data: JsonObject, offerId: String = "", stockKey: String = "", amount: Int = 0) {
        if (livePending || liveRefreshRequired) return
        form.clearFocus()
        if (!isStockTargetLoaded(data)) { statusError = true; status = tr("live.target_changed"); return }
        val shop = data.get("shopId").asString
        val player = data.get("playerId").asString
        val all = stockAllOnline
        val apply = { requestLive(action, shop, player, offerId, stockKey, amount, allPlayers = all) }
        if (all) confirm(tr("live.confirm_all", label), apply) else apply()
    }

    private fun optionLabel(value: String): String {
        liveOverview?.getAsJsonArray("players")?.firstOrNull { it.asJsonObject.get("id").asString == value }?.asJsonObject?.let {
            return it.get("name").asString + if (it.get("online").asBoolean) " ●" else ""
        }
        if (value in AdminConfigSchema.conditionTypes) return fieldLabel(value)
        val key = "gui.cobbledollarscommandshops.admin.option.$value"
        return if (net.minecraft.client.resources.language.I18n.exists(key)) Component.translatable(key).string else value
    }

    private fun openDropdown(key: String, options: List<String>, selected: Set<String>, multi: Boolean, rect: UiRect, apply: (Set<String>) -> Unit) {
        form.clearFocus()
        dropdownState = UiDropdownOverlay.State(key, options, selected, 0, multi, rect.x, rect.y, 0, 0, width, height)
        dropdownApply = apply
    }

    private fun updateItemResults() {
        val query = itemSearch.currentText.lowercase(Locale.ROOT)
        itemResults = BuiltInRegistries.ITEM.filter { BuiltInRegistries.ITEM.getKey(it).toString().contains(query) || it.description.string.lowercase(Locale.ROOT).contains(query) }
        itemScroll = 0
    }
    private fun pickerState() = UiItemPickerOverlay.State(itemSearch.currentText, itemSearchFocused, itemScroll, itemResults, 0, 0, width, height)

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xCC080810.toInt())
        val frame = UiRect(5, 5, width - 10, height - 10)
        UiChrome.drawPanel(context, frame, AdminUiTheme.boardFrame)
        context.drawString(font, title, 12, 12, 0xFFCC88, false)
        clickActions.clear()
        button(context, UiRect(width - 27, 10, 15, 12), "×", mouseX, mouseY, hint = tr("tooltip.close")) { onClose() }
        val tabs = listOf(tr("shops"), tr("banks"), tr("stocks"), tr("settings"))
        val leftWidth = (width / 3).coerceIn(105, 190)
        var tabX = 12
        tabs.forEachIndexed { index, label ->
            val w = (font.width(label) + 12).coerceAtMost((width - 28) / tabs.size)
            button(context, UiRect(tabX, 29, w, 13), label, mouseX, mouseY, index == tab) {
                tab = index; fileScroll = 0; search = ""
                val first = visibleFiles().firstOrNull()
                if (first != null) selectFile(first) else { segments.clear(); rebuildForm() }
            }; tabX += w + 3
        }
        val actions = footerActions()
        var measuredX = 12
        var footerRows = 1
        actions.forEach { (label, _) ->
            val buttonWidth = font.width(label) + 10
            if (measuredX > 12 && measuredX + buttonWidth > width - 12) { footerRows++; measuredX = 12 }
            measuredX += buttonWidth + 4
        }
        val footerY = height - 36 - footerRows * 17
        val left = UiRect(11, 46, leftWidth, (footerY - 51).coerceAtLeast(25))
        val detail = UiRect(left.right + 5, 46, width - left.right - 16, left.height)
        UiChrome.drawPanel(context, left, AdminUiTheme.adminPanel)
        UiChrome.drawPanel(context, detail, AdminUiTheme.adminPanel)
        searchForm.layout(UiRect(left.x + 2, left.y + 2, left.width - 4, 15))
        searchForm.render(context, font, mouseX, mouseY, delta)
        val files = visibleFiles()
        val visibleCount = ((left.height - 22) / 16).coerceAtLeast(1)
        fileScroll = fileScroll.coerceIn(0, (files.size - visibleCount).coerceAtLeast(0))
        files.drop(fileScroll).take(visibleCount).forEachIndexed { index, path ->
            val name = if (path.startsWith("shops/")) path.split('/')[1] else if (path == "global_bank.json") tr("global_bank") else fieldLabel(path.removeSuffix(".json"))
            button(context, UiRect(left.x + 3, left.y + 20 + index * 16, left.width - 6, 14), name, mouseX, mouseY, path == selectedFile) { selectFile(path) }
        }
        if (fileScroll > 0) context.drawString(font, "↑", left.right - 9, left.y + 17, 0xAAAAAA, false)
        if (fileScroll + visibleCount < files.size) context.drawString(font, "↓", left.right - 9, left.bottom - 10, 0xAAAAAA, false)
        form.layout(UiRect(detail.x + 2, detail.y + 2, detail.width - 4, detail.height - 4))
        form.render(context, font, mouseX, mouseY, delta)
        if (form.hasScrollAbove) context.drawString(font, "↑", detail.right - 9, detail.y + 1, 0xAAAAAA, false)
        if (form.hasScrollBelow) context.drawString(font, "↓", detail.right - 9, detail.bottom - 11, 0xAAAAAA, false)
        var actionX = 12
        var actionY = footerY
        actions.forEach { (label, action, hint) ->
            val w = font.width(label) + 10
            if (actionX > 12 && actionX + w > width - 12) { actionX = 12; actionY += 17 }
            button(context, UiRect(actionX, actionY, w, 13), label, mouseX, mouseY, enabled = !pending, hint = hint, action = action)
            actionX += w + 4
        }
        val statusText = if (pending) tr("saving") else if (livePending) tr("live.loading") else status.ifEmpty { tr(if (tab == 2) "live.immediate" else "hint") }
        font.split(Component.literal(statusText), width - 28).take(2).forEachIndexed { index, line ->
            context.drawString(font, line, 13, height - 32 + index * 10, if (statusError) 0xFF7777 else 0xBBBBBB, false)
        }
        if (mouseY >= height - 33 && status.isNotEmpty()) context.renderTooltip(font, font.split(Component.literal(status), (width - 40).coerceAtLeast(100)), mouseX, mouseY)
        if (dropdownState == null && itemApply == null && modal == null) {
            val actionHint = clickActions.firstOrNull { it.rect.contains(mouseX, mouseY) }?.hint
            val tooltip = actionHint?.let { listOf(Component.literal(it)) } ?: form.tooltip(mouseX, mouseY)
            tooltip?.let { renderHelpTooltip(context, it, mouseX, mouseY) }
        }
        dropdownState?.let { dropdown.render(context, font, mouseX, mouseY, it, ::optionLabel) { 0xAAAAFF } }
        if (itemApply != null) itemPicker.render(context, font, mouseX, mouseY, pickerState(), itemSearch)
        modal?.let { renderModal(context, mouseX, mouseY, delta, it) }
    }

    private fun button(context: GuiGraphics, rect: UiRect, label: String, mouseX: Int, mouseY: Int, active: Boolean = false, enabled: Boolean = true, hint: String? = null, action: () -> Unit) {
        UiChrome.drawButton(context, font, rect, label, rect.contains(mouseX, mouseY), if (!enabled) AdminUiTheme.disabledButton else if (active) AdminUiTheme.successButton else AdminUiTheme.neutralButton)
        if (enabled) clickActions += ScreenAction(rect, action, hint)
    }

    private fun renderHelpTooltip(context: GuiGraphics, lines: List<Component>, mouseX: Int, mouseY: Int) {
        val maxWidth = (width - 28).coerceIn(80, 300)
        context.renderTooltip(font, lines.flatMap { font.split(it, maxWidth) }, mouseX, mouseY)
    }

    private fun selectFile(path: String) {
        form.clearFocus(); selectedFile = path; segments.clear()
        if (tab == 2) { stockPage = 0; stockValues.clear(); liveStock = null }
        rebuildForm()
        if (tab == 2 && !livePending) refreshStock()
    }
    private fun footerActions(): List<FooterAction> {
        if (tab == 2) return listOf(FooterAction(tr("live.refresh"), {
            form.clearFocus()
            if (!livePending) requestLive("overview")
        }, tr("live.help.refresh")))
        val actions = mutableListOf(FooterAction(tr("save", lastDirtyCount), ::save, tr("tooltip.save")), FooterAction(tr("reload"), {
            confirmDiscard {
                val draft = currentDraft()
                pending = true; pendingSince = System.currentTimeMillis()
                val id = AdminClientBridge.beginOperation(this, draft, false)
                pendingOperation = id
                AdminNetwork.requestSnapshot(id)
            }
        }, tr("tooltip.reload")))
        deferredSnapshot?.let { snapshot -> actions += FooterAction(tr("load_server_state"), {
            confirmDiscard { acceptSnapshot(snapshot) }
        }, tr("tooltip.load_server_state")) }
        if (tab == 0) {
            actions += FooterAction(tr("new"), { newShop(false) }, tr("tooltip.new_shop"))
            if (selectedFile.endsWith("/shop.json")) actions += FooterAction(tr("duplicate"), { newShop(true) }, tr("tooltip.duplicate"))
        }
        if (tab == 1) actions += FooterAction(tr("local_bank"), ::newBank, tr("tooltip.new_bank"))
        if (selectedFile.startsWith("shops/")) actions += FooterAction(tr("delete"), { confirm(tr("confirm_delete")) {
            document.deleteFile(selectedFile); segments.clear()
            selectedFile = visibleFiles().firstOrNull() ?: "audit.json"
            if (selectedFile == "audit.json") tab = 3
            rebuildForm()
        } }, tr("tooltip.delete"))
        return actions
    }
    private fun save() {
        form.clearFocus(); searchForm.clearFocus()
        refreshValidation()
        validationIssues.firstOrNull()?.let { issue ->
            selectedFile = issue.file
            tab = when { selectedFile.endsWith("/shop.json") -> 0; selectedFile.endsWith("bank.json") -> 1; else -> 3 }
            segments.clear(); segments.addAll(issue.segments.dropLast(1))
            statusError = true; status = tr("validation.save_blocked", issue.file, tr(issue.key, *issue.args.toTypedArray()))
            rebuildForm()
            return
        }
        val draft = document.serialize()
        pending = true; pendingSince = System.currentTimeMillis(); status = ""; statusError = false
        val id = AdminClientBridge.beginOperation(this, draft, true)
        pendingOperation = id
        AdminNetwork.save(draft, id)
    }
    private fun newShop(duplicate: Boolean) {
        var id = ""
        val source = if (duplicate) selectedFile else null
        modal = Modal(tr(if (duplicate) "duplicate" else "new"), UiForm(listOf(UiTextFieldComponent(tr("shop_id"), LambdaFieldBinding("id", { id }, { id = it }),
            tooltipProvider = { listOf(Component.literal(help("id"))) }))) ) {
            try { val path = document.createShop(id, source, ::defaultText); modal = null; tab = 0; selectFile(path) }
            catch (exception: IllegalArgumentException) { statusError = true; status = tr(exception.message ?: "invalid_id") }
        }
    }
    private fun newBank() {
        val shops = document.files.keySet().filter { it.endsWith("/shop.json") }.map { it.split('/')[1] }.filterNot { document.files.has("shops/$it/bank.json") }.sorted()
        if (shops.isEmpty()) { status = tr("no_missing_bank"); statusError = false; return }
        openDropdown("new_bank", shops, emptySet(), false, UiRect(12, height - 51, 100, 12)) { selected ->
            selected.firstOrNull()?.let {
                val path = "shops/$it/bank.json"
                document.files.add(path, AdminConfigSchema.bank.newValue(::defaultText)); selectFile(path)
            }
        }
    }
    private fun confirm(title: String, action: () -> Unit) { modal = Modal(title, UiForm(emptyList())) { modal = null; action() } }
    private fun confirmDiscard(action: () -> Unit) { form.clearFocus(); if (document.dirtyCount > 0) confirm(tr("discard"), action) else action() }
    override fun onClose() { if (!pending) confirmDiscard { minecraft?.setScreen(null) } }
    private fun modalRect() = UiRect((width - (width - 40).coerceAtMost(360)) / 2, (height - 100) / 2, (width - 40).coerceAtMost(360), 100)
    private fun renderModal(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float, value: Modal) {
        context.pose().pushPose(); context.pose().translate(0f, 0f, 500f)
        context.fill(0, 0, width, height, AdminUiTheme.modalScrimColor)
        val rect = modalRect()
        UiChrome.drawPanel(context, rect, AdminUiTheme.modalOverlay)
        context.drawWordWrap(font, Component.literal(value.title), rect.x + 8, rect.y + 7, rect.width - 16, 0xFFCC88)
        value.form.layout(UiRect(rect.x + 4, rect.y + 40, rect.width - 8, 20)); value.form.render(context, font, mouseX, mouseY, delta)
        UiChrome.drawButton(context, font, UiRect(rect.x + 8, rect.bottom - 23, 90, 13), tr("confirm"), mouseX in rect.x + 8..rect.x + 98 && mouseY in rect.bottom - 23..rect.bottom - 10, AdminUiTheme.successButton)
        UiChrome.drawButton(context, font, UiRect(rect.right - 98, rect.bottom - 23, 90, 13), tr("cancel"), mouseX in rect.right - 98..rect.right - 8 && mouseY in rect.bottom - 23..rect.bottom - 10, AdminUiTheme.neutralButton)
        val modalHint = when {
            UiRect(rect.x + 8, rect.bottom - 23, 90, 13).contains(mouseX, mouseY) -> tr("tooltip.confirm")
            UiRect(rect.right - 98, rect.bottom - 23, 90, 13).contains(mouseX, mouseY) -> tr("tooltip.cancel")
            else -> null
        }
        (modalHint?.let { listOf(Component.literal(it)) } ?: value.form.tooltip(mouseX, mouseY))
            ?.let { renderHelpTooltip(context, it, mouseX, mouseY) }
        context.pose().popPose()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return true
        val x = mouseX.toInt(); val y = mouseY.toInt()
        modal?.let {
            val rect = modalRect()
            if (UiRect(rect.x + 8, rect.bottom - 23, 90, 13).contains(x, y)) { playClick(); it.form.clearFocus(); it.confirm() }
            else if (UiRect(rect.right - 98, rect.bottom - 23, 90, 13).contains(x, y)) { playClick(); it.form.clearFocus(); modal = null }
            else { it.form.mouseClicked(x, y, button); if (it.form.lastClickHandled) playClick() }
            return true
        }
        dropdownState?.let { state ->
            when (val action = dropdown.mouseClicked(font, x, y, state, ::optionLabel)) {
                is UiDropdownOverlay.Action.Toggle -> {
                    playClick()
                    if (state.multiSelect) dropdownState = state.copy(selected = if (action.option in state.selected) state.selected - action.option else state.selected + action.option)
                    else { dropdownState = null; dropdownApply?.invoke(setOf(action.option)) }
                }
                UiDropdownOverlay.Action.Apply -> { playClick(); dropdownState = null; dropdownApply?.invoke(state.selected) }
                UiDropdownOverlay.Action.Dismiss -> { playClick(); dropdownState = null }
            }
            return true
        }
        if (itemApply != null) {
            val state = pickerState()
            if (itemPicker.searchRect(state).contains(x, y)) { playClick(); itemSearchFocused = true; itemSearch.moveCursorToMouse(font, itemPicker.searchRect(state), x) }
            else when (val action = itemPicker.mouseClicked(x, y, state)) {
                is UiItemPickerOverlay.Action.Select -> { playClick(); itemApply?.invoke(action.itemId); itemApply = null }
                UiItemPickerOverlay.Action.Close -> { playClick(); itemApply = null }
                UiItemPickerOverlay.Action.None -> itemSearchFocused = false
            }
            return true
        }
        if (pending) return true
        clickActions.firstOrNull { it.rect.contains(x, y) }?.let { playClick(); form.clearFocus(); searchForm.clearFocus(); it.action(); return true }
        searchForm.mouseClicked(x, y, button)
        val activeForm = form
        val consumed = activeForm.mouseClicked(x, y, button)
        if (activeForm.lastClickHandled || searchForm.lastClickHandled) playClick()
        return consumed || super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (modal != null || pending) return true
        dropdownState?.let { state -> dropdownState = state.copy(scroll = (state.scroll - verticalAmount.toInt()).coerceIn(0, dropdown.maxScroll(font, state, ::optionLabel))); return true }
        if (itemApply != null) { itemScroll = (itemScroll - verticalAmount.toInt()).coerceIn(0, itemPicker.maxScroll(pickerState())); return true }
        if (mouseX < (width / 3).coerceIn(105, 190) + 11) { fileScroll = (fileScroll - verticalAmount.toInt()).coerceAtLeast(0); return true }
        return form.mouseScrolled(mouseX.toInt(), mouseY.toInt(), horizontalAmount, verticalAmount)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        modal?.let { if (it.form.keyPressed(keyCode, scanCode, modifiers)) return true; if (keyCode == 256) modal = null; return true }
        if (dropdownState != null) { if (keyCode == 256) dropdownState = null; return true }
        if (itemApply != null) {
            if (keyCode == 256) { itemApply = null; return true }
            if (itemSearchFocused) { itemSearch.keyPressed(keyCode); updateItemResults() }; return true
        }
        if (pending) return true
        if (form.keyPressed(keyCode, scanCode, modifiers) || searchForm.keyPressed(keyCode, scanCode, modifiers)) return true
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        modal?.let { return it.form.charTyped(codePoint, modifiers) }
        if (itemApply != null) { if (itemSearchFocused) { itemSearch.charTyped(codePoint); updateItemResults() }; return true }
        if (pending || dropdownState != null) return true
        return form.charTyped(codePoint, modifiers) || searchForm.charTyped(codePoint, modifiers)
    }
}
