package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
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

    static ItemMatchExpression readItemMatchExpression(JsonObject object, HolderLookup.Provider provider, String context) throws IOException {
        JsonObject matchObject = readRequiredObject(object, "match", context);
        List<ItemMatchAtom> include = readMatchAtoms(matchObject, "include", provider, context, true);
        List<ItemMatchAtom> exclude = readMatchAtoms(matchObject, "exclude", provider, context, false);
        return new ItemMatchExpression(include, exclude);
    }

    static void writeMatchExpression(JsonObject object, ItemMatchExpression expression) {
        JsonObject matchObject = new JsonObject();
        matchObject.add("include", writeMatchAtoms(expression.include()));
        if (!expression.exclude().isEmpty()) {
            matchObject.add("exclude", writeMatchAtoms(expression.exclude()));
        }
        object.add("match", matchObject);
    }

    private static JsonArray writeMatchAtoms(List<ItemMatchAtom> atoms) {
        JsonArray array = new JsonArray();
        for (ItemMatchAtom atom : atoms) {
            JsonObject atomObject = new JsonObject();
            if (atom instanceof ItemMatchAtom.ExactStack stackAtom) {
                atomObject.addProperty("stack", stackAtom.sourceValue());
            } else if (atom instanceof ItemMatchAtom.ExactItem itemAtom) {
                atomObject.addProperty("item", itemAtom.sourceValue());
            } else if (atom instanceof ItemMatchAtom.Tag tagAtom) {
                atomObject.addProperty("tag", tagAtom.sourceValue());
            } else if (atom instanceof ItemMatchAtom.Mod modAtom) {
                atomObject.addProperty("mod", modAtom.sourceValue());
            } else {
                throw new IllegalStateException("Unsupported item match atom type: " + atom.getClass().getName());
            }
            array.add(atomObject);
        }
        return array;
    }

    private static List<ItemMatchAtom> readMatchAtoms(
            JsonObject matchObject,
            String key,
            HolderLookup.Provider provider,
            String context,
            boolean required
    ) throws IOException {
        JsonElement element = matchObject.get(key);
        if (element == null || element.isJsonNull()) {
            if (required) {
                throw new IOException("Field 'match." + key + "' in " + context + " must be a non-empty array.");
            }
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IOException("Field 'match." + key + "' in " + context + " must be an array.");
        }

        JsonArray array = element.getAsJsonArray();
        if (required && array.isEmpty()) {
            throw new IOException("Field 'match." + key + "' in " + context + " cannot be empty.");
        }

        ArrayList<ItemMatchAtom> atoms = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement atomElement = array.get(index);
            if (!atomElement.isJsonObject()) {
                throw new IOException("Entry #" + index + " in 'match." + key + "' of " + context + " must be an object.");
            }
            atoms.add(readMatchAtom(atomElement.getAsJsonObject(), provider, context + ", match." + key + "[" + index + "]"));
        }
        return List.copyOf(atoms);
    }

    private static ItemMatchAtom readMatchAtom(JsonObject object, HolderLookup.Provider provider, String context) throws IOException {
        String stackDefinition = readOptionalString(object, "stack", null, context);
        String itemDefinition = readOptionalString(object, "item", null, context);
        String tagDefinition = readOptionalString(object, "tag", null, context);
        String modDefinition = readOptionalString(object, "mod", null, context);
        int fieldCount = (stackDefinition != null ? 1 : 0)
                + (itemDefinition != null ? 1 : 0)
                + (tagDefinition != null ? 1 : 0)
                + (modDefinition != null ? 1 : 0);
        if (fieldCount != 1) {
            throw new IOException("Exactly one of 'item', 'stack', 'tag', or 'mod' must be set in " + context + ".");
        }

        if (stackDefinition != null) {
            try {
                ItemParser.ItemResult parsed = new ItemParser(provider).parse(new StringReader(stackDefinition));
                return new ItemMatchAtom.ExactStack(new ItemStack(parsed.item(), 1, parsed.components()), stackDefinition);
            } catch (CommandSyntaxException exception) {
                throw new IOException("Field 'stack' in " + context + " is invalid: " + stackDefinition, exception);
            }
        }

        if (itemDefinition != null) {
            ResourceLocation resourceLocation = readResourceLocation(itemDefinition, "item", context);
            if (!BuiltInRegistries.ITEM.containsKey(resourceLocation)) {
                throw new IOException("Field 'item' in " + context + " references an unknown item: " + itemDefinition);
            }
            return new ItemMatchAtom.ExactItem(BuiltInRegistries.ITEM.get(resourceLocation), itemDefinition);
        }

        if (tagDefinition != null) {
            String normalizedTag = normalizeTagValue(tagDefinition);
            ResourceLocation tagLocation = readResourceLocation(normalizedTag, "tag", context);
            TagKey<Item> tagKey = TagKey.create(Registries.ITEM, tagLocation);
            HolderLookup.RegistryLookup<Item> itemLookup = provider.lookupOrThrow(Registries.ITEM);
            HolderSet.Named<Item> holders = itemLookup.get(tagKey)
                    .orElseThrow(() -> new IOException("Field 'tag' in " + context + " references an unknown item tag: " + normalizedTag));
            ArrayList<Item> items = new ArrayList<>();
            for (Holder<Item> holder : holders) {
                items.add(holder.value());
            }
            items.sort(Comparator.comparing(item -> String.valueOf(BuiltInRegistries.ITEM.getKey(item))));
            return new ItemMatchAtom.Tag(tagKey, normalizedTag, items);
        }

        String normalizedMod = normalizeModId(modDefinition, context);
        ArrayList<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId.getNamespace().equals(normalizedMod)) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            throw new IOException("Field 'mod' in " + context + " does not resolve to any registered item namespace: " + normalizedMod);
        }
        items.sort(Comparator.comparing(item -> String.valueOf(BuiltInRegistries.ITEM.getKey(item))));
        return new ItemMatchAtom.Mod(normalizedMod, normalizedMod, items);
    }

    private static String normalizeTagValue(String rawTag) {
        if (rawTag == null) {
            return null;
        }
        return rawTag.startsWith("#") ? rawTag.substring(1) : rawTag;
    }

    private static String normalizeModId(String rawModId, String context) throws IOException {
        if (rawModId == null) {
            return null;
        }
        String normalized = rawModId.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]+")) {
            throw new IOException("Field 'mod' in " + context + " is invalid: " + rawModId);
        }
        return normalized;
    }
}
