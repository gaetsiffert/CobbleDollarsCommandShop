package fr.cobbledollars.commandshops.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.admin.AdminMessage;
import fr.cobbledollars.commandshops.admin.network.AdminChunkPayload;
import fr.cobbledollars.commandshops.admin.network.AdminNetwork;
import fr.cobbledollars.commandshops.client.admin.AdminClientBridge;
import fr.cobbledollars.commandshops.client.admin.AdminEditorDocument;
import fr.cobbledollars.commandshops.client.admin.CommandShopAdminScreen;
import fr.cobbledollars.commandshops.client.ui.UiComponent;
import fr.cobbledollars.commandshops.client.ui.components.UiSingleLineEditorState;
import fr.cobbledollars.commandshops.client.ui.components.UiTextFieldComponent;
import fr.cobbledollars.commandshops.client.ui.form.UiForm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Exercises the actual chunk receiver, reflective bridge and detached editor without sending packets. */
final class ClientAdminOperationSmokeTest {
    private static final UUID OPEN = new UUID(0L, 0L);
    private static final String BASE = """
            {"revision":"operation-base","files":{"audit.json":{"enabled":false,"note":"draft"},"feedback.json":{},"global_bank.json":[]}}
            """;
    private static final String SAVED = BASE.replace("operation-base", "operation-saved");
    private static final String RELOADED = BASE.replace("operation-base", "operation-reloaded");
    private static final AdminMessage SUCCESS = AdminMessage.translated("save.success", "Configuration saved and applied.");

    private ClientAdminOperationSmokeTest() { }

    static void verify(Minecraft minecraft) throws Exception {
        Screen previous = minecraft.screen;
        try {
            verifyEditedDraft(minecraft, previous);
            verifyTimeout(minecraft, previous);
            verifyBufferedTypingAfterTimeout(minecraft, previous);
            verifySupersededOperationAndSessionRenewal(minecraft, previous);
            verifyClosedAndNewScreens(minecraft, previous);
            verifyDisconnect(minecraft, previous);
        } finally {
            network("clearClientState", new Class<?>[0]);
            minecraft.setScreen(previous);
        }
        CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_ADMIN_OPERATION_RESULT: PASS; real chunk receiver and bridge preserve edited, expired, buffered and closed drafts; older replies cannot finish newer requests or restore an expired session; disconnect discards responses.");
    }

    private static void verifyEditedDraft(Minecraft minecraft, Screen backdrop) throws Exception {
        UUID nonce = UUID.randomUUID();
        CommandShopAdminScreen screen = open(minecraft, backdrop, nonce);
        UUID save = begin(screen, true);
        document(screen).getFiles().getAsJsonObject("audit.json").addProperty("enabled", true);
        result(save, true);
        snapshot(nonce, save, SAVED);

        require(minecraft.screen == screen, "A save replaced the editor instance.");
        require(document(screen).getFiles().getAsJsonObject("audit.json").get("enabled").getAsBoolean(),
                "A save response overwrote edits made after submission.");
        require(SAVED.equals(field(screen, "deferredSnapshot")), "The saved state was not retained for explicit loading.");
        require(!pending(screen), "A deferred matching snapshot did not finish its own pending operation.");
        screen.acceptSnapshot((String) field(screen, "deferredSnapshot"));
        requireDraft(screen, SAVED, "Explicitly loading the server state did not apply it.");
    }

    private static void verifyTimeout(Minecraft minecraft, Screen backdrop) throws Exception {
        UUID nonce = UUID.randomUUID();
        CommandShopAdminScreen screen = open(minecraft, backdrop, nonce);
        UUID save = begin(screen, true);
        network("dispatchTimeout", new Class<?>[]{UUID.class, AdminMessage.class}, save,
                AdminMessage.literal("Synthetic response timeout"));
        require(!pending(screen), "A network timeout left the editor locked.");
        result(save, true);
        require(!pending(screen), "A late success restarted the pending state.");
        require(Component.translatable("gui.cobbledollarscommandshops.admin.late_save_confirmed").getString().equals(field(screen, "status")),
                "A late save success did not distinguish the previous save from the current draft.");
        snapshot(nonce, save, SAVED);

        requireDraft(screen, BASE, "A save completed after timeout and automatically replaced the draft.");
        require(SAVED.equals(field(screen, "deferredSnapshot")), "A late successful save was lost.");
        require(!((Boolean) field(screen, "statusError")), "The available saved state was still presented as a failure.");
    }

