package fr.cobbledollars.commandshops.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.admin.AdminMessage;
import fr.cobbledollars.commandshops.admin.network.AdminLiveEnvelope;
import fr.cobbledollars.commandshops.client.admin.AdminEditorDocument;
import fr.cobbledollars.commandshops.client.admin.AdminConfigSchema;
import fr.cobbledollars.commandshops.client.admin.CommandShopAdminScreen;
import fr.cobbledollars.commandshops.client.ui.FieldBinding;
import fr.cobbledollars.commandshops.client.ui.UiComponent;
import fr.cobbledollars.commandshops.client.ui.UiRect;
import fr.cobbledollars.commandshops.client.ui.components.UiSingleLineEditorState;
import fr.cobbledollars.commandshops.client.ui.components.UiTextFieldComponent;
import fr.cobbledollars.commandshops.client.ui.form.UiForm;
import kotlin.Pair;
import kotlin.jvm.functions.Function0;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Exercises the real audit forms and responses against a detached draft. */
final class ClientAdminAuditSmokeTest {
    private static final String SNAPSHOT = """
            {"revision":"audit-fixture","files":{"audit.json":{},"feedback.json":{},"global_bank.json":[],
              "shops/demo/shop.json":{"categories":[]}}}
            """;
    private ClientAdminAuditSmokeTest() { }

    static void verify(Minecraft minecraft) throws Exception {
        var previous = minecraft.screen;
        Language previousLanguage = Language.getInstance();
        try {
            for (String locale : List.of("en_us", "fr_fr")) {
                Language.inject(ClientLanguage.loadFrom(minecraft.getResourceManager(),
                        locale.equals("en_us") ? List.of("en_us") : List.of("en_us",locale), false));
                CommandShopAdminScreen screen = new CommandShopAdminScreen(SNAPSHOT);
                minecraft.setScreen(screen);
                var document = (AdminEditorDocument)field(screen,"document");
                document.getFiles().getAsJsonObject("shops/demo/shop.json").addProperty("name","Draft title");
                String draft = screen.currentDraft();
                for (String view : List.of("summary","shops","players","items","offers","journal")) {
                    for (boolean details : view.equals("summary") ? List.of(false) : List.of(false,true)) {
                        seed(screen,view,details);
                        for (int width : List.of(280,640)) verifyForm(minecraft,screen,width);
                        require(draft.equals(screen.currentDraft()),"Audit rendering changed the configuration draft.");
                    }
                }
                seed(screen,"shops",false);
                Object panel = field(screen,"audit");
                invokeAction(screen,tr("details"));
                require(field(panel,"detail") != null,"Details button did not open the selected profile.");
                invoke(screen,"rebuildForm");
                invokeAction(screen,tr("transactions"));
                require("journal".equals(field(panel,"view")) && "demo".equals(field(panel,"shop")),
                        "The profile must open the journal with its recorded shop ID.");

                seed(screen,"journal",true);
                invokeAction(screen,tr("details"));
                require("shops".equals(field(panel,"view")),"A journal event must link back to its recorded shop.");
                seed(screen,"journal",false);
                setField(panel,"data",state("journal",true));
                invoke(screen,"rebuildForm");
                invokeAction(screen,tr("next"));
                require(((Number)field(panel,"page")).intValue()==1 && "cursor-1".equals(field(panel,"cursor")),
                        "The next journal page must retain its cursor.");
                JsonObject second = state("journal",false); second.addProperty("page",1);
                setField(panel,"data",second); setField(panel,"error",""); invoke(screen,"rebuildForm");
                invokeAction(screen,tr("previous"));
                require(((Number)field(panel,"page")).intValue()==0 && "".equals(field(panel,"cursor")),
                        "The previous journal page must restore its original cursor.");
                verifyBufferedResponse(minecraft,screen);
                verifyHistoryEvents(minecraft,screen);
                verifyFinancialEvents(minecraft,screen);
                require(draft.equals(screen.currentDraft()) && document.getDirtyCount()==1,
                        "Browsing audit discarded unsaved configuration edits.");
            }
        } finally {
            Language.inject(previousLanguage);
            minecraft.setScreen(previous);
        }
        CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_ADMIN_AUDIT_RESULT: PASS; six views, history and financial event types/defaults, participant links, exact JSON/null details, operation links, cursor navigation, read-only drafts, buffered/stale responses and English/French help/geometry verified at two widths.");
    }

