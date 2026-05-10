package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.math.BigInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class ConfigParsing {
    private ConfigParsing() {
    }

    static JsonArray readRequiredArray(JsonObject object, String key, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null) {
            throw new IOException("Missing field '" + key + "' in " + context + ".");
        }
        if (!element.isJsonArray()) {
            throw new IOException("Field '" + key + "' in " + context + " must be an array.");
        }
        return element.getAsJsonArray();
    }

    static JsonObject readRequiredObject(JsonObject object, String key, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null) {
            throw new IOException("Missing field '" + key + "' in " + context + ".");
        }
        if (!element.isJsonObject()) {
            throw new IOException("Field '" + key + "' in " + context + " must be an object.");
        }
        return element.getAsJsonObject();
    }

    static String readRequiredString(JsonObject object, String key, String context) throws IOException {
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

    static String readOptionalString(JsonObject object, String key, String defaultValue, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Field '" + key + "' in " + context + " must be a string.");
        }
        return element.getAsString();
    }

    static BigInteger readBigInteger(JsonObject object, String key, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null) {
            throw new IOException("Missing field '" + key + "' in " + context + ".");
        }
        if (!element.isJsonPrimitive()) {
            throw new IOException("Field '" + key + "' in " + context + " must be a number or string.");
        }

        try {
            return new BigInteger(element.getAsString());
        } catch (NumberFormatException exception) {
            throw new IOException("Field '" + key + "' in " + context + " is not a valid integer.", exception);
        }
    }

    static int readInt(JsonObject object, String key, int defaultValue, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive()) {
            throw new IOException("Field '" + key + "' in " + context + " must be an integer.");
        }

        try {
            return Integer.parseInt(element.getAsString());
        } catch (NumberFormatException exception) {
            throw new IOException("Field '" + key + "' in " + context + " is not a valid integer.", exception);
        }
    }

    static long readLong(JsonObject object, String key, long defaultValue, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive()) {
            throw new IOException("Field '" + key + "' in " + context + " must be an integer.");
        }

        try {
            return Long.parseLong(element.getAsString());
        } catch (NumberFormatException exception) {
            throw new IOException("Field '" + key + "' in " + context + " is not a valid integer.", exception);
        }
    }

    static ResourceLocation readResourceLocation(String value, String label, String context) throws IOException {
        try {
            return ResourceLocation.parse(value);
        } catch (RuntimeException exception) {
            throw new IOException("Field '" + label + "' in " + context + " is not a valid resource location: " + value, exception);
        }
    }

    static Item readItem(JsonObject object, String key, String context) throws IOException {
        String itemId = readRequiredString(object, key, context);
        ResourceLocation resourceLocation = readResourceLocation(itemId, key, context);
        if (!BuiltInRegistries.ITEM.containsKey(resourceLocation)) {
            throw new IOException("Field '" + key + "' in " + context + " references an unknown item: " + itemId);
        }
        return BuiltInRegistries.ITEM.get(resourceLocation);
    }

    static ItemStack readItemStack(JsonObject object, HolderLookup.Provider provider, String context) throws IOException {
        String stackDefinition = readOptionalString(object, "stack", null, context);
        JsonElement itemElement = object.get("item");
        boolean hasItem = itemElement != null && !itemElement.isJsonNull();
        if ((stackDefinition == null) == !hasItem) {
            throw new IOException("Exactly one of 'item' or 'stack' must be set in " + context + ".");
        }

        int count = readInt(object, "count", 1, context);
        if (count <= 0) {
            throw new IOException("Field 'count' in " + context + " must be greater than 0.");
        }

        if (stackDefinition != null) {
            try {
                ItemParser.ItemResult parsed = new ItemParser(provider).parse(new StringReader(stackDefinition));
                return new ItemStack(parsed.item(), count, parsed.components());
            } catch (CommandSyntaxException exception) {
                throw new IOException("Field 'stack' in " + context + " is invalid: " + stackDefinition, exception);
            }
        }

        return new ItemStack(readItem(object, "item", context), count);
    }
}
