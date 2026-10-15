package fr.cobbledollars.commandshops.gametest;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.client.admin.AdminEditorDocument;
import fr.cobbledollars.commandshops.client.admin.CommandShopAdminScreen;
import fr.cobbledollars.commandshops.client.ui.FieldBinding;
import fr.cobbledollars.commandshops.client.ui.UiComponent;
import fr.cobbledollars.commandshops.client.ui.UiRect;
import fr.cobbledollars.commandshops.client.ui.components.UiSelectComponent;
import fr.cobbledollars.commandshops.client.ui.components.UiTextFieldComponent;
import fr.cobbledollars.commandshops.client.ui.form.UiForm;
import kotlin.jvm.functions.Function1;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Opt-in client render smoke test; edits only a detached fixture and never sends a save packet. */
@EventBusSubscriber(modid = CobbleDollarsCommandShopsMod.MODID, value = Dist.CLIENT)
public final class ClientAdminUiSmokeTest {
    private static final int SETTLE_FRAMES = 12;
    private static final String SHOP_FILE = "shops/admin_demo/shop.json";
    private static final String[] CAPTURE_NAMES = {"admin-root.png", "admin-offers.png", "admin-offer.png", "admin-feedback.png", "admin-bank.png", "admin-conditions.png", "admin-condition.png", "admin-feedback-defaults.png", "admin-inline-error.png"};
    private static final String SNAPSHOT = """
            {
              "revision": "admin-ui-smoke-fixture",
              "files": {
                "audit.json": {
                  "enabled": true,
                  "flush_each_entry": true,
                  "events": {
                    "buy_success": true,
                    "buy_failure": false,
                    "sell_success": true,
                    "sell_failure": false,
                    "visibility_changed": true
                  }
                },
                "feedback.json": {
                  "buy_success": {
                    "channels": ["action_bar", "sound"],
                    "sound": {"id": "minecraft:entity.experience_orb.pickup", "volume": 0.25, "pitch": 1.8}
                  },
                  "buy_failure": {"channels": ["chat", "sound"], "sound": "minecraft:entity.villager.no"},
                  "sell_success": {"channels": ["action_bar", "sound"], "sound": "minecraft:entity.experience_orb.pickup"},
                  "sell_failure": {"channels": ["chat"], "sound": null},
                  "shop_denied": {"channels": ["chat", "sound"], "sound": "minecraft:entity.villager.no"}
                },
                "global_bank.json": {
                  "categories": [{"name": "Minerals", "offers": [{"match": {"include": [{"item": "minecraft:iron_ingot"}]}, "price": 8}]}]
                },
                "shops/admin_demo/shop.json": {
                  "deny_message": "This shop requires access to the overworld.",
                  "conditions": [
                    {"player_tags_all": ["member"], "deny_message": "Membership is required."},
                    {"dimensions_any": ["minecraft:overworld"], "deny_message": "Return to the overworld."}
                  ],
                  "categories": [{
                    "name": "Building supplies",
                    "offers": [{
                      "id": "stone_bundle",
                      "match": {"include": [{"item": "minecraft:stone"}]},
                      "count": 16,
                      "price": 120,
                      "stock": 64,
                      "restock": {"type": "interval", "amount": 8, "every_seconds": 3600},
                      "conditions": {"player_tags_none": ["shop_blocked"]},
                      "purchase_bonuses": [{"required_bundles": 4, "rewards": [{"item": "minecraft:diamond", "count": 1}]}]
                    }]
                  }]
                },
                "shops/admin_demo/bank.json": {
                  "offers": [{"match": {"include": [{"tag": "minecraft:logs"}]}, "price": 2}]
                }
              }
            }
            """;

    private static CommandShopAdminScreen screen;
    private static long startedAt;
    private static int stage;
    private static int renderedFrames;
    private static boolean capturing;
    private static boolean completed;

