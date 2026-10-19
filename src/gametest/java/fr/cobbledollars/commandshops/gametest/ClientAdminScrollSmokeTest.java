package fr.cobbledollars.commandshops.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.admin.AdminMessage;
import fr.cobbledollars.commandshops.admin.network.AdminLiveEnvelope;
import fr.cobbledollars.commandshops.client.admin.CommandShopAdminScreen;
import fr.cobbledollars.commandshops.client.ui.UiComponent;
import fr.cobbledollars.commandshops.client.ui.UiRect;
import fr.cobbledollars.commandshops.client.ui.form.UiForm;
import kotlin.Pair;
import kotlin.jvm.functions.Function0;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Regressions for real admin view reconstruction, without any save or runtime request. */
final class ClientAdminScrollSmokeTest {
    private static final String SHOP = "shops/scroll_demo/shop.json";
    private ClientAdminScrollSmokeTest() { }

    static void verify(Minecraft minecraft) throws Exception {
        var previous = minecraft.screen;
        try {
            CommandShopAdminScreen screen = new CommandShopAdminScreen(snapshot());
            minecraft.setScreen(screen);
            String draft = screen.currentDraft();
            verifyConfiguration(screen);
            verifyAudit(screen);
            verifyStock(screen);
            navigate(screen, SHOP, 0, List.of("categories", "0", "offers"));
            layout(screen);
            require(offset(screen) == 216, "Returning from Stocks/Audit lost the configuration view's own scroll position.");
            setField(screen, "search", "scroll");
            screen.mouseScrolled(20, 100, 0, -4);
            require(((Number) field(screen, "fileScroll")).intValue() == 4, "The sidebar fixture needs a genuinely scrolled file list.");
            invoke(screen, "selectTab", 4);
            layout(screen);
            invoke(screen, "selectTab", 0);
            layout(screen);
            require(SHOP.equals(field(screen, "selectedFile")) && List.of("categories", "0", "offers").equals(field(screen, "segments")),
                    "Returning to Shops lost its selected file or nested configuration path.");
            require(offset(screen) == 216, "Returning to a tab lost its nested view's scroll position.");
            require("scroll".equals(field(screen, "search")) && ((Number) field(screen, "fileScroll")).intValue() == 4,
                    "Returning to a tab lost its sidebar search or file-list scroll position.");
            screen.acceptSnapshot(screen.currentDraft());
            layout(screen);
            require(List.of("categories", "0", "offers").equals(field(screen, "segments")) && offset(screen) == 216,
                    "Accepting an unchanged save/reload snapshot discarded the selected path or its scroll position.");
            invoke(screen, "init");
            layout(screen);
            require(offset(screen) == 216, "Screen reinitialization lost the configuration view's scroll position.");
            require(draft.equals(screen.currentDraft()), "Browsing and restoring scroll changed the configuration draft.");
            verifyDeletedPathFallback(minecraft);
        } finally {
            minecraft.setScreen(previous);
        }
        CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_ADMIN_SCROLL_RESULT: PASS; independent config paths/files, Audit details/back/filter rebuilds, stock pages, temporary loading forms, correlated replies and screen reinitialization retain their scroll positions without changing drafts.");
    }