    private static void verifyBufferedTypingAfterTimeout(Minecraft minecraft, Screen backdrop) throws Exception {
        UUID nonce = UUID.randomUUID();
        CommandShopAdminScreen screen = open(minecraft, backdrop, nonce);
        UUID save = begin(screen, true);
        network("dispatchTimeout", new Class<?>[]{UUID.class, AdminMessage.class}, save,
                AdminMessage.literal("Synthetic response timeout"));

        UiForm form = (UiForm) field(screen, "form");
        @SuppressWarnings("unchecked") List<UiComponent> rows = (List<UiComponent>) field(form, "components");
        UiComponent focused = null;
        UiTextFieldComponent textField = null;
        for (UiComponent row : rows) {
            UiComponent control = row;
            while (control.getClass().getSimpleName().equals("AdminRemovableRow")
                    || control.getClass().getSimpleName().equals("AdminValidatedRow")) {
                control = (UiComponent) field(control, "content");
            }
            if (control instanceof UiTextFieldComponent candidate) {
                focused = row;
                textField = candidate;
                break;
            }
        }
        require(focused != null, "The detached audit fixture has no editable note field.");
        Method focus = UiForm.class.getDeclaredMethod("setFocusedComponent", UiComponent.class);
        focus.setAccessible(true);
        focus.invoke(form, focused);
        require(form.charTyped('x', 0), "The focused field did not accept buffered typing.");
        require("draft".equals(document(screen).getFiles().getAsJsonObject("audit.json").get("note").getAsString()),
                "The fixture did not keep typing in the focused buffer.");

        result(save, true);
        snapshot(nonce, save, SAVED);
        snapshot(nonce, UUID.randomUUID(), SAVED);
        require(field(screen, "form") == form && field(form, "focusedComponent") == focused,
                "A deferred or ignored response interrupted the focused field.");
        UiSingleLineEditorState editor = (UiSingleLineEditorState) field(textField, "editor");
        require("draftx".equals(editor.getCurrentText()), "A late response discarded uncommitted buffered text.");
        require("draft".equals(document(screen).getFiles().getAsJsonObject("audit.json").get("note").getAsString()),
                "A late response unexpectedly committed the focused buffer.");

        form.clearFocus();
        require("draftx".equals(document(screen).getFiles().getAsJsonObject("audit.json").get("note").getAsString()),
                "Buffered typing was lost when the player eventually left the field.");
    }

    private static void verifySupersededOperationAndSessionRenewal(Minecraft minecraft, Screen backdrop) throws Exception {
        UUID expiredNonce = UUID.randomUUID();
        UUID renewedNonce = UUID.randomUUID();
        CommandShopAdminScreen screen = open(minecraft, backdrop, expiredNonce);
        UUID oldSave = begin(screen, true);
        AdminClientBridge.expireOperation(screen, oldSave);
        screen.completeOperation(oldSave);
        UUID reload = begin(screen, false);

        result(oldSave, true);
        snapshot(expiredNonce, oldSave, SAVED);
        require(pending(screen) && reload.equals(field(screen, "pendingOperation")),
                "An older save response completed the newer reload.");
        requireDraft(screen, BASE, "An older response changed the draft during a newer reload.");

        snapshot(renewedNonce, reload, RELOADED);
        requireDraft(screen, RELOADED, "The matching reload did not apply its snapshot.");
        require(renewedNonce.equals(sessionId()), "Reloading after session expiry did not adopt the replacement nonce.");
        require(!pending(screen), "The matching reload left the editor pending.");

        snapshot(expiredNonce, oldSave, SAVED);
        requireDraft(screen, RELOADED, "An obsolete snapshot replaced the latest server state.");
        require(field(screen, "deferredSnapshot") == null, "An obsolete snapshot replaced the deferred-state choice.");
        require(renewedNonce.equals(sessionId()), "An obsolete snapshot restored the expired session nonce.");
    }