    static void seed(CommandShopAdminScreen screen,String view,boolean details) throws Exception {
        setField(screen,"tab",3);
        Object panel=field(screen,"audit");
        setField(panel,"view",view); setField(panel,"window","all"); setField(panel,"page",0);
        setField(panel,"cursor",""); setField(panel,"shop",""); setField(panel,"search","");
        setField(panel,"filtersExpanded",false); setField(panel,"pending",false); setField(panel,"queued",false);
        setField(panel,"operation",null); setField(panel,"error","");
        JsonObject state=state(view,false);
        setField(panel,"data",state);
        setField(panel,"detail",details ? state.getAsJsonArray("rows").get(0).getAsJsonObject() : null);
        invoke(screen,"rebuildForm");
    }

    private static void verifyBufferedResponse(Minecraft minecraft,CommandShopAdminScreen screen) throws Exception {
        seed(screen,"shops",false);
        Object panel=field(screen,"audit"); setField(panel,"filtersExpanded",true);
        invoke(screen,"rebuildForm"); verifyForm(minecraft,screen,640);
        UiForm form=(UiForm)field(screen,"form"); UiComponent input=null;
        for (UiComponent row : components(screen)) {
            if (row instanceof UiTextFieldComponent && "audit.shop".equals(((FieldBinding<?>)field(row,"binding")).getKey())) input=row;
        }
        require(input!=null,"Missing recorded-shop filter.");
        Method focus=UiForm.class.getDeclaredMethod("setFocusedComponent",UiComponent.class); focus.setAccessible(true); focus.invoke(form,input);
        require(form.charTyped('x',0),"Audit filter rejected buffered typing.");
        UUID operation=UUID.randomUUID(); setField(panel,"operation",operation); setField(panel,"pending",true);
        setField(panel,"since",System.currentTimeMillis()); setField(panel,"requestedQuery",invoke(panel,"query"));
        JsonObject reply=state("shops",false); reply.addProperty("loadedAt",42L);
        screen.acceptAuditResult(UUID.randomUUID(),new AdminLiveEnvelope(true,reply,AdminMessage.literal("Ignored")).json());
        require(((JsonObject)field(panel,"data")).get("loadedAt").getAsLong()!=42L,"An unrelated audit reply replaced current data.");
        screen.acceptAuditResult(operation,new AdminLiveEnvelope(true,reply,AdminMessage.literal("Loaded")).json());
        screen.tick();
        require(field(screen,"form")==form && "".equals(field(panel,"shop")),"An audit reply rebuilt or committed the focused filter.");
        UiSingleLineEditorState editor=(UiSingleLineEditorState)field(input,"editor");
        require("x".equals(editor.getCurrentText()),"An audit reply discarded buffered filter typing.");
        require(((JsonObject)field(panel,"data")).get("loadedAt").getAsLong()==42L,"The matching audit response was not accepted.");
        form.clearFocus();
        require("x".equals(field(panel,"shop")),"The buffered filter was lost when focus ended.");
        screen.tick();
    }

