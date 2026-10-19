package fr.cobbledollars.commandshops.admin.config;

import com.google.gson.JsonElement;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.admin.AdminMessage;
import fr.cobbledollars.commandshops.admin.AdminMessages;
import fr.cobbledollars.commandshops.audit.AuditFiles;
import fr.cobbledollars.commandshops.audit.AuditLogService;
import fr.cobbledollars.commandshops.command.CommandSettings;
import fr.cobbledollars.commandshops.feedback.FeedbackFiles;
import fr.cobbledollars.commandshops.feedback.ShopFeedbackService;
import fr.cobbledollars.commandshops.shop.BankFiles;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopFiles;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/** Server-authoritative configuration editing. Permission checks belong to the command/payload entry points. */
public final class AdminConfigService {
    public static final int MAX_SNAPSHOT_BYTES = AdminConfigStore.MAX_SNAPSHOT_BYTES;
    public static final int MAX_FILE_BYTES = AdminConfigStore.MAX_FILE_BYTES;
    public static final int MAX_FILES = AdminConfigStore.MAX_FILES;
    private static final AdminConfigStore STORE = new AdminConfigStore(ShopFiles.getConfigDirectory());
    private static Map<String, JsonElement> appliedFiles;
    private static boolean reloadRequired;

    private AdminConfigService() {
    }

    public static String snapshot(MinecraftServer server) throws IOException {
        return STORE.snapshot();
    }

    public static SaveResult save(MinecraftServer server, String snapshotJson) {
        return save(server, snapshotJson, null);
    }

    public static SaveResult save(MinecraftServer server, String snapshotJson, ServerPlayer actor) {
        try {
            String updated = STORE.save(snapshotJson, files -> validate(files, server.registryAccess()),
                    () -> activateConfigurations(server), new AdminConfigStore.ChangeObserver() {
                        private AuditLogService.ConfigurationChange change;
                        private Map<String, JsonElement> desired;

                        @Override
                        public void prepare(Map<String, JsonElement> before, Map<String, JsonElement> after) throws IOException {
                            if (reloadRequired || (appliedFiles != null && AuditLogService.configurationChanged(appliedFiles, before))) {
                                throw AdminMessages.io("save.external_changes", "Configuration files contain external changes that have not been applied. Run /cdshops reload before saving.");
                            }
                            desired = copyFiles(after);
                            change = AuditLogService.prepareConfigurationChange(before, after, "admin",
                                    actor == null ? "server" : actor.getGameProfile().getName(),
                                    actor == null ? null : actor.getUUID().toString());
                        }

                        @Override
                        public void complete(String outcome) {
                            if (change != null) change.complete(outcome);
                            if (outcome.equals("applied")) {
                                appliedFiles = desired;
                                reloadRequired = false;
                            } else if (outcome.equals("rollback_failed")) reloadRequired = true;
                        }
                    });
            return new SaveResult(true, AdminMessage.translated("save.success", "Configuration saved and applied."), updated);
        } catch (Exception exception) {
            CobbleDollarsCommandShopsMod.LOGGER.warn("Admin configuration save was rejected: {}", exception.getMessage());
            if (exception.getSuppressed().length > 0 || (exception.getCause() != null && exception.getCause().getSuppressed().length > 0)) {
                CobbleDollarsCommandShopsMod.LOGGER.error("Could not restore an admin configuration edit.", exception);
            }
            String current = "";
            try {
                current = STORE.snapshot();
            } catch (IOException ignored) {
            }
            return new SaveResult(false, AdminMessages.fromException(exception), current);
        }
    }

    /** Capture the successfully loaded files, rather than attributing future external edits to their reloader. */
    public static void rememberAppliedConfiguration() throws IOException {
        appliedFiles = DiskConfigSnapshot.read(ShopFiles.getConfigDirectory());
        reloadRequired = false;
    }