    private static void verifyClosedAndNewScreens(Minecraft minecraft, Screen backdrop) throws Exception {
        UUID oldNonce = UUID.randomUUID();
        CommandShopAdminScreen closed = open(minecraft, backdrop, oldNonce);
        UUID save = begin(closed, true);
        minecraft.setScreen(backdrop);
        result(save, true);
        snapshot(oldNonce, save, SAVED);
        require(minecraft.screen == backdrop, "A late save reopened the closed editor.");

        UUID newNonce = UUID.randomUUID();
        snapshot(newNonce, OPEN, BASE);
        CommandShopAdminScreen reopened = (CommandShopAdminScreen) minecraft.screen;
        require(reopened != closed, "The command failed to open a new editor.");
        snapshot(oldNonce, save, SAVED);
        requireDraft(reopened, BASE, "A previous editor's reply replaced a new editor's document.");
        require(field(reopened, "deferredSnapshot") == null, "A previous editor's reply leaked into a new editor.");
        require(newNonce.equals(sessionId()), "An old editor's reply replaced the new session nonce.");
    }

    private static void verifyDisconnect(Minecraft minecraft, Screen backdrop) throws Exception {
        UUID nonce = UUID.randomUUID();
        CommandShopAdminScreen screen = open(minecraft, backdrop, nonce);
        UUID save = begin(screen, true);
        network("clearClientState", new Class<?>[0]);
        result(save, true);
        snapshot(nonce, save, SAVED);

        require(minecraft.screen == screen, "A disconnected reply changed the current screen.");
        requireDraft(screen, BASE, "A disconnected reply replaced the draft.");
        require(field(screen, "deferredSnapshot") == null, "A disconnected reply was mistaken for a legacy server opening.");
        require(sessionId() == null, "A disconnected reply restored a server session.");
    }

    private static CommandShopAdminScreen open(Minecraft minecraft, Screen backdrop, UUID nonce) throws Exception {
        minecraft.setScreen(backdrop);
        network("beginClientConnection", new Class<?>[0]);
        snapshot(nonce, OPEN, BASE);
        require(minecraft.screen instanceof CommandShopAdminScreen, "The initial server snapshot did not open the editor.");
        return (CommandShopAdminScreen) minecraft.screen;
    }

    private static UUID begin(CommandShopAdminScreen screen, boolean save) throws Exception {
        UUID id = AdminClientBridge.beginOperation(screen, screen.currentDraft(), save);
        setField(screen, "pending", true);
        setField(screen, "pendingOperation", id);
        setField(screen, "pendingSince", System.currentTimeMillis());
        Object session = field(null, AdminNetwork.class, "CLIENT_SESSION");
        Method requested = session.getClass().getDeclaredMethod("requested", UUID.class);
        requested.setAccessible(true);
        requested.invoke(session, id);
        return id;
    }

    private static void result(UUID operationId, boolean success) throws Exception {
        network("acceptResult", new Class<?>[]{UUID.class, boolean.class, AdminMessage.class}, operationId, success, SUCCESS);
    }

    private static void snapshot(UUID nonce, UUID operationId, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        network("acceptSnapshot", new Class<?>[]{AdminChunkPayload.class},
                new AdminChunkPayload(nonce, operationId, bytes.length, 0, bytes));
    }

    private static UUID sessionId() throws Exception {
        Object session = field(null, AdminNetwork.class, "CLIENT_SESSION");
        Method method = session.getClass().getDeclaredMethod("id");
        method.setAccessible(true);
        return (UUID) method.invoke(session);
    }

    private static void requireDraft(CommandShopAdminScreen screen, String expected, String message) {
        require(JsonParser.parseString(expected).equals(JsonParser.parseString(screen.currentDraft())), message);
    }

    private static AdminEditorDocument document(CommandShopAdminScreen screen) throws Exception {
        return (AdminEditorDocument) field(screen, "document");
    }

    private static boolean pending(CommandShopAdminScreen screen) throws Exception {
        return (Boolean) field(screen, "pending");
    }

    private static Object field(Object target, String name) throws Exception {
        return field(target, target.getClass(), name);
    }

    private static Object field(Object target, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void network(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = AdminNetwork.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(null, args);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