    private static void verifyHistoryEvents(Minecraft minecraft,CommandShopAdminScreen screen) throws Exception {
        var eventFields=AdminConfigSchema.INSTANCE.getAudit().getFields().get("events").getSchema().getFields();
        for (String key : List.of("stock_changed","config_changed")) {
            require(eventFields.containsKey(key) && JsonParser.parseString(eventFields.get(key).getSchema().getDefaultJson()).getAsBoolean(),
                    "History recording must be configurable and enabled by default: "+key);
        }
        Object panel=field(screen,"audit");
        JsonObject event=JsonParser.parseString("""
                {"id":"history-event","timestamp":"2026-10-16T10:00:00Z","type":"config_changed",
                "operation_id":"60000000-0000-0000-0000-000000000001","actor_name":"Administrator",
                "actor_uuid":"60000000-0000-0000-0000-000000000002","source":"admin","phase":"prepared",
                "file":"audit.json","path":"/events/config_changed","change":"changed",
                "before_present":true,"after_present":true,"before":true,"after":false,
                "future_object":{"nested":[1,null,"test"]},"future_array":[false,{"another":"value"}]}
                """).getAsJsonObject();
        for (String json : List.of("null","{\"enabled\":false,\"nested\":[1,null]}","[true,{\"price\":12.345}]","\"null\"")) {
            seed(screen,"journal",false);
            event.add("before",JsonParser.parseString("null")); event.add("after",JsonParser.parseString(json));
            JsonObject state=state("journal",false); state.getAsJsonArray("rows").remove(0); state.getAsJsonArray("rows").add(event);
            setField(panel,"data",state); setField(panel,"detail",event.deepCopy()); invoke(screen,"rebuildForm");
            for (int width : List.of(280,640)) verifyForm(minecraft,screen,width);
            require(tr("json_null").equals(readOnlyValue(screen,tr("field.before"))),"JSON null must remain visible in config history.");
            String expected=json.equals("null") ? tr("json_null") : JsonParser.parseString(json).toString();
            require(expected.equals(readOnlyValue(screen,tr("field.after"))),"Config history lost the exact JSON type/value: "+json);
        }
        setField(panel,"detail",null); invoke(screen,"rebuildForm");
        boolean preparedVisible=false;
        for (UiComponent row : components(screen)) if (row.getClass().getSimpleName().equals("AdminActionRow")) {
            if (((String)field(row,"label")).contains(tr("option.prepared"))) preparedVisible=true;
        }
        require(preparedVisible,"Prepared configuration changes must show their status in the journal list.");
        setField(panel,"detail",event.deepCopy()); setField(panel,"shop","other-shop"); setField(panel,"event","config_changed");
        invoke(screen,"rebuildForm"); invokeAction(screen,tr("view_operation"));
        require("journal".equals(field(panel,"view")) && event.get("operation_id").getAsString().equals(field(panel,"search"))
                        && "".equals(field(panel,"shop")) && "all".equals(field(panel,"event")),
                "An operation link must show the entire operation across shops and event types.");

        for (String phase : List.of("applied","rolled_back","rollback_failed","failed","activation_failed")) {
            seed(screen,"journal",false); JsonObject terminal=event.deepCopy(); terminal.addProperty("phase",phase);
            terminal.remove("before"); terminal.remove("after"); terminal.add("files",JsonParser.parseString("[\"audit.json\",\"shops/demo/shop.json\"]"));
            terminal.add("shop_ids",JsonParser.parseString("[\"demo\"]")); terminal.addProperty("change_count",2);
            setField(panel,"detail",terminal); invoke(screen,"rebuildForm"); verifyForm(minecraft,screen,640);
            require(tr("option."+phase).equals(readOnlyValue(screen,tr("field.phase"))),"Missing translated configuration outcome: "+phase);
        }
        seed(screen,"journal",false);
        JsonObject stock=JsonParser.parseString("""
                {"id":"stock-event","timestamp":"2026-10-16T10:00:00Z","type":"stock_changed",
                "operation_id":"60000000-0000-0000-0000-000000000003","actor_name":"Administrator",
                "shop_id":"demo","offer_id":"stone","stock_key":"stone/minecraft:stone/exact","item_id":"minecraft:stone",
                "player_name":"Target","player_uuid":"60000000-0000-0000-0000-000000000004",
                "action":"set_stock","stock_before":2,"stock_after":7}
                """).getAsJsonObject();
        setField(panel,"detail",stock); invoke(screen,"rebuildForm");
        for (int width : List.of(280,640)) verifyForm(minecraft,screen,width);
        require("2".equals(readOnlyValue(screen,tr("field.stock_before"))) && "7".equals(readOnlyValue(screen,tr("field.stock_after"))),
                "Manual stock history must show exact before/after quantities.");
    }

    private static String readOnlyValue(CommandShopAdminScreen screen,String label) throws Exception {
        for (UiComponent row : components(screen)) if (row instanceof UiTextFieldComponent) {
            FieldBinding<?> binding=(FieldBinding<?>)field(row,"binding");
            if (binding.getKey().equals(label)) return binding.get().toString();
        }
        throw new AssertionError("Missing recorded audit value: "+label);
    }