    private ClientAdminUiSmokeTest() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!enabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (startedAt == 0L) {
                startedAt = System.currentTimeMillis();
            }
            require(System.currentTimeMillis() - startedAt < 120_000L, "Timed out while rendering the admin fixture.");
            if (screen == null && minecraft.screen instanceof TitleScreen && minecraft.getOverlay() == null) {
                Class.forName("fr.cobbledollars.commandshops.client.ui.components.UiToggleComponent");
                Class.forName("fr.cobbledollars.commandshops.client.ui.overlay.UiDropdownOverlay");
                Class.forName("fr.cobbledollars.commandshops.client.ui.overlay.UiItemPickerOverlay");
                ClientShopOfferOrderSmokeTest.verify(minecraft);
                ClientAdminLocalizationSmokeTest.verify(minecraft);
                ClientAdminOperationSmokeTest.verify(minecraft);
                ClientAdminUsabilitySmokeTest.verify(minecraft);
                screen = new CommandShopAdminScreen(SNAPSHOT);
                minecraft.setScreen(screen);
                assertView(SHOP_FILE, List.of());
                CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_ADMIN_UI_SMOKE: opened the detached fixture.");
            }
        } catch (Exception | AssertionError exception) {
            fail(exception);
        }
    }

    @SubscribeEvent
    public static void afterFrame(RenderFrameEvent.Post event) {
        if (!enabled() || screen == null || capturing) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        try {
            require(minecraft.screen == screen, "The admin screen was unexpectedly replaced.");
            if (minecraft.getOverlay() != null || ++renderedFrames < SETTLE_FRAMES) {
                return;
            }
            require(screen.width > 100 && screen.height > 100, "The admin screen has no usable viewport.");
            UiForm form = (UiForm) field(screen, "form");
            require(form.getBounds().getWidth() > 0 && form.getBounds().getHeight() > 0, "The admin form was not laid out.");
            require(!components(form).isEmpty(), "The admin form contains no components.");
            if (stage == 0 || stage == 2 || stage == 6) {
                require(hasComponent(form, UiTextFieldComponent.class), "The shop editor did not build its text fields.");
            } else if (stage == 3) {
                require(hasComponent(form, UiSelectComponent.class), "The feedback editor did not build its sound selector.");
            }
            verifyHelp(form);
            if (stage == 1 || stage == 5) verifyCollectionActions(form);
            if (stage == 6) verifyConditionRule(form);
            capture(minecraft);
        } catch (Exception | AssertionError exception) {
            fail(exception);
        }
    }

    private static void capture(Minecraft minecraft) throws Exception {
        String name = CAPTURE_NAMES[stage];
        File file = new File(new File(minecraft.gameDirectory, Screenshot.SCREENSHOT_DIR), name);
        Files.deleteIfExists(file.toPath());
        capturing = true;
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), message -> minecraft.execute(() -> {
            try {
                require(file.isFile() && Files.size(file.toPath()) > 0L, "The screenshot was not written: " + message.getString());
                CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_ADMIN_UI_SMOKE_CAPTURE: {}", file.getAbsolutePath());
                stage++;
                renderedFrames = 0;
                if (stage == CAPTURE_NAMES.length) {
                    completed = true;
                    CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_ADMIN_UI_SMOKE_RESULT: PASS; 9 views rendered; ordered conditions, custom denial fields, parameter help, collection action tooltips, inherited feedback defaults and inline validation verified.");
                    minecraft.stop();
                    return;
                }
                if (stage == 1) {
                    navigate(SHOP_FILE, 0, List.of("categories", "0", "offers"));
                } else if (stage == 2) {
                    navigate(SHOP_FILE, 0, List.of("categories", "0", "offers", "0"));
                } else if (stage == 3) {
                    navigate("feedback.json", 2, List.of("buy_success"));
                } else if (stage == 4) {
                    navigate("global_bank.json", 1, List.of());
                } else if (stage == 5) {
                    navigate(SHOP_FILE, 0, List.of("conditions"));
                } else if (stage == 6) {
                    navigate(SHOP_FILE, 0, List.of("conditions", "0"));
                } else if (stage == 7) {
                    AdminEditorDocument document = (AdminEditorDocument) field(screen, "document");
                    document.getFiles().getAsJsonObject("feedback.json").remove("buy_success");
                    navigate("feedback.json", 2, List.of());
                } else {
                    AdminEditorDocument document = (AdminEditorDocument) field(screen, "document");
                    document.resolve(SHOP_FILE, List.of("categories", "0", "offers", "0")).getAsJsonObject().addProperty("price", "invalid");
                    navigate(SHOP_FILE, 0, List.of("categories", "0", "offers", "0"));
                }
                capturing = false;
            } catch (Exception | AssertionError exception) {
                fail(exception);
            }
        }));
    }

    @SuppressWarnings("unchecked")
    private static void navigate(String path, int tab, List<String> segments) throws Exception {
        ((UiForm) field(screen, "form")).clearFocus();
        setField(screen, "selectedFile", path);
        setField(screen, "tab", tab);
        List<String> currentSegments = (List<String>) field(screen, "segments");
        currentSegments.clear();
        currentSegments.addAll(segments);
        Method rebuild = CommandShopAdminScreen.class.getDeclaredMethod("rebuildForm");
        rebuild.setAccessible(true);
        rebuild.invoke(screen);
        assertView(path, segments);
    }

    private static void assertView(String path, List<String> segments) throws Exception {
        require(Minecraft.getInstance().screen instanceof CommandShopAdminScreen, "The expected admin screen is not open.");
        require(path.equals(field(screen, "selectedFile")), "The wrong configuration file is selected.");
        require(segments.equals(field(screen, "segments")), "The editor navigated to the wrong configuration node.");
        require(field(screen, "form") instanceof UiForm, "The reused UiForm implementation is missing.");
    }

    @SuppressWarnings("unchecked")
    private static List<UiComponent> components(UiForm form) throws Exception {
        return (List<UiComponent>) field(form, "components");
    }

    private static boolean hasComponent(UiForm form, Class<? extends UiComponent> expected) throws Exception {
        for (UiComponent component : components(form)) {
            if (expected.isInstance(unwrap(component))) {
                return true;
            }
        }
        return false;
    }

    private static void verifyHelp(UiForm form) throws Exception {
        for (UiComponent row : components(form)) {
            if (row.getBounds().getBottom() < form.getBounds().getY() || row.getBounds().getY() > form.getBounds().getBottom()) continue;
            UiComponent control = unwrap(row);
            String name = control.getClass().getSimpleName();
            UiRect helpRect;
            if (name.equals("AdminActionRow")) {
                if (((String) field(control, "hint")).isBlank()) continue;
                helpRect = (UiRect) field(control, "helpRect");
            } else if (name.equals("UiItemFieldComponent")) {
                helpRect = (UiRect) field(control, "tooltipRect");
            } else {
                helpRect = (UiRect) field(control, "tooltipBounds");
            }
            require(helpRect != null, "Missing help marker for " + name);
            if (helpRect.getY() < form.getBounds().getY() || helpRect.getBottom() > form.getBounds().getBottom()) continue;
            List<Component> tooltip = control.tooltip(helpRect.getX() + 3, helpRect.getY() + 3);
            require(tooltip != null && !tooltip.isEmpty() && tooltip.getFirst().getString().length() > 20,
                    "Missing parameter explanation for " + name);
            require(!tooltip.getFirst().getString().contains("gui.cobbledollarscommandshops"), "Untranslated help text.");
        }
    }

    private static void verifyCollectionActions(UiForm form) throws Exception {
        List<String> expectedActions = List.of("edit", "move_up", "move_down", "duplicate", "delete");
        boolean found = false;
        for (UiComponent component : components(form)) {
            UiComponent row = unwrap(component);
            if (!row.getClass().getSimpleName().equals("AdminActionRow")) continue;
            List<?> buttons = (List<?>) field(row, "buttons");
            if (buttons.size() != expectedActions.size()) continue;
            found = true;
            UiRect helpRect = (UiRect) field(row, "helpRect");
            require(row.getPreferredHeight() == 16 && row.getBounds().getHeight() == 16,
                    "Collection actions must use a compact single-line row.");
            for (int index = 0; index < buttons.size(); index++) {
                UiRect button = (UiRect) field(buttons.get(index), "rect");
                require(button.getY() == helpRect.getY(), "Collection caption and buttons must share the same line.");
                require(helpRect.getRight() <= button.getX(), "Collection help must not overlap the action buttons.");
                List<Component> tooltip = row.tooltip(button.getX() + 2, button.getY() + 2);
                String expected = Component.translatable("gui.cobbledollarscommandshops.admin.tooltip." + expectedActions.get(index)).getString();
                require(tooltip != null && expected.equals(tooltip.getFirst().getString()), "Wrong action tooltip: " + expectedActions.get(index));
            }
            require(!row.mouseClicked(helpRect.getX() + 3, helpRect.getY() + 3, 0), "Help marker must not trigger an action.");
        }
        require(found, "The collection action buttons were not rendered.");
    }

    @SuppressWarnings("unchecked")
    private static void verifyConditionRule(UiForm form) throws Exception {
        boolean selectorFound = false;
        boolean messageFound = false;
        UiComponent selector = null;
        String selectorLabel = Component.translatable("gui.cobbledollarscommandshops.admin.condition_type").getString();
        for (UiComponent row : components(form)) {
            UiComponent control = unwrap(row);
            if (!(control instanceof UiSelectComponent) && !(control instanceof UiTextFieldComponent)) continue;
            FieldBinding<?> binding = (FieldBinding<?>) field(control, "binding");
            String helpKey;
            if (control instanceof UiSelectComponent && selectorLabel.equals(binding.getKey())) {
                require("player_tags_all".equals(binding.get()), "The condition selector lost its current type.");
                selectorFound = true;
                selector = control;
                helpKey = "condition_type";
            } else if (control instanceof UiTextFieldComponent && "deny_message".equals(binding.getKey())) {
                require("Membership is required.".equals(binding.get()), "The custom denial message was not preserved.");
                messageFound = true;
                helpKey = "condition_deny_message";
            } else continue;
            UiRect helpRect = (UiRect) field(control, "tooltipBounds");
            require(helpRect != null, "The access rule control has no help marker.");
            List<Component> tooltip = control.tooltip(helpRect.getX() + 3, helpRect.getY() + 3);
            String expected = Component.translatable("gui.cobbledollarscommandshops.admin.help." + helpKey).getString();
            require(tooltip != null && expected.equals(tooltip.getFirst().getString()), "The access rule control has incorrect help: " + helpKey);
        }
        require(selectorFound && messageFound, "The access rule must expose both its type and its custom denial message.");
        AdminEditorDocument document = (AdminEditorDocument) field(screen, "document");
        List<String> rulePath = List.of("conditions", "0");
        JsonElement original = document.resolve(SHOP_FILE, rulePath).deepCopy();
        UiRect selectorButton = (UiRect) field(selector, "buttonRect");
        require(selector.mouseClicked(selectorButton.getX() + 2, selectorButton.getY() + 2, 0), "The condition type selector did not open.");
        Function1<Set<String>, ?> apply = (Function1<Set<String>, ?>) field(screen, "dropdownApply");
        setField(screen, "dropdownState", null);
        apply.invoke(Set.of("player_tags_all"));
        require(original.equals(document.resolve(SHOP_FILE, rulePath)), "Selecting the current condition type must retain its values and denial message.");
    }

    private static Object field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static UiComponent unwrap(UiComponent component) throws Exception {
        while (component.getClass().getSimpleName().equals("AdminRemovableRow")
                || component.getClass().getSimpleName().equals("AdminValidatedRow")) {
            component = (UiComponent) field(component, "content");
        }
        return component;
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static boolean enabled() {
        return !completed && Boolean.getBoolean("commandshops.adminUiSmokeTest");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void fail(Throwable failure) {
        completed = true;
        CobbleDollarsCommandShopsMod.LOGGER.error("CLIENT_ADMIN_UI_SMOKE_RESULT: FAIL", failure);
        Minecraft.getInstance().stop();
    }
}
