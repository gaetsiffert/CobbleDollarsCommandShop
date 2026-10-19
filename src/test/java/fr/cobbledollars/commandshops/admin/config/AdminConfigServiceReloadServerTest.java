package fr.cobbledollars.commandshops.admin.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.audit.AuditFiles;
import fr.cobbledollars.commandshops.audit.AuditLogService;
import fr.cobbledollars.commandshops.command.CommandSettings;
import fr.cobbledollars.commandshops.feedback.ShopFeedbackService;
import fr.cobbledollars.commandshops.shop.ShopFiles;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(EphemeralTestServerProvider.class)
class AdminConfigServiceReloadServerTest {
    private static final String SHOP_ID = "large_audit_reload_test";
    private static final String SHOP_FILE = "shops/" + SHOP_ID + "/shop.json";
    private final Map<Path, byte[]> originals = new LinkedHashMap<>();
    private boolean fixtureOwned;

    @AfterEach
    void cleanup() throws IOException {
        AuditLogService.shutdown();
        AdminConfigService.clearAppliedConfiguration();
        ShopFeedbackService.clear();
        CommandSettings.clear();
        ShopRegistry.clear();
        for (var entry : originals.entrySet()) {
            if (entry.getValue() == null) Files.deleteIfExists(entry.getKey());
            else Files.write(entry.getKey(), entry.getValue());
        }
        if (fixtureOwned) {
            Files.deleteIfExists(ShopFiles.getConfigDirectory().resolve(SHOP_FILE));
            Files.deleteIfExists(ShopFiles.getShopDirectory().resolve(SHOP_ID));
        }
        for (Path file : AuditLogService.listReadableLogFiles()) Files.deleteIfExists(file);
    }

    @Test
    void startupBaselineAndReloadAcceptLargeFilesWhenAuditIsDisabled(MinecraftServer server) throws Exception {
        initialize(server, false);
        writeShop(40);

        AdminConfigService.reload(server, "Console", null);

        assertEquals(BigInteger.valueOf(40), ShopRegistry.getShop(SHOP_ID).getOfferById("tracked_price").price());
        assertFalse(AuditLogService.prepareRead().enabled());
        assertFalse(Files.exists(AuditLogService.getLogFile()), "Disabled audit must not introduce a new log file.");
        assertThrows(IOException.class, () -> AdminConfigService.snapshot(server),
                "Large disk configurations must remain excluded from the bounded editor protocol.");
    }

    @Test
    void reloadRecordsRealChangesToLargeDiskConfigurations(MinecraftServer server) throws Exception {
        initialize(server, true);
        writeShop(41);

        AdminConfigService.reload(server, "ReloadAdministrator", "11111111-2222-3333-4444-555555555555");

        assertEquals(BigInteger.valueOf(41), ShopRegistry.getShop(SHOP_ID).getOfferById("tracked_price").price());
        AuditLogService.prepareRead();
        List<JsonObject> history = Files.readAllLines(AuditLogService.getLogFile()).stream()
                .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        JsonObject changed = history.stream().filter(event -> event.has("file")
                && SHOP_FILE.equals(event.get("file").getAsString())).findFirst().orElseThrow();
        assertEquals("prepared", changed.get("phase").getAsString());
        assertEquals("/categories/0/offers/0/price", changed.get("path").getAsString());
        assertEquals(25, changed.get("before").getAsInt());
        assertEquals(41, changed.get("after").getAsInt());
        assertEquals("ReloadAdministrator", changed.get("actor_name").getAsString());
        assertEquals("reload", changed.get("source").getAsString());
        assertTrue(history.stream().anyMatch(event -> "applied".equals(event.get("phase").getAsString())
                && changed.get("operation_id").equals(event.get("operation_id"))));
    }

    private void initialize(MinecraftServer server, boolean enabled) throws IOException {
        Path root = ShopFiles.getConfigDirectory();
        Files.createDirectories(root);
        for (String file : List.of("audit.json", "commands.json", "feedback.json", "global_bank.json")) {
            Path path = root.resolve(file);
            originals.put(path, Files.exists(path) ? Files.readAllBytes(path) : null);
        }
        assertFalse(Files.exists(ShopFiles.getShopDirectory().resolve(SHOP_ID)), "The fixture shop ID must be unused.");
        fixtureOwned = true;
        Files.writeString(AuditFiles.getConfigFile(), "{\"enabled\":" + enabled + "}");
        Files.writeString(CommandSettings.getConfigFile(), CommandSettings.defaultConfigText());
        Files.writeString(root.resolve("feedback.json"), "{}");
        Files.writeString(root.resolve("global_bank.json"), "[]");
        writeShop(25);
        ShopRegistry.reload(server.registryAccess());
        ShopFeedbackService.reload();
        CommandSettings.reload();
        AuditLogService.reload();
        AdminConfigService.rememberAppliedConfiguration();
        assertEquals(BigInteger.valueOf(25), ShopRegistry.getShop(SHOP_ID).getOfferById("tracked_price").price());
    }

    private void writeShop(int price) throws IOException {
        Path path = ShopFiles.getConfigDirectory().resolve(SHOP_FILE);
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                { "categories": [{ "name": "Large configuration", "offers": [
                  { "id": "tracked_price", "match": { "include": [{ "item": "minecraft:emerald" }] }, "price": %d }
                ] }] }
                """.formatted(price) + " ".repeat(AdminConfigService.MAX_FILE_BYTES));
        assertTrue(Files.size(path) > AdminConfigService.MAX_FILE_BYTES);
    }
}
