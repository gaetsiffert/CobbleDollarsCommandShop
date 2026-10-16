package fr.cobbledollars.commandshops.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.client.admin.AdminEditorDocument;
import fr.cobbledollars.commandshops.client.admin.AdminFeedbackPreview;
import fr.cobbledollars.commandshops.client.admin.AdminFeedbackSettings;
import fr.cobbledollars.commandshops.client.admin.CommandShopAdminScreen;
import fr.cobbledollars.commandshops.client.ui.FieldBinding;
import fr.cobbledollars.commandshops.client.ui.UiComponent;
import fr.cobbledollars.commandshops.client.ui.UiRect;
import fr.cobbledollars.commandshops.client.ui.components.UiSelectComponent;
import fr.cobbledollars.commandshops.client.ui.components.UiTextFieldComponent;
import fr.cobbledollars.commandshops.client.ui.form.UiForm;
import kotlin.Pair;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Exercises editor callbacks on a detached fixture; no request or save packet is ever sent. */
final class ClientAdminUsabilitySmokeTest {
    private static final String SHOP = "shops/usability/shop.json";
    private static final List<String> OFFER = List.of("categories", "0", "offers", "0");
    private static final String SNAPSHOT = """
            {"revision":"admin-usability-fixture","files":{
              "audit.json":{},"global_bank.json":[],
              "feedback.json":{
                "buy_success":{"channels":[],"sound":{"id":"minecraft:entity.villager.no","volume":0.7,"pitch":0.9}},
                "shop_denied":{"sound":null}
              },
              "shops/usability/shop.json":{
                "conditions":[{"player_tags_all":["member"],"deny_message":"Membership required"}],
                "categories":[{"name":"Test","offers":[{
                  "id":"stone","match":{"include":[{"item":"minecraft:stone"}]},"price":25,"stock":40,
                  "restock":{"type":"interval","amount":7,"every_seconds":90}
                }]}]
              }
            }}
            """;

    private ClientAdminUsabilitySmokeTest() {
    }

    static void verify(Minecraft minecraft) throws Exception {
        Screen previousScreen = minecraft.screen;
        try {
            CommandShopAdminScreen screen = new CommandShopAdminScreen(SNAPSHOT);
            minecraft.setScreen(screen);
            AdminEditorDocument document = (AdminEditorDocument) field(screen, "document");
            verifySameSelections(minecraft, screen, document);
            verifyFeedbackDefaults(minecraft, screen, document);
            verifyConditionRemoval(minecraft, screen, document);
            verifyLocalValidation(minecraft, screen, document);
            verifySoundPreview(minecraft, document);
        } finally {
            minecraft.setScreen(previousScreen);
        }
        CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_ADMIN_USABILITY_RESULT: PASS; repeated selections preserve parameters; effective feedback defaults, customization/reset, condition deletion confirmation, inline validation/save guard and actual local sound preview verified.");
    }

    private static void verifySameSelections(Minecraft minecraft, CommandShopAdminScreen screen, AdminEditorDocument document) throws Exception {
        String before = document.serialize();
        navigate(minecraft, screen, "feedback.json", List.of("buy_success"));
        selectCurrent(screen, "detailed");
        require(before.equals(document.serialize()), "Selecting the active detailed sound format reset its custom parameters.");
        navigate(minecraft, screen, SHOP, List.of("categories", "0", "offers", "0", "restock"));
        selectCurrent(screen, "interval");
        require(before.equals(document.serialize()), "Selecting the active restock type reset its amount or interval.");
        require(document.getDirtyCount() == 0, "No-op selections must not dirty the draft.");
    }

    private static void verifyFeedbackDefaults(Minecraft minecraft, CommandShopAdminScreen screen, AdminEditorDocument document) throws Exception {
        navigate(minecraft, screen, "feedback.json", List.of());
        JsonObject root = document.getFiles().getAsJsonObject("feedback.json");
        require(!root.has("buy_failure"), "Fixture failure event must inherit its defaults.");
        JsonObject effective = AdminFeedbackSettings.INSTANCE.effectiveEvent(root, "buy_failure");
        UiComponent customize = actionRow(screen, tr("field.buy_failure"));
        require(((Number) field(customize, "color")).intValue() == 0x888888, "Inherited feedback event must be gray.");
        int summaries = 0;
        for (UiComponent row : components(screen)) {
            UiComponent control = unwrap(row);
            if (control instanceof UiTextFieldComponent && "inherited.buy_failure".equals(binding(control).getKey())) {
                require(!(Boolean) field(control, "editable"), "Inherited values must be read-only until customized.");
                require(((Number) field(control, "valueColor")).intValue() == 0x888888, "Inherited values must be displayed in gray.");
                summaries++;
            }
        }
        require(summaries == 4, "Missing effective channels, sound id, volume or pitch for inherited feedback.");
        require(document.getDirtyCount() == 0 && !root.has("buy_failure"), "Displaying effective defaults must not alter the draft.");
        invokeAction(customize, tr("customize"));
        require(effective.toString().equals(root.get("buy_failure").toString()), "Customizing did not start with the actual effective defaults.");
        require(List.of("buy_failure").equals(field(screen, "segments")), "Customizing an event must open its settings.");
        navigate(minecraft, screen, "feedback.json", List.of());
        invokeAction(actionRow(screen, tr("field.buy_failure") + " ("), tr("reset_defaults"));
        require(!root.has("buy_failure") && document.getDirtyCount() == 0, "Resetting the event must restore inherited defaults without an override.");
    }

