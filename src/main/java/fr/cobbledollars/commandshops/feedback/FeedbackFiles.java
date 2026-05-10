package fr.cobbledollars.commandshops.feedback;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.shop.ShopFiles;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

public final class FeedbackFiles {
    private static final Path CONFIG_FILE = ShopFiles.getConfigDirectory().resolve("feedback.json");

    private FeedbackFiles() {
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

    public static FeedbackConfig loadConfig() throws IOException {
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                throw new IOException("Feedback config '" + CONFIG_FILE + "' is empty.");
            }
            if (!root.isJsonObject()) {
                throw new IOException("Feedback config '" + CONFIG_FILE + "' must be a JSON object.");
            }
            return parseConfig(root.getAsJsonObject(), "feedback config '" + CONFIG_FILE + "'");
        } catch (RuntimeException exception) {
            throw new IOException("Feedback config '" + CONFIG_FILE + "' could not be parsed.", exception);
        }
    }

    public static String defaultConfigText() {
        return """
                {
                  "buy_success": {
                    "channels": ["action_bar", "sound"],
                    "sound": {
                      "id": "minecraft:entity.experience_orb.pickup",
                      "volume": 0.25,
                      "pitch": 1.8
                    }
                  },
                  "buy_failure": {
                    "channels": ["action_bar", "sound"],
                    "sound": "minecraft:entity.villager.no"
                  },
                  "sell_success": {
                    "channels": ["action_bar", "sound"],
                    "sound": "minecraft:entity.experience_orb.pickup"
                  },
                  "sell_failure": {
                    "channels": ["action_bar", "sound"],
                    "sound": "minecraft:entity.villager.no"
                  },
                  "shop_denied": {
                    "channels": ["chat", "sound"],
                    "sound": "minecraft:entity.villager.no"
                  }
                }
                """;
    }

    private static FeedbackConfig parseConfig(JsonObject root, String context) throws IOException {
        FeedbackConfig defaults = FeedbackConfig.defaults();
        return new FeedbackConfig(
                parseEventConfig(root, "buy_success", defaults.buySuccess(), context),
                parseEventConfig(root, "buy_failure", defaults.buyFailure(), context),
                parseEventConfig(root, "sell_success", defaults.sellSuccess(), context),
                parseEventConfig(root, "sell_failure", defaults.sellFailure(), context),
                parseEventConfig(root, "shop_denied", defaults.shopDenied(), context)
        );
    }

    private static FeedbackConfig.EventConfig parseEventConfig(
            JsonObject root,
            String key,
            FeedbackConfig.EventConfig defaultConfig,
            String context
    ) throws IOException {
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return defaultConfig;
        }
        if (!element.isJsonObject()) {
            throw new IOException("Field '" + key + "' in " + context + " must be an object.");
        }

        JsonObject eventObject = element.getAsJsonObject();
        EnumSet<FeedbackChannel> channels = parseChannels(eventObject, key, defaultConfig.channels(), context);
        FeedbackConfig.SoundSpec sound = parseSound(eventObject, key, defaultConfig.sound(), context);
        return new FeedbackConfig.EventConfig(channels, sound);
    }

    private static EnumSet<FeedbackChannel> parseChannels(JsonObject object, String key, Set<FeedbackChannel> defaults, String context) throws IOException {
        JsonElement element = object.get("channels");
        if (element == null || element.isJsonNull()) {
            return defaults.isEmpty() ? EnumSet.noneOf(FeedbackChannel.class) : EnumSet.copyOf(defaults);
        }
        if (!element.isJsonArray()) {
            throw new IOException("Field '" + key + ".channels' in " + context + " must be an array.");
        }

        JsonArray array = element.getAsJsonArray();
        EnumSet<FeedbackChannel> channels = EnumSet.noneOf(FeedbackChannel.class);
        for (int index = 0; index < array.size(); index++) {
            JsonElement channelElement = array.get(index);
            if (!channelElement.isJsonPrimitive() || !channelElement.getAsJsonPrimitive().isString()) {
                throw new IOException("Channel #" + index + " in '" + key + ".channels' for " + context + " must be a string.");
            }
            channels.add(FeedbackChannel.parse(channelElement.getAsString(), context + ", field '" + key + ".channels'"));
        }
        return channels;
    }

    private static FeedbackConfig.SoundSpec parseSound(
            JsonObject object,
            String key,
            FeedbackConfig.SoundSpec defaultSound,
            String context
    ) throws IOException {
        JsonElement element = object.get("sound");
        if (element == null) {
            return defaultSound;
        }
        if (element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return new FeedbackConfig.SoundSpec(readSoundEvent(element.getAsString(), key, context), defaultVolume(defaultSound), defaultPitch(defaultSound));
        }
        if (!element.isJsonObject()) {
            throw new IOException("Field '" + key + ".sound' in " + context + " must be either a string, an object, or null.");
        }

        JsonObject soundObject = element.getAsJsonObject();
        String id = readRequiredString(soundObject, "id", context + ", field '" + key + ".sound'");
        float volume = readFloat(soundObject, "volume", defaultVolume(defaultSound), context + ", field '" + key + ".sound'");
        float pitch = readFloat(soundObject, "pitch", defaultPitch(defaultSound), context + ", field '" + key + ".sound'");
        return new FeedbackConfig.SoundSpec(readSoundEvent(id, key, context), volume, pitch);
    }

    private static SoundEvent readSoundEvent(String soundId, String key, String context) throws IOException {
        ResourceLocation resourceLocation;
        try {
            resourceLocation = ResourceLocation.parse(soundId);
        } catch (RuntimeException exception) {
            throw new IOException("Field '" + key + ".sound' in " + context + " is not a valid sound id: " + soundId, exception);
        }
        if (!BuiltInRegistries.SOUND_EVENT.containsKey(resourceLocation)) {
            throw new IOException("Field '" + key + ".sound' in " + context + " references an unknown sound: " + soundId);
        }
        return BuiltInRegistries.SOUND_EVENT.get(resourceLocation);
    }

    private static String readRequiredString(JsonObject object, String key, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null) {
            throw new IOException("Missing field '" + key + "' in " + context + ".");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Field '" + key + "' in " + context + " must be a string.");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw new IOException("Field '" + key + "' in " + context + " cannot be empty.");
        }
        return value;
    }

    private static float readFloat(JsonObject object, String key, float defaultValue, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive()) {
            throw new IOException("Field '" + key + "' in " + context + " must be a number.");
        }
        try {
            return Float.parseFloat(element.getAsString());
        } catch (NumberFormatException exception) {
            throw new IOException("Field '" + key + "' in " + context + " is not a valid number.", exception);
        }
    }

    private static float defaultVolume(FeedbackConfig.SoundSpec sound) {
        return sound == null ? 1.0F : sound.volume();
    }

    private static float defaultPitch(FeedbackConfig.SoundSpec sound) {
        return sound == null ? 1.0F : sound.pitch();
    }
}