    private static void verifyConfiguration(CommandShopAdminScreen screen) throws Exception {
        navigate(screen, SHOP, 0, List.of("categories", "0", "offers"));
        layout(screen);
        scroll(screen, 18);
        int before = offset(screen);
        require(before > 0, "The scroll fixture needs a genuinely scrolled form.");
        invoke(screen, "rebuildForm");
        layout(screen);
        int after = offset(screen);
        CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_ADMIN_SCROLL_REBUILD: before={}, after={}", before, after);
        require(after == before, "Rebuilding an unchanged config view reset its scroll position: " + before + " -> " + after);

        invokeAction(screen, label -> label.startsWith("19."), tr("edit"));
        layout(screen);
        require(offset(screen) == 0, "A previously unopened configuration child must start at the top.");
        scroll(screen, 4);
        int child = offset(screen);
        require(child > 0, "The configuration child needs enough content to scroll.");
        invokeAction(screen, label -> true, "←");
        layout(screen);
        require(offset(screen) == before, "Returning to a configuration parent lost its previous scroll position.");
        invokeAction(screen, label -> label.startsWith("19."), tr("edit"));
        layout(screen);
        require(offset(screen) == child, "Reopening a configuration child lost its own scroll position.");
        invokeAction(screen, label -> true, "←");
        layout(screen);
        invoke(screen, "selectFile", "shops/second/shop.json");
        layout(screen);
        require(offset(screen) == 0, "A different configuration file inherited another file's scroll position.");
        navigate(screen, "shops/second/shop.json", 0, List.of("categories", "0", "offers"));
        layout(screen); scroll(screen, 9);
        require(offset(screen) == 108, "The second file did not reach its independent scroll position.");
        invoke(screen, "selectFile", SHOP);
        layout(screen);
        require(List.of("categories", "0", "offers").equals(field(screen, "segments")) && offset(screen) == before,
                "Returning to a configuration file lost its own nested path or scroll position.");
        invoke(screen, "selectFile", "shops/second/shop.json");
        layout(screen);
        require(List.of("categories", "0", "offers").equals(field(screen, "segments")) && offset(screen) == 108,
                "Reopening the second configuration file lost its own nested path or scroll position.");
        invoke(screen, "selectFile", SHOP);
        layout(screen);
    }

    private static void verifyAudit(CommandShopAdminScreen screen) throws Exception {
        ClientAdminAuditSmokeTest.seed(screen, "shops", false);
        Object panel = field(screen, "audit");
        JsonObject state = ((JsonObject) field(panel, "data")).deepCopy();
        JsonObject template = state.getAsJsonArray("rows").get(0).getAsJsonObject();
        JsonArray rows = new JsonArray();
        for (int index = 0; index < 25; index++) {
            JsonObject row = template.deepCopy();
            row.addProperty("id", "scroll_shop_" + index); row.addProperty("shopId", "scroll_shop_" + index);
            row.addProperty("name", "Scroll shop " + index); rows.add(row);
        }
        state.add("rows", rows); state.addProperty("total", 25);
        setField(panel, "data", state); invoke(screen, "rebuildForm"); layout(screen); scroll(screen, 18);
        int list = offset(screen);
        require(list == 216, "The Audit fixture needs a genuinely scrolled list.");

        invokeAction(screen, label -> true, audit("show_filters"));
        screen.tick(); layout(screen);
        require(offset(screen) == list, "Expanding Audit filters reset the current view's scroll position.");
        invokeAction(screen, label -> true, audit("hide_filters"));
        screen.tick(); layout(screen);
        require(offset(screen) == list, "Collapsing Audit filters reset the current view's scroll position.");

        invokeAction(screen, label -> label.startsWith("Scroll shop 18"), audit("details"));
        screen.tick(); layout(screen);
        require(offset(screen) == 0, "Opening Audit details inherited the list's offset.");
        scroll(screen, 4);
        int details = offset(screen);
        require(details > 0, "The Audit detail fixture needs enough content to scroll.");
        invokeAction(screen, label -> true, audit("back"));
        screen.tick(); layout(screen);
        require(offset(screen) == list, "Back from Audit details lost the list's position.");
        invokeAction(screen, label -> label.startsWith("Scroll shop 18"), audit("details"));
        screen.tick(); layout(screen);
        require(offset(screen) == details, "Reopening the same Audit details lost their own position.");
        invokeAction(screen, label -> true, audit("back"));
        screen.tick(); layout(screen);

        UUID operation = UUID.randomUUID();
        setField(panel, "operation", operation); setField(panel, "requestedQuery", invoke(panel, "query"));
        setField(panel, "since", System.currentTimeMillis()); setField(panel, "pending", true);
        setField(panel, "needsRebuild", true); screen.tick(); layout(screen);
        require(offset(screen) < list, "The temporary Audit loading form must be short enough to exercise clamping.");
        screen.acceptAuditResult(operation, envelope(state)); screen.tick(); layout(screen);
        require(offset(screen) == list, "An Audit loading form erased the list position before the matching response.");

        invoke(screen, "rebuildForm"); layout(screen);
        require(offset(screen) == list, "Refreshing an unchanged Audit view reset its scroll position.");
    }