    private static void verifyConditionRemoval(Minecraft minecraft, CommandShopAdminScreen screen, AdminEditorDocument document) throws Exception {
        List<String> valuesPath = List.of("conditions", "0", "player_tags_all");
        navigate(minecraft, screen, SHOP, valuesPath);
        UiComponent removable = components(screen).stream().filter(row -> row.getClass().getSimpleName().equals("AdminRemovableRow")).findFirst().orElseThrow();
        JsonElement original = document.resolve(SHOP, List.of("conditions")).deepCopy();
        clickRemove(removable);
        require(field(screen, "modal") != null, "Removing a rule's final value must open a confirmation.");
        require(original.equals(document.resolve(SHOP, List.of("conditions"))), "Opening confirmation must preserve the rule.");
        screen.keyPressed(256, 0, 0);
        require(field(screen, "modal") == null && original.equals(document.resolve(SHOP, List.of("conditions"))), "Cancelling the deletion must retain the final value.");
        clickRemove(removable);
        Object modal = field(screen, "modal");
        require(modal != null, "Confirmation must reopen after cancellation.");
        ((Function0<?>) field(modal, "confirm")).invoke();
        require(field(screen, "modal") == null, "Confirmed deletion must dismiss the modal.");
        require(document.resolve(SHOP, List.of("conditions")).getAsJsonArray().isEmpty(), "Confirmed deletion must remove the entire rule.");
        require(List.of("conditions").equals(field(screen, "segments")), "After removing a rule the editor must navigate to the condition list.");
    }

    @SuppressWarnings("unchecked")
    private static void verifyLocalValidation(Minecraft minecraft, CommandShopAdminScreen screen, AdminEditorDocument document) throws Exception {
        navigate(minecraft, screen, SHOP, OFFER);
        UiComponent priceRow = null;
        for (UiComponent row : components(screen)) {
            UiComponent control = unwrap(row);
            if (control instanceof UiTextFieldComponent && "price".equals(binding(control).getKey())) {
                ((FieldBinding<String>) binding(control)).set("invalid");
                priceRow = row;
                break;
            }
        }
        require(priceRow != null, "The offer price field is missing.");
        renderForm(minecraft, screen);
        UiComponent validated = priceRow;
        while (!validated.getClass().getSimpleName().equals("AdminValidatedRow")) validated = (UiComponent) field(validated, "content");
        String error = (String) ((Function0<?>) field(validated, "error")).invoke();
        require(error != null && !error.isBlank() && !error.contains("gui.cobbledollarscommandshops"), "Local numeric validation needs a translated inline explanation.");
        UiComponent content = (UiComponent) field(validated, "content");
        require(validated.getPreferredHeight() > content.getPreferredHeight(), "An inline error must reserve space below its field.");
        List<Component> tooltip = validated.tooltip(validated.getBounds().getX() + 6, validated.getBounds().getY() + content.getPreferredHeight() + 2);
        require(tooltip != null && error.equals(tooltip.getFirst().getString()), "Inline validation must expose its complete error as a tooltip.");
        invoke(screen, "save");
        require(!(Boolean) field(screen, "pending") && field(screen, "pendingOperation") == null, "A locally invalid draft must never start a network save.");
        require((Boolean) field(screen, "statusError") && ((String) field(screen, "status")).contains(error), "Blocked saves must explain the first error.");
        require("invalid".equals(document.resolve(SHOP, OFFER).getAsJsonObject().get("price").getAsString()), "Local validation must preserve the unfinished text.");
    }