    public static void clearAppliedConfiguration() {
        appliedFiles = null;
        reloadRequired = false;
    }

    /** External file edits are attributed to their executor as a reload, never as an authored admin edit. */
    public static ShopRegistry.ReloadSummary reload(MinecraftServer server, String actorName, String actorUuid) throws IOException {
        Map<String, JsonElement> desired = DiskConfigSnapshot.read(ShopFiles.getConfigDirectory());
        validate(desired, server.registryAccess());
        AuditLogService.ConfigurationChange change = AuditLogService.prepareConfigurationChange(
                appliedFiles == null ? desired : appliedFiles, desired, "reload", actorName, actorUuid);
        try {
            ShopRegistry.ReloadSummary result = activateConfigurations(server);
            appliedFiles = copyFiles(desired);
            reloadRequired = false;
            change.complete("applied");
            return result;
        } catch (IOException | RuntimeException exception) {
            reloadRequired = true;
            change.complete("activation_failed");
            throw exception;
        }
    }

    private static Map<String, JsonElement> copyFiles(Map<String, JsonElement> files) {
        Map<String, JsonElement> copy = new TreeMap<>();
        files.forEach((path, value) -> copy.put(path, value.deepCopy()));
        return Map.copyOf(copy);
    }

    static void validate(Map<String, JsonElement> files, HolderLookup.Provider provider) throws IOException {
        Set<String> normalizedShopIds = new HashSet<>();
        for (var entry : files.entrySet()) {
            String name = entry.getKey();
            JsonElement json = entry.getValue();
            Path path = ShopFiles.getConfigDirectory().resolve(name);
            try {
                if (name.equals("audit.json")) {
                    requireObject(name, json);
                    AuditFiles.parseConfig(json.getAsJsonObject(), name);
                } else if (name.equals("commands.json")) {
                    requireObject(name, json);
                    CommandSettings.parseConfig(json.getAsJsonObject(), name);
                } else if (name.equals("feedback.json")) {
                    requireObject(name, json);
                    FeedbackFiles.parseConfig(json.getAsJsonObject(), name);
                } else if (name.equals("global_bank.json") || name.endsWith("/bank.json")) {
                    BankFiles.parseBankJson(path, json, provider);
                } else {
                    String shopId = ShopFiles.normalizeId(name.split("/")[1], "shop id");
                    if (!normalizedShopIds.add(shopId)) {
                        throw new IOException("Duplicate shop id after case normalization: " + shopId);
                    }
                    ShopFiles.parseShopJson(shopId, path, json, provider);
                }
            } catch (RuntimeException exception) {
                throw new IOException("Invalid configuration in " + name + ": " + exception.getMessage(), exception);
            }
        }
    }

    private static void requireObject(String name, JsonElement json) throws IOException {
        if (!json.isJsonObject()) {
            throw new IOException(name + " must be a JSON object.");
        }
    }

    private static ShopRegistry.ReloadSummary activateConfigurations(MinecraftServer server) throws IOException {
        // All configurations were parsed before the first file was changed.
        boolean previouslyAllowed = CommandSettings.allowPlayerOpen();
        List<String> previousShops = ShopRegistry.listShopIds();
        ShopRegistry.ReloadSummary summary = ShopRegistry.reload(server.registryAccess());
        ShopFeedbackService.reload();
        AuditLogService.reload();
        CommandSettings.reload();
        if (previouslyAllowed != CommandSettings.allowPlayerOpen()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                server.getCommands().sendCommands(player);
            }
        }
        for (String shop : previousShops) {
            if (ShopRegistry.getShop(shop) == null) {
                CommandShopSessions.closeShopSessions(server, shop,
                        Component.translatable("cobbledollarscommandshops.system.shop_missing_after_reload", shop));
            }
        }
        CommandShopSessions.refreshAllSessions(server);
        return summary;
    }

    public record SaveResult(boolean success, AdminMessage message, String snapshotJson) {
    }
}