    private static void verifyStock(CommandShopAdminScreen screen) throws Exception {
        ClientAdminLiveSmokeTest.seed(screen, "scroll_demo");
        JsonObject stock = ((JsonObject) field(screen, "liveStock")).deepCopy();
        JsonObject template = stock.getAsJsonArray("offers").get(0).getAsJsonObject();
        JsonArray offers = new JsonArray();
        for (int index = 0; index < 45; index++) {
            JsonObject offer = template.deepCopy(); offer.addProperty("stockKey", "scroll#" + index);
            offer.addProperty("offerId", "scroll_" + index); offers.add(offer);
        }
        stock.add("offers", offers); setField(screen, "liveStock", stock);
        navigate(screen, SHOP, 2, List.of()); layout(screen);
        require(offset(screen) == 0, "A new Stocks view inherited configuration or Audit scroll.");
        scroll(screen, 18);
        int firstPage = offset(screen);
        require(firstPage == 216, "The Stocks fixture needs a genuinely scrolled page.");
        invoke(screen, "rebuildForm"); layout(screen);
        require(offset(screen) == firstPage, "Rebuilding Stocks reset its current scroll position.");
        invokeAction(screen, label -> true, tr("live.next")); layout(screen);
        require(offset(screen) == 0, "A previously unopened Stocks page must start at the top.");
        scroll(screen, 9);
        int secondPage = offset(screen);
        invokeAction(screen, label -> true, tr("live.previous")); layout(screen);
        require(offset(screen) == firstPage, "Returning to the previous Stocks page lost its position.");
        invokeAction(screen, label -> true, tr("live.next")); layout(screen);
        require(offset(screen) == secondPage, "Reopening a Stocks page lost its own scroll position.");
        invokeAction(screen, label -> true, tr("live.previous")); layout(screen);

        UUID operation = UUID.randomUUID();
        setField(screen, "liveOperation", operation); setField(screen, "liveAction", "stock");
        setField(screen, "livePending", true); setField(screen, "livePendingSince", System.currentTimeMillis());
        setField(screen, "liveStock", null); setField(screen, "liveFormDirty", true);
        screen.tick(); layout(screen);
        require(offset(screen) < firstPage, "The temporary Stocks loading form must exercise scroll clamping.");
        screen.acceptLiveResult(operation, envelope(stock)); screen.tick(); layout(screen);
        require(offset(screen) == firstPage, "A Stocks loading form erased the position before its matching reply.");
    }

    private static void verifyDeletedPathFallback(Minecraft minecraft) throws Exception {
        CommandShopAdminScreen screen = new CommandShopAdminScreen(snapshot()); minecraft.setScreen(screen);
        navigate(screen, SHOP, 0, List.of("categories", "0", "offers")); layout(screen); scroll(screen, 18);
        invokeAction(screen, label -> label.startsWith("45."), tr("edit")); layout(screen);
        JsonObject snapshot = JsonParser.parseString(screen.currentDraft()).getAsJsonObject();
        snapshot.getAsJsonObject("files").getAsJsonObject(SHOP).getAsJsonArray("categories")
                .get(0).getAsJsonObject().getAsJsonArray("offers").remove(44);
        screen.acceptSnapshot(snapshot.toString()); layout(screen);
        require(List.of("categories", "0", "offers").equals(field(screen, "segments")),
                "A snapshot deleting the selected child must fall back to the deepest valid parent.");
        require(offset(screen) == 216, "Falling back after a removed child lost the known parent's position.");
    }

