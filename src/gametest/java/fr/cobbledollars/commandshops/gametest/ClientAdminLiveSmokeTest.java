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
import fr.cobbledollars.commandshops.client.admin.CommandShopAdminScreen;
import fr.cobbledollars.commandshops.client.ui.FieldBinding;
import fr.cobbledollars.commandshops.client.ui.UiComponent;
import fr.cobbledollars.commandshops.client.ui.UiRect;
import fr.cobbledollars.commandshops.client.ui.components.UiTextFieldComponent;
import fr.cobbledollars.commandshops.client.ui.components.UiSingleLineEditorState;
import fr.cobbledollars.commandshops.client.ui.form.UiForm;
import kotlin.Pair;
import kotlin.jvm.functions.Function0;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Detached live-state responses exercise the actual screen without issuing administrative mutations. */
final class ClientAdminLiveSmokeTest {
    static final String PLAYER_ID = "50000000-0000-0000-0000-000000000001";
    private static final String SHOP = "shops/live_demo/shop.json";
    private static final String SNAPSHOT = """
            {"revision":"admin-live-ui-fixture","files":{
              "audit.json":{},"feedback.json":{},"global_bank.json":[],
              "shops/live_demo/shop.json":{"categories":[{"name":"Test","offers":[{
                "id":"logs","match":{"include":[{"tag":"minecraft:logs"}]},"price":25,"stock":8
              }]}]}
            }}
            """;

    private ClientAdminLiveSmokeTest() {
    }

    static void verify(Minecraft minecraft) throws Exception {
        Screen previous = minecraft.screen;
        try {
            CommandShopAdminScreen screen = new CommandShopAdminScreen(SNAPSHOT);
            minecraft.setScreen(screen);
            AdminEditorDocument document = (AdminEditorDocument) field(screen, "document");
            document.resolve(SHOP, List.of("categories", "0", "offers", "0"))
                    .getAsJsonObject().addProperty("price", 26);
            String draft = screen.currentDraft();
            require(document.getDirtyCount() == 1, "The live-state fixture needs an unsaved configuration edit.");

            verifyBufferedReply(minecraft, screen, document);
            draft = screen.currentDraft();
            UUID overviewId = UUID.randomUUID();
            setField(screen, "liveOperation", overviewId);
            screen.acceptLiveResult(overviewId, envelope(overview("live_demo")));
            require(overview("live_demo").equals(field(screen, "liveOverview")), "The matching overview did not reach the shop controls.");
            require(draft.equals(screen.currentDraft()), "A visibility overview replaced the unsaved configuration.");
            screen.acceptLiveResult(UUID.randomUUID(), envelope(overview("obsolete_shop")));
            require(overview("live_demo").equals(field(screen, "liveOverview")), "An unrelated live response replaced the current overview.");

            setField(screen, "tab", 2);
            setField(screen, "stockTarget", PLAYER_ID);
            UUID stockId = UUID.randomUUID();
            setField(screen, "liveOperation", stockId);
            screen.acceptLiveResult(stockId, envelope(stock("live_demo")));
            require(stock("live_demo").equals(field(screen, "liveStock")), "The matching stock response did not reach the Stocks tab.");
            invoke(screen, "rebuildForm");
            renderForm(minecraft, screen);
            verifyStockControls(minecraft, screen);
            require(draft.equals(screen.currentDraft()) && document.getDirtyCount() == 1,
                    "Loading player stock changed the configuration draft or its dirty state.");

            setField(screen, "tab", 0);
            invoke(screen, "rebuildForm");
            renderForm(minecraft, screen);
            verifyVisibility(screen);
            require(draft.equals(screen.currentDraft()), "Returning to Shops discarded the draft.");

            CommandShopAdminScreen settings = new CommandShopAdminScreen("{\"revision\":\"settings\",\"files\":{\"audit.json\":{}}}");
            require(((Number) field(settings, "tab")).intValue() == 4, "Settings must be the fifth tab, after Audit.");
            verifyLayoutVariants(minecraft);
        } finally {
            minecraft.setScreen(previous);
        }
        CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_ADMIN_LIVE_RESULT: PASS; runtime visibility, separate variant stocks, stock range guards, bulk confirmation, correlated responses, draft preservation and inline action geometry in English/French at two widths verified.");
    }

    static void seed(CommandShopAdminScreen screen, String shopId) throws Exception {
        setField(screen, "liveOverview", overview(shopId));
        setField(screen, "liveStock", stock(shopId));
        setField(screen, "stockTarget", PLAYER_ID);
    }