    @SuppressWarnings("unchecked")
    private static void verifyFinancialEvents(Minecraft minecraft,CommandShopAdminScreen screen) throws Exception {
        var fields=AdminConfigSchema.INSTANCE.getAudit().getFields().get("events").getSchema().getFields();
        for (String type : List.of("player_transfer","balance_adjusted","battle_reward","native_buy_success","native_sell_success")) {
            boolean expected=type.equals("player_transfer") || type.equals("balance_adjusted");
            require(fields.containsKey(type) && JsonParser.parseString(fields.get(type).getSchema().getDefaultJson()).getAsBoolean()==expected,
                    "Unexpected financial audit default: "+type);
            require(net.minecraft.client.resources.language.I18n.exists("gui.cobbledollarscommandshops.admin.audit.option."+type),
                    "Missing translated financial event: "+type);
            seed(screen,"journal",false);
            JsonObject event=JsonParser.parseString("""
                    {"id":"money-event","timestamp":"2026-10-27T10:00:00Z","type":"player_transfer",
                    "operation_id":"61000000-0000-0000-0000-000000000001",
                    "player_uuid":"61000000-0000-0000-0000-000000000002","player_name":"Sender",
                    "recipient_uuid":"61000000-0000-0000-0000-000000000003","recipient_name":"Recipient",
                    "balance_before":"9007199254740993001","balance_after":"1",
                    "recipient_balance_before":"0","recipient_balance_after":"9007199254740993000",
                    "currency_amount":"9007199254740993000","currency_delta":"-9007199254740993000",
                    "actor_name":"Server","action":"give","requested_amount":"9007199254740993000"}
                    """).getAsJsonObject();
            event.addProperty("type",type);
            if (!type.equals("player_transfer")) {
                for (String key : List.of("recipient_uuid","recipient_name","recipient_balance_before","recipient_balance_after")) event.remove(key);
            }
            if (type.startsWith("native_")) event.add("item_lines",JsonParser.parseString("[{\"item\":\"minecraft:apple\",\"count\":3}]"));
            Object panel=field(screen,"audit");
            setField(panel,"detail",event); invoke(screen,"rebuildForm");
            for (int width : List.of(280,640)) verifyForm(minecraft,screen,width);
            require(readOnlyValue(screen,tr("field.balance_before")).contains("9"),"Financial balance was not rendered.");
            if (type.startsWith("native_")) for (UiComponent row : components(screen)) {
                if (row.getClass().getSimpleName().equals("AdminActionRow")) require(
                        !((String)field(row,"label")).endsWith("minecraft:apple"),
                        "Native commerce must not link to command-shop-only item rankings.");
            }
            if (type.equals("player_transfer")) {
                boolean linked=false;
                for (UiComponent row : components(screen)) if (row.getClass().getSimpleName().equals("AdminActionRow")
                        && ((String)field(row,"label")).endsWith(event.get("recipient_uuid").getAsString())) {
                    ((List<Pair<String,Function0<?>>>)field(row,"actions")).getFirst().getSecond().invoke();
                    linked=true; break;
                }
                require(linked && "players".equals(field(panel,"view"))
                                && event.get("recipient_uuid").getAsString().equals(field(panel,"player")),
                        "Transfer details must link the recipient's player profile.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyForm(Minecraft minecraft,CommandShopAdminScreen screen,int width) throws Exception {
        UiForm form=(UiForm)field(screen,"form");
        form.layout(new UiRect(10,10,width,1200));
        var graphics=new GuiGraphics(minecraft,minecraft.renderBuffers().bufferSource());
        form.render(graphics,minecraft.font,-1,-1,0F); graphics.flush();
        for (UiComponent row : components(screen)) {
            String name=row.getClass().getSimpleName();
            if (name.equals("AdminActionRow")) {
                ClientAdminUiSmokeTest.verifyInlineActionRow(row);
                require(!((String)field(row,"label")).contains("gui.cobbledollars"),"Untranslated audit row.");
                if (((String)field(row,"hint")).isBlank()) continue;
            }
            UiRect help=(UiRect)field(row,name.equals("AdminActionRow") ? "helpRect" : "tooltipBounds");
            require(help!=null,"Audit control has no help marker: "+name);
            List<Component> tooltip=row.tooltip(help.getX()+2,help.getY()+2);
            require(tooltip!=null && tooltip.getFirst().getString().length()>20
                    && !tooltip.getFirst().getString().contains("gui.cobbledollars"),"Missing translated audit explanation: "+name);
        }
        List<?> footer=(List<?>)invoke(screen,"footerActions");
        require(footer.size()==2,"Audit must expose refresh/reset without configuration mutations.");
    }

    private static JsonObject state(String view,boolean next) {
        JsonObject result=JsonParser.parseString("""
                {"view":"summary","window":"all","loadedAt":1800000000000,"invalidLines":0,"unreadableFiles":0,
                "pageSize":25,"page":0,"total":1,"hasNext":false,"nextCursor":"","auditEnabled":true,
                "enabledEvents":["buy_success","sell_success","visibility_changed","stock_changed","config_changed"],
                "summary":{"buySuccessCount":5,"buyFailureCount":0,"sellSuccessCount":2,"sellFailureCount":0,
                "visibilityChangeCount":1,"stockChangeCount":1,"configChangeCount":1,"spent":"9007199254740993000","earned":"100","netFlow":"9007199254740992900"},"rows":[]}
                """).getAsJsonObject();
        result.addProperty("view",view); result.addProperty("hasNext",next); result.addProperty("nextCursor",next ? "cursor-1" : "");
        for (String key : List.of("transferCount","balanceAdjustmentCount","battleRewardCount","nativeBuySuccessCount","nativeSellSuccessCount"))
            result.getAsJsonObject("summary").addProperty(key,1);
        for (String key : List.of("transferred","adminAdded","adminRemoved","battleRewards","nativeSpent","nativeEarned"))
            result.getAsJsonObject("summary").addProperty(key,"9007199254740993000");
        String row=switch(view) {
            case "shops" -> "{\"shopId\":\"demo\",\"spent\":\"250\",\"earned\":\"0\",\"buySuccessCount\":5,\"sellSuccessCount\":0,\"buyFailureCount\":0,\"sellFailureCount\":0,\"visibilityChangeCount\":1,\"id\":\"demo\",\"name\":\"demo\",\"volume\":\"250\"}";
            case "players" -> "{\"playerUuid\":\"50000000-0000-0000-0000-000000000001\",\"playerName\":\"12345\",\"spent\":\"250\",\"earned\":\"0\",\"boughtItemCount\":5,\"id\":\"50000000-0000-0000-0000-000000000001\",\"name\":\"12345\",\"volume\":\"250\"}";
            case "items" -> "{\"itemId\":\"minecraft:stone\",\"itemName\":\"Stone\",\"boughtCount\":5,\"soldCount\":0,\"spent\":\"250\",\"earned\":\"0\",\"id\":\"minecraft:stone\",\"name\":\"Stone\",\"volume\":\"250\"}";
            case "offers" -> "{\"shopId\":\"demo\",\"offerId\":\"stone\",\"spent\":\"250\",\"bundleCount\":5,\"itemCount\":25,\"buySuccessCount\":5,\"buyFailureCount\":0,\"id\":\"demo/stone\",\"name\":\"stone\",\"volume\":\"250\"}";
            default -> "{\"timestamp\":\"2026-10-16T10:00:00Z\",\"type\":\"buy_success\",\"shop_id\":\"demo\",\"offer_id\":\"stone\",\"player_name\":\"12345\",\"player_uuid\":\"50000000-0000-0000-0000-000000000001\",\"currency_amount\":\"250\",\"currency_flow\":\"sink\",\"bundle_count\":5,\"item_count_total\":25,\"stock_before\":10,\"stock_after\":5,\"item_lines\":[{\"item\":\"minecraft:stone\",\"count\":25,\"line_total\":\"250\",\"components_hash\":\"12345\"}],\"bonus_lines\":[{\"item\":\"minecraft:diamond\",\"count\":1}],\"id\":\"event-1\"}";
        };
        result.getAsJsonArray("rows").add(JsonParser.parseString(row));
        return result;
    }
    @SuppressWarnings("unchecked")
    private static void invokeAction(CommandShopAdminScreen screen,String label) throws Exception {
        for (UiComponent row : components(screen)) if (row.getClass().getSimpleName().equals("AdminActionRow")) {
            for (Pair<String,Function0<?>> action : (List<Pair<String,Function0<?>>>)field(row,"actions")) {
                if (action.getFirst().equals(label)) { action.getSecond().invoke(); return; }
            }
        }
        throw new AssertionError("Missing audit action: "+label);
    }
    @SuppressWarnings("unchecked")
    private static List<UiComponent> components(CommandShopAdminScreen screen) throws Exception { return (List<UiComponent>)field(field(screen,"form"),"components"); }
    private static Object field(Object target,String name) throws Exception {
        for (Class<?> type=target.getClass(); type!=null; type=type.getSuperclass()) {
            try { Field f=type.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
            catch (NoSuchFieldException missing) { /* Reused labeled controls inherit their help bounds. */ }
        }
        throw new NoSuchFieldException(name);
    }
    private static void setField(Object target,String name,Object value) throws Exception { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value); }
    private static Object invoke(Object target,String name) throws Exception { Method m=target.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(target); }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    private static String tr(String key) { return Component.translatable("gui.cobbledollarscommandshops.admin.audit."+key).getString(); }
}