    private static String snapshot() {
        JsonArray offers = new JsonArray();
        for (int index = 0; index < 45; index++) {
            JsonObject offer = JsonParser.parseString("""
                    {"id":"stone","match":{"include":[{"item":"minecraft:stone"}]},"count":16,
                    "price":120,"stock":64,"restock":{"type":"interval","amount":8,"every_seconds":3600}}
                    """).getAsJsonObject();
            offer.addProperty("id", "stone_" + index);
            offers.add(offer);
        }
        JsonObject category = new JsonObject(); category.addProperty("name", "Scroll fixture"); category.add("offers", offers);
        JsonArray categories = new JsonArray(); categories.add(category);
        JsonObject shop = new JsonObject(); shop.add("categories", categories);
        JsonObject files = new JsonObject(); files.add(SHOP, shop); files.add("shops/second/shop.json", shop.deepCopy());
        for (int index = 0; index < 30; index++) {
            JsonObject extra = new JsonObject(); extra.add("categories", new JsonArray());
            files.add("shops/scroll_extra_" + index + "/shop.json", extra);
        }
        files.add("audit.json", new JsonObject());
        JsonObject snapshot = new JsonObject(); snapshot.addProperty("revision", "scroll-fixture"); snapshot.add("files", files);
        return snapshot.toString();
    }

    @SuppressWarnings("unchecked")
    private static void navigate(CommandShopAdminScreen screen, String file, int tab, List<String> segments) throws Exception {
        ((UiForm) field(screen, "form")).clearFocus();
        setField(screen, "selectedFile", file); setField(screen, "tab", tab);
        List<String> path = (List<String>) field(screen, "segments"); path.clear(); path.addAll(segments);
        invoke(screen, "rebuildForm");
    }

    private static void layout(CommandShopAdminScreen screen) throws Exception {
        ((UiForm) field(screen, "form")).layout(new UiRect(10, 10, 640, 120));
    }

    private static void scroll(CommandShopAdminScreen screen, int steps) throws Exception {
        UiForm form = (UiForm) field(screen, "form");
        UiRect bounds = form.getBounds();
        require(form.mouseScrolled(bounds.getRight() - 2, bounds.getBottom() - 2, 0, -steps), "The form rejected scrolling.");
    }

    private static int offset(CommandShopAdminScreen screen) throws Exception { return ((Number) field(field(screen, "form"), "scrollOffset")).intValue(); }
    private static Object field(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void setField(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private static Object invoke(Object target, String name) throws Exception { Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target); }
    private static Object invoke(Object target, String name, String argument) throws Exception { Method method = target.getClass().getDeclaredMethod(name, String.class); method.setAccessible(true); return method.invoke(target, argument); }
    private static Object invoke(Object target, String name, int argument) throws Exception { Method method = target.getClass().getDeclaredMethod(name, int.class); method.setAccessible(true); return method.invoke(target, argument); }
    @SuppressWarnings("unchecked")
    private static void invokeAction(CommandShopAdminScreen screen, Predicate<String> rowLabel, String actionLabel) throws Exception {
        for (UiComponent component : (List<UiComponent>) field(field(screen, "form"), "components")) {
            while (component.getClass().getSimpleName().equals("AdminValidatedRow") || component.getClass().getSimpleName().equals("AdminRemovableRow")) component = (UiComponent) field(component, "content");
            if (!component.getClass().getSimpleName().equals("AdminActionRow") || !rowLabel.test((String) field(component, "label"))) continue;
            for (Pair<String, Function0<?>> action : (List<Pair<String, Function0<?>>>) field(component, "actions")) {
                if (actionLabel.equals(action.getFirst())) { action.getSecond().invoke(); return; }
            }
        }
        throw new AssertionError("Missing action: " + actionLabel);
    }
    private static String envelope(JsonObject state) { return new AdminLiveEnvelope(true, state, AdminMessage.literal("Loaded")).json(); }
    private static String tr(String key) { return Component.translatable("gui.cobbledollarscommandshops.admin." + key).getString(); }
    private static String audit(String key) { return tr("audit." + key); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