    @SuppressWarnings("unchecked")
    private static void verifyBufferedReply(Minecraft minecraft, CommandShopAdminScreen screen, AdminEditorDocument document) throws Exception {
        List<String> path = (List<String>) field(screen, "segments");
        path.addAll(List.of("categories", "0", "offers", "0"));
        invoke(screen, "rebuildForm");
        renderForm(minecraft, screen);
        UiForm form = (UiForm) field(screen, "form");
        UiComponent priceRow = null;
        for (UiComponent row : components(screen)) {
            UiComponent control = unwrap(row);
            if (control instanceof UiTextFieldComponent && "price".equals(((FieldBinding<?>) field(control, "binding")).getKey())) priceRow = row;
        }
        require(priceRow != null, "The live response fixture needs an editable price field.");
        Method focus = UiForm.class.getDeclaredMethod("setFocusedComponent", UiComponent.class);
        focus.setAccessible(true);
        focus.invoke(form, priceRow);
        require(form.charTyped('0', 0), "The focused price field did not accept buffered typing.");
        String draft = screen.currentDraft();
        UUID id = UUID.randomUUID();
        setField(screen, "liveOperation", id);
        screen.acceptLiveResult(id, envelope(overview("live_demo")));
        screen.tick();
        require(field(screen, "form") == form && draft.equals(screen.currentDraft()), "A live response rebuilt or committed the focused configuration field.");
        UiSingleLineEditorState editor = (UiSingleLineEditorState) field(unwrap(priceRow), "editor");
        require("260".equals(editor.getCurrentText()), "A live response discarded buffered configuration typing.");
        form.clearFocus();
        require(document.resolve(SHOP, List.of("categories", "0", "offers", "0")).getAsJsonObject().get("price").getAsInt() == 260,
                "The preserved configuration typing was lost after leaving the field.");
        path.clear();
        invoke(screen, "rebuildForm");
    }

    @SuppressWarnings("unchecked")
    private static void verifyStockControls(Minecraft minecraft, CommandShopAdminScreen screen) throws Exception {
        UiComponent oakRow = null;
        UiComponent birchRow = null;
        FieldBinding<String> target = null;
        boolean customNameFound = false;
        boolean noPendingRestockFound = false;
        for (UiComponent row : components(screen)) {
            UiComponent control = unwrap(row);
            if (control.getClass().getSimpleName().equals("UiSectionHeaderComponent")) {
                customNameFound |= ((String) field(control, "label")).contains("Custom oak variant");
            }
            if (control.getClass().getSimpleName().equals("AdminActionRow")) {
                noPendingRestockFound |= tr("live.no_pending_restock").equals(field(control, "label"));
            }
            if (!(control instanceof UiTextFieldComponent)) continue;
            String key = ((FieldBinding<?>) field(control, "binding")).getKey();
            if (key.equals("live.stock.logs#oak")) oakRow = row;
            if (key.equals("live.stock.logs#birch")) birchRow = row;
            if (key.equals("live.target")) target = (FieldBinding<String>) field(control, "binding");
        }
        require(oakRow != null && birchRow != null && oakRow != birchRow, "Concrete variants of the same offer need separate stock fields.");
        require(customNameFound, "A concrete item's custom display name must survive the live response.");
        require(noPendingRestockFound, "A configured restock with no pending refill must have an accurate status.");
        require(target != null, "The player target field is missing.");
        FieldBinding<String> oak = (FieldBinding<String>) field(unwrap(oakRow), "binding");
        FieldBinding<String> birch = (FieldBinding<String>) field(unwrap(birchRow), "binding");
        require("2".equals(oak.get()) && "6".equals(birch.get()), "Variant stock fields lost their individual current values.");
        UiComponent oakActions = null;
        int offerRows = 0;
        for (UiComponent row : components(screen)) {
            UiComponent control = unwrap(row);
            if (!control.getClass().getSimpleName().equals("AdminActionRow") || !"logs".equals(field(control, "label"))) continue;
            List<?> buttons = (List<?>) field(control, "buttons");
            require(buttons.size() == 3, "Each concrete stock needs apply, refill item and refill source actions.");
            ClientAdminUiSmokeTest.verifyInlineActionRow(control);
            if (oakActions == null) oakActions = control;
            offerRows++;
        }
        require(offerRows == 2, "The two concrete variants must both have stock actions.");

        for (String invalid : List.of("-1", "9", "invalid")) {
            oak.set(invalid);
            renderForm(minecraft, screen);
            String error = (String) ((Function0<?>) field(oakRow, "error")).invoke();
            require(error != null && !error.isBlank() && !error.contains("gui.cobbledollarscommandshops"), "Invalid stock requires a translated inline range error.");
            invokeAction(oakActions, tr("live.apply"));
            require(Boolean.TRUE.equals(field(screen, "statusError")) && error.equals(field(screen, "status")), "Applying invalid stock must explain its valid range.");
            require(field(screen, "liveOperation") == null && field(screen, "modal") == null, "Invalid stock must not start a request or bulk confirmation.");
            require("6".equals(birch.get()), "Editing one variant changed another variant's stock.");
        }
        oak.set("8");
        String otherPlayer = "50000000-0000-0000-0000-000000000002";
        setField(screen, "stockTarget", otherPlayer);
        setField(screen, "statusError", false);
        invokeAction(oakActions, tr("live.apply"));
        require(field(screen, "liveOperation") == null && field(screen, "modal") == null && Boolean.TRUE.equals(field(screen, "statusError")),
                "A stale stock action must reject a player target that differs from the loaded stock.");
        setField(screen, "stockTarget", PLAYER_ID);

        setField(screen, "stockAllOnline", true);
        setField(screen, "liveFormDirty", false);
        target.set(otherPlayer);
        require(Boolean.FALSE.equals(field(screen, "stockAllOnline")), "Typing a new target must reset the all-online scope.");
        require(Boolean.TRUE.equals(field(screen, "liveFormDirty")), "Typing a new target must schedule an updated stock form.");
        target.set(PLAYER_ID);
        setField(screen, "stockAllOnline", true);
        invokeAction(oakActions, tr("live.apply"));
        require(field(screen, "modal") != null && field(screen, "liveOperation") == null, "Bulk stock changes must be confirmed before any server request.");
        screen.keyPressed(256, 0, 0);
        require(field(screen, "modal") == null, "Cancelling a bulk action must close its confirmation.");
        setField(screen, "stockAllOnline", false);
    }

