package fr.cobbledollars.commandshops.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.admin.AdminMessage;
import fr.cobbledollars.commandshops.admin.network.AdminNetwork;
import fr.cobbledollars.commandshops.client.admin.CommandShopAdminScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Uses loaded client resources and the same reflective message dispatch as the optional admin protocol. */
final class ClientAdminLocalizationSmokeTest {
    private static final String SNAPSHOT = """
            {"revision":"admin-localization-smoke","files":{"audit.json":{},"feedback.json":{},"global_bank.json":[]}}
            """;

    private ClientAdminLocalizationSmokeTest() {
    }

    static void verify(Minecraft minecraft) throws Exception {
        Language previousLanguage = Language.getInstance();
        Screen previousScreen = minecraft.screen;
        try {
            Method bridge = Class.forName("fr.cobbledollars.commandshops.client.admin.AdminClientBridge")
                    .getMethod("acceptLocalizedResult", boolean.class, AdminMessage.class);
            require(Modifier.isStatic(bridge.getModifiers()), "The localized client bridge must expose a static entry point.");
            Method dispatch = AdminNetwork.class.getDeclaredMethod("dispatchResult", boolean.class, AdminMessage.class);
            dispatch.setAccessible(true);

            CommandShopAdminScreen screen = new CommandShopAdminScreen(SNAPSHOT);
            minecraft.setScreen(screen);
            AdminMessage network = AdminMessage.translated("network.load_failed", "Could not load the configuration: %s", "global_bank.json");
            AdminMessage store = AdminMessage.translated("store.restore_failed",
                    "Saving failed and restoring the previous configuration failed: %s; %s", "disk full", "rollback denied");
            AdminMessage missing = AdminMessage.translated("smoke.missing_translation", "English fallback: %s", "details");
            Component cachedNetworkComponent = network.component();

            for (String locale : List.of("en_us", "fr_fr", "de_de")) {
                List<String> languages = locale.equals("en_us") ? List.of("en_us") : List.of("en_us", locale);
                Language.inject(ClientLanguage.loadFrom(minecraft.getResourceManager(), languages, false));
                String expectedNetwork = locale.equals("fr_fr")
                        ? "Impossible de charger la configuration : global_bank.json"
                        : "Could not load the configuration: global_bank.json";
                String expectedStore = locale.equals("fr_fr")
                        ? "L'enregistrement et la restauration de la configuration précédente ont échoué : disk full ; rollback denied"
                        : "Saving failed and restoring the previous configuration failed: disk full; rollback denied";
                require(expectedNetwork.equals(cachedNetworkComponent.getString()), "The cached network message ignored language " + locale);
                verifyDispatch(screen, dispatch, network, expectedNetwork, locale);
                verifyDispatch(screen, dispatch, store, expectedStore, locale);
                verifyDispatch(screen, dispatch, missing, "English fallback: details", locale);
                verifyDispatch(screen, dispatch, AdminMessage.literal("Invalid shop price: -1"), "Invalid shop price: -1", locale);
            }
        } finally {
            Language.inject(previousLanguage);
            minecraft.setScreen(previousScreen);
        }
        CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_ADMIN_LOCALIZATION_RESULT: PASS; French, English and fallback resources; message arguments and reflective client dispatch verified.");
    }

    private static void verifyDispatch(CommandShopAdminScreen screen, Method dispatch, AdminMessage message,
            String expected, String locale) throws Exception {
        require(expected.equals(message.component().getString()), "Wrong localized message for " + message.key() + " in " + locale);
        Field status = CommandShopAdminScreen.class.getDeclaredField("status");
        status.setAccessible(true);
        status.set(screen, "undelivered");
        dispatch.invoke(null, false, message);
        require(expected.equals(status.get(screen)), "The localized result did not reach the admin screen for " + locale);
        Field statusError = CommandShopAdminScreen.class.getDeclaredField("statusError");
        statusError.setAccessible(true);
        require(statusError.getBoolean(screen), "The localized failure must retain its error presentation.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