    private static void verifySoundPreview(Minecraft minecraft, AdminEditorDocument document) {
        JsonObject feedback = document.getFiles().getAsJsonObject("feedback.json");
        String original = document.serialize();
        AdminFeedbackPreview.PreviewResult played = AdminFeedbackPreview.INSTANCE.play(feedback, "buy_success", minecraft);
        require(played.getStatus() == AdminFeedbackPreview.PreviewStatus.PLAYED, "A registered resource sound must play locally from the unsaved draft.");
        require("minecraft:entity.villager.no".equals(played.getSoundId()), "The preview must use the custom draft sound id.");
        require(AdminFeedbackPreview.INSTANCE.play(feedback, "shop_denied", minecraft).getStatus() == AdminFeedbackPreview.PreviewStatus.NO_SOUND, "An explicitly disabled sound must explain that no preview is available.");
        JsonObject unknown = feedback.deepCopy();
        unknown.getAsJsonObject("buy_success").addProperty("sound", "commandshops_smoke:unknown_sound");
        require(AdminFeedbackPreview.INSTANCE.play(unknown, "buy_success", minecraft).getStatus() == AdminFeedbackPreview.PreviewStatus.UNKNOWN_SOUND, "An unknown sound must report an explanation instead of playing.");
        require(original.equals(document.serialize()), "Sound preview must not modify the draft.");
    }

    @SuppressWarnings("unchecked")
    private static void selectCurrent(CommandShopAdminScreen screen, String current) throws Exception {
        for (UiComponent row : components(screen)) {
            UiComponent control = unwrap(row);
            if (!(control instanceof UiSelectComponent) || !current.equals(binding(control).get())) continue;
            UiRect button = (UiRect) field(control, "buttonRect");
            require(control.mouseClicked(button.getX() + 2, button.getY() + 2, 0), "The selector did not open.");
            require(field(screen, "dropdownState") != null, "The selector did not expose its options.");
            Function1<Set<String>, ?> apply = (Function1<Set<String>, ?>) field(screen, "dropdownApply");
            setField(screen, "dropdownState", null);
            apply.invoke(Set.of(current));
            return;
        }
        throw new AssertionError("Missing selector for " + current);
    }

    private static void clickRemove(UiComponent row) {
        require(row.mouseClicked(row.getBounds().getRight() - 14, row.getBounds().getY() + 2, 0), "The final-value removal action did not respond.");
    }

    private static FieldBinding<?> binding(UiComponent component) throws Exception {
        return (FieldBinding<?>) field(component, "binding");
    }

    private static UiComponent actionRow(CommandShopAdminScreen screen, String labelPrefix) throws Exception {
        for (UiComponent row : components(screen)) {
            UiComponent control = unwrap(row);
            if (control.getClass().getSimpleName().equals("AdminActionRow") && ((String) field(control, "label")).startsWith(labelPrefix)) return control;
        }
        throw new AssertionError("Missing action row: " + labelPrefix);
    }

    @SuppressWarnings("unchecked")
    private static void invokeAction(UiComponent row, String label) throws Exception {
        for (Pair<String, Function0<?>> action : (List<Pair<String, Function0<?>>>) field(row, "actions")) {
            if (label.equals(action.getFirst())) { action.getSecond().invoke(); return; }
        }
        throw new AssertionError("Missing action: " + label);
    }

    @SuppressWarnings("unchecked")
    private static List<UiComponent> components(CommandShopAdminScreen screen) throws Exception {
        return (List<UiComponent>) field(field(screen, "form"), "components");
    }

    private static UiComponent unwrap(UiComponent component) throws Exception {
        while (component.getClass().getSimpleName().equals("AdminRemovableRow") || component.getClass().getSimpleName().equals("AdminValidatedRow")) {
            component = (UiComponent) field(component, "content");
        }
        return component;
    }

    @SuppressWarnings("unchecked")
    private static void navigate(Minecraft minecraft, CommandShopAdminScreen screen, String file, List<String> path) throws Exception {
        ((UiForm) field(screen, "form")).clearFocus();
        setField(screen, "selectedFile", file);
        setField(screen, "tab", file.endsWith("/shop.json") ? 0 : file.endsWith("bank.json") ? 1 : 3);
        List<String> segments = (List<String>) field(screen, "segments");
        segments.clear();
        segments.addAll(path);
        invoke(screen, "rebuildForm");
        renderForm(minecraft, screen);
    }

    private static void renderForm(Minecraft minecraft, CommandShopAdminScreen screen) throws Exception {
        UiForm form = (UiForm) field(screen, "form");
        form.layout(new UiRect(10, 10, Math.max(300, screen.width - 20), Math.max(200, screen.height - 20)));
        GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        form.render(graphics, minecraft.font, -1, -1, 0.0F);
        graphics.flush();
    }

    private static void invoke(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
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

    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static String tr(String key) {
        return Component.translatable("gui.cobbledollarscommandshops.admin." + key).getString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
