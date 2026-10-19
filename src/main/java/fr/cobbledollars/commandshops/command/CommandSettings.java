package fr.cobbledollars.commandshops.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.shop.ShopFiles;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server-side command access, applied only at startup or after a configuration save/reload. */
public final class CommandSettings {
    private static final Path CONFIG_FILE = ShopFiles.getConfigDirectory().resolve("commands.json");
    private static volatile boolean allowPlayerOpen = true;

    private CommandSettings() { }

    public static Path getConfigFile() {
        return CONFIG_FILE;
    }

    public static String defaultConfigText() {
        return """
                {
                  "allow_player_open": true
                }
                """;
    }

    public static void initialize() throws IOException {
        Files.createDirectories(CONFIG_FILE.getParent());
        if (!Files.exists(CONFIG_FILE)) Files.writeString(CONFIG_FILE, defaultConfigText());
        reload();
    }

    public static void reload() throws IOException {
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) throw new IOException(CONFIG_FILE + " must be a JSON object.");
            allowPlayerOpen = parseConfig(root.getAsJsonObject(), CONFIG_FILE.toString());
        } catch (RuntimeException exception) {
            throw new IOException("Command config '" + CONFIG_FILE + "' could not be parsed.", exception);
        }
    }

    public static boolean parseConfig(JsonObject root, String context) throws IOException {
        JsonElement value = root.get("allow_player_open");
        if (value == null || value.isJsonNull()) return true;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("Field 'allow_player_open' in " + context + " must be a boolean.");
        }
        return value.getAsBoolean();
    }

    public static boolean allowPlayerOpen() {
        return allowPlayerOpen;
    }

    public static void clear() {
        allowPlayerOpen = true;
    }
}
