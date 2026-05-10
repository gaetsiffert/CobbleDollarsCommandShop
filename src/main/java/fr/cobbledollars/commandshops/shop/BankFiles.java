package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Bank;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class BankFiles {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final String GLOBAL_BANK_FILENAME = "global_bank.json";
    private static final String LOCAL_BANK_FILENAME = "bank.json";

    private BankFiles() {
    }

    public static Path getGlobalBankFile() {
        return ShopFiles.getConfigDirectory().resolve(GLOBAL_BANK_FILENAME);
    }

    public static Path resolveLocalBankFile(Path shopFolder) {
        return shopFolder.resolve(LOCAL_BANK_FILENAME);
    }

    public static void ensureExampleGlobalBankExists() throws IOException {
        Path bankFile = getGlobalBankFile();
        Files.createDirectories(bankFile.getParent());
        if (Files.exists(bankFile)) {
            return;
        }

        writeBank(bankFile, createExampleBank());
    }

    public static Bank loadGlobalBank() throws IOException {
        return loadBankFile(getGlobalBankFile());
    }

    public static Bank loadBankFile(Path bankFile) throws IOException {
        Files.createDirectories(bankFile.getParent());
        try (Reader reader = Files.newBufferedReader(bankFile)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                throw new IOException("Bank file '" + bankFile + "' is empty.");
            }

            if (root.isJsonArray()) {
                return parseOffersArray(root.getAsJsonArray(), "bank file '" + bankFile + "'");
            }
            if (root.isJsonObject()) {
                JsonObject rootObject = root.getAsJsonObject();
                JsonArray offersArray = readRequiredArray(rootObject, "offers", "bank file '" + bankFile + "'");
                return parseOffersArray(offersArray, "bank file '" + bankFile + "'");
            }

            throw new IOException("Bank file '" + bankFile + "' must be a JSON object or array.");
        } catch (RuntimeException exception) {
            throw new IOException("Bank file '" + bankFile + "' could not be parsed.", exception);
        }
    }

    public static Bank copyBank(Bank bank) {
        ArrayList<Offer> offers = new ArrayList<>();
        for (Offer offer : bank) {
            offers.add(new Offer(offer.getItem().copy(), offer.getPrice(), offer.getStock()));
        }
        return new Bank(offers);
    }

    private static Bank parseOffersArray(JsonArray offersArray, String context) throws IOException {
        List<Offer> offers = new ArrayList<>();
        for (int offerIndex = 0; offerIndex < offersArray.size(); offerIndex++) {
            JsonElement offerElement = offersArray.get(offerIndex);
            if (!offerElement.isJsonObject()) {
                throw new IOException("Offer #" + offerIndex + " in " + context + " must be an object.");
            }

            JsonObject offerObject = offerElement.getAsJsonObject();
            Item item = readItem(offerObject, "item", context + ", offer #" + offerIndex);
            int count = readInt(offerObject, "count", 1, context + ", offer #" + offerIndex);
            if (count <= 0) {
                throw new IOException("Field 'count' in " + context + ", offer #" + offerIndex + " must be greater than 0.");
            }

            BigInteger price = readBigInteger(offerObject, "price", context + ", offer #" + offerIndex);
            if (price.signum() < 0) {
                throw new IOException("Field 'price' in " + context + ", offer #" + offerIndex + " must be positive or zero.");
            }

            offers.add(new Offer(new ItemStack(item, count), price, -1));
        }
        return new Bank(new ArrayList<>(offers));
    }

    private static void writeBank(Path bankFile, Bank bank) throws IOException {
        Files.createDirectories(bankFile.getParent());
        try (Writer writer = Files.newBufferedWriter(bankFile)) {
            GSON.toJson(toJson(bank), writer);
        }
    }

    private static JsonElement toJson(Bank bank) {
        JsonObject root = new JsonObject();
        JsonArray offersArray = new JsonArray();
        for (Offer offer : bank) {
            JsonObject offerObject = new JsonObject();
            offerObject.addProperty("item", BuiltInRegistries.ITEM.getKey(offer.getItem().getItem()).toString());
            offerObject.addProperty("count", offer.getItem().getCount());
            offerObject.addProperty("price", offer.getPrice());
            offersArray.add(offerObject);
        }
        root.add("offers", offersArray);
        return root;
    }

    private static Bank createExampleBank() {
        ArrayList<Offer> offers = new ArrayList<>();
        offers.add(new Offer(new ItemStack(Items.IRON_INGOT, 1), BigInteger.valueOf(8L), -1));
        offers.add(new Offer(new ItemStack(Items.GOLD_INGOT, 1), BigInteger.valueOf(12L), -1));
        offers.add(new Offer(new ItemStack(Items.DIAMOND, 1), BigInteger.valueOf(75L), -1));
        return new Bank(offers);
    }

    private static JsonArray readRequiredArray(JsonObject object, String key, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null) {
            throw new IOException("Missing field '" + key + "' in " + context + ".");
        }
        if (!element.isJsonArray()) {
            throw new IOException("Field '" + key + "' in " + context + " must be an array.");
        }
        return element.getAsJsonArray();
    }

    private static Item readItem(JsonObject object, String key, String context) throws IOException {
        String itemId = readRequiredString(object, key, context);
        ResourceLocation resourceLocation;
        try {
            resourceLocation = ResourceLocation.parse(itemId);
        } catch (RuntimeException exception) {
            throw new IOException("Field '" + key + "' in " + context + " is not a valid item id: " + itemId, exception);
        }

        if (!BuiltInRegistries.ITEM.containsKey(resourceLocation)) {
            throw new IOException("Field '" + key + "' in " + context + " references an unknown item: " + itemId);
        }

        return BuiltInRegistries.ITEM.get(resourceLocation);
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

    private static BigInteger readBigInteger(JsonObject object, String key, String context) throws IOException {
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

    private static int readInt(JsonObject object, String key, int defaultValue, String context) throws IOException {
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
}