    private static void verifyVisibility(CommandShopAdminScreen screen) throws Exception {
        UiComponent visibility = null;
        for (UiComponent row : components(screen)) {
            UiComponent control = unwrap(row);
            if (control.getClass().getSimpleName().equals("AdminActionRow")
                    && ((String) field(control, "label")).startsWith(tr("live.visibility"))) visibility = control;
        }
        require(visibility != null, "The shop root is missing its live visibility control.");
        ClientAdminUiSmokeTest.verifyInlineActionRow(visibility);
        invokeAction(visibility, tr("live.disable"));
        Object modal = field(screen, "modal");
        require(modal != null && field(screen, "liveOperation") == null, "Disabling a shop must first allow entry of a custom denial reason.");
        UiForm form = (UiForm) field(modal, "form");
        @SuppressWarnings("unchecked") List<UiComponent> rows = (List<UiComponent>) field(form, "components");
        require(rows.stream().anyMatch(row -> row instanceof UiTextFieldComponent), "The shop disable dialog needs a denial-message field.");
        screen.keyPressed(256, 0, 0);
        require(field(screen, "modal") == null, "Cancelling shop disabling must preserve the current visibility.");
    }

    private static void verifyLayoutVariants(Minecraft minecraft) throws Exception {
        Language previousLanguage = Language.getInstance();
        try {
            for (String locale : List.of("en_us", "fr_fr")) {
                Language.inject(ClientLanguage.loadFrom(minecraft.getResourceManager(),
                        locale.equals("en_us") ? List.of("en_us") : List.of("en_us", locale), false));
                CommandShopAdminScreen screen = new CommandShopAdminScreen(SNAPSHOT);
                seed(screen, "live_demo");
                minecraft.setScreen(screen);
                AdminEditorDocument document = (AdminEditorDocument) field(screen, "document");
                document.getFiles().getAsJsonObject(SHOP).add("conditions", JsonParser.parseString("{\"player_tags_all\":[\"member\"]}"));
                String before = screen.currentDraft();

                verifyLayoutView(minecraft, screen, SHOP, 0, List.of(), tr("live.visibility"));
                verifyLayoutView(minecraft, screen, SHOP, 2, List.of(), tr("stocks"));
                verifyLayoutView(minecraft, screen, SHOP, 0, List.of("conditions"), tr("condition_order"));
                verifyLayoutView(minecraft, screen, SHOP, 0, List.of("categories", "0", "offers"), "1.");
                verifyLayoutView(minecraft, screen, "global_bank.json", 1, List.of(), tr("bank_format"));
                require(before.equals(screen.currentDraft()), "Checking action geometry must not change configuration in " + locale);

                document.getFiles().add("global_bank.json", JsonParser.parseString("{\"offers\":[]}"));
                verifyLayoutView(minecraft, screen, "global_bank.json", 1, List.of(), tr("bank_format"));
                document.getFiles().add("global_bank.json", JsonParser.parseString("{\"categories\":[]}"));
                verifyLayoutView(minecraft, screen, "global_bank.json", 1, List.of(), tr("bank_format"));
            }
        } finally {
            Language.inject(previousLanguage);
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyLayoutView(Minecraft minecraft, CommandShopAdminScreen screen, String file,
                                         int tab, List<String> segments, String expectedLabel) throws Exception {
        setField(screen, "selectedFile", file);
        setField(screen, "tab", tab);
        List<String> path = (List<String>) field(screen, "segments");
        path.clear();
        path.addAll(segments);
        invoke(screen, "rebuildForm");
        for (int width : List.of(280, 640)) {
            renderForm(minecraft, screen, width);
            boolean expectedFound = false;
            for (UiComponent row : components(screen)) {
                UiComponent control = unwrap(row);
                if (!control.getClass().getSimpleName().equals("AdminActionRow")) continue;
                ClientAdminUiSmokeTest.verifyInlineActionRow(control);
                expectedFound |= ((String) field(control, "label")).startsWith(expectedLabel)
                        && !((List<?>) field(control, "buttons")).isEmpty();
            }
            require(expectedFound, "Missing inline action fixture " + expectedLabel + " at width " + width);
        }
    }

    @SuppressWarnings("unchecked")
    private static void invokeAction(UiComponent row, String label) throws Exception {
        for (Pair<String, Function0<?>> action : (List<Pair<String, Function0<?>>>) field(row, "actions")) {
            if (label.equals(action.getFirst())) { action.getSecond().invoke(); return; }
        }
        throw new AssertionError("Missing action: " + label);
    }

    private static UiComponent unwrap(UiComponent component) throws Exception {
        while (component.getClass().getSimpleName().equals("AdminRemovableRow") || component.getClass().getSimpleName().equals("AdminValidatedRow")) {
            component = (UiComponent) field(component, "content");
        }
        return component;
    }

    private static String envelope(JsonObject state) {
        return new AdminLiveEnvelope(true, state, AdminMessage.literal("Live state loaded")).json();
    }

    private static JsonObject overview(String shopId) {
        JsonObject result = JsonParser.parseString("""
                {"shops":[],"players":[{"id":"50000000-0000-0000-0000-000000000001","name":"Admin demo","online":true}]}
                """).getAsJsonObject();
        JsonObject shop = new JsonObject();
        shop.addProperty("id", shopId);
        shop.addProperty("enabled", true);
        shop.addProperty("message", "");
        shop.addProperty("changedBy", "");
        shop.addProperty("changedAt", 0L);
        result.getAsJsonArray("shops").add(shop);
        return result;
    }

    private static JsonObject stock(String shopId) {
        JsonObject result = JsonParser.parseString("""
                {
                  "playerId":"50000000-0000-0000-0000-000000000001","playerName":"Admin demo",
                  "allPlayers":false,"affectedPlayers":0,
                  "offers":[
                    {"offerId":"logs","stockKey":"logs#oak","itemId":"minecraft:oak_log","itemName":"Oak Log","count":16,"current":2,"max":8,"restockType":"interval","restockAmount":2,"restockEverySeconds":3600,"nextRestockAt":0,"nextRestockAmount":0},
                    {"offerId":"logs","stockKey":"logs#birch","itemId":"minecraft:birch_log","itemName":"Birch Log","count":16,"current":6,"max":8,"restockType":"interval","restockAmount":2,"restockEverySeconds":3600,"nextRestockAt":0,"nextRestockAmount":0}
                  ]
                }
                """).getAsJsonObject();
        result.addProperty("shopId", shopId);
        result.getAsJsonArray("offers").get(0).getAsJsonObject()
                .add("itemNameComponent", JsonParser.parseString("{\"text\":\"Custom oak variant\"}"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<UiComponent> components(CommandShopAdminScreen screen) throws Exception {
        return (List<UiComponent>) field(field(screen, "form"), "components");
    }

    private static void renderForm(Minecraft minecraft, CommandShopAdminScreen screen) throws Exception {
        renderForm(minecraft, screen, Math.max(300, screen.width - 20));
    }

    private static void renderForm(Minecraft minecraft, CommandShopAdminScreen screen, int width) throws Exception {
        UiForm form = (UiForm) field(screen, "form");
        form.layout(new UiRect(10, 10, width, Math.max(200, screen.height - 20)));
        GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        form.render(graphics, minecraft.font, -1, -1, 0.0F);
        // Populate button geometry below the viewport too; screenshots separately exercise the real clipping.
        for (UiComponent row : components(screen)) {
            if (row.getBounds().getY() > form.getBounds().getBottom()) row.render(graphics, minecraft.font, -1, -1, 0.0F);
        }
        graphics.flush();
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String tr(String key) {
        return Component.translatable("gui.cobbledollarscommandshops.admin." + key).getString();
    }
}
