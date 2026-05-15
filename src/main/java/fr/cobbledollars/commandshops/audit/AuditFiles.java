package fr.cobbledollars.commandshops.audit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.shop.ShopFiles;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

public final class AuditFiles {
    private static final Path CONFIG_FILE = ShopFiles.getConfigDirectory().resolve("audit.json");

    private AuditFiles() {
    }

    public static Path getConfigFile() {
        return CONFIG_FILE;
    }

    public static void ensureDefaultConfigExists() throws IOException {
        Files.createDirectories(CONFIG_FILE.getParent());
        if (Files.exists(CONFIG_FILE)) {
            return;
        }
        Files.writeString(CONFIG_FILE, defaultConfigText());
    }

    public static AuditConfig loadConfig() throws IOException {
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                throw new IOException("Audit config '" + CONFIG_FILE + "' is empty.");
            }
            if (!root.isJsonObject()) {
                throw new IOException("Audit config '" + CONFIG_FILE + "' must be a JSON object.");
            }
            return parseConfig(root.getAsJsonObject(), "audit config '" + CONFIG_FILE + "'");
        } catch (RuntimeException exception) {
            throw new IOException("Audit config '" + CONFIG_FILE + "' could not be parsed.", exception);
        }
    }

    public static String defaultConfigText() {
        return """
                {
                  "enabled": true,
                  "flush_each_entry": true,
                  "events": {
                    "buy_success": true,
                    "buy_failure": false,
                    "sell_success": true,
                    "sell_failure": false,
                    "visibility_changed": true
                  }
                }
                """;
    }

    private static AuditConfig parseConfig(JsonObject root, String context) throws IOException {
        AuditConfig defaults = AuditConfig.defaults();
        boolean enabled = readBoolean(root, "enabled", defaults.enabled(), context);
        boolean flushEachEntry = readBoolean(root, "flush_each_entry", defaults.flushEachEntry(), context);
        EnumSet<AuditEventType> enabledEvents = parseEnabledEvents(root, defaults, context);
        return new AuditConfig(enabled, flushEachEntry, enabledEvents);
    }

    private static EnumSet<AuditEventType> parseEnabledEvents(JsonObject root, AuditConfig defaults, String context) throws IOException {
        EnumSet<AuditEventType> enabledEvents = EnumSet.noneOf(AuditEventType.class);
        JsonElement eventsElement = root.get("events");
        if (eventsElement == null || eventsElement.isJsonNull()) {
            enabledEvents.addAll(defaults.enabledEvents());
            return enabledEvents;
        }
        if (!eventsElement.isJsonObject()) {
            throw new IOException("Field 'events' in " + context + " must be an object.");
        }

        JsonObject eventsObject = eventsElement.getAsJsonObject();
        for (AuditEventType eventType : AuditEventType.values()) {
            boolean enabled = readBoolean(eventsObject, eventType.configKey(), eventType.enabledByDefault(), context + ", field 'events'");
            if (enabled) {
                enabledEvents.add(eventType);
            }
        }
        return enabledEvents;
    }

    private static boolean readBoolean(JsonObject object, String key, boolean defaultValue, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("Field '" + key + "' in " + context + " must be a boolean.");
        }
        return element.getAsBoolean();
    }
}
