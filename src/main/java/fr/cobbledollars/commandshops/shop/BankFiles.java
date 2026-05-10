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
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.item.ItemParser;
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

    public static void ensureDefaultGlobalBankExists() throws IOException {
        Path bankFile = getGlobalBankFile();
        Files.createDirectories(bankFile.getParent());
        if (Files.exists(bankFile)) {
            return;
        }

        writeBankDefinition(bankFile, createDefaultGlobalBankDefinition());
    }

    public static BankDefinition loadGlobalBank(HolderLookup.Provider provider) throws IOException {
        return loadBankFile(getGlobalBankFile(), provider);
    }

    public static BankDefinition loadBankFile(Path bankFile, HolderLookup.Provider provider) throws IOException {
        Files.createDirectories(bankFile.getParent());
        try (Reader reader = Files.newBufferedReader(bankFile)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                throw new IOException("Bank file '" + bankFile + "' is empty.");
            }

            if (root.isJsonArray()) {
                return parseFlatBankArray(bankFile, root.getAsJsonArray(), provider);
            }
            if (root.isJsonObject()) {
                return parseBankObject(bankFile, root.getAsJsonObject(), provider);
            }

            throw new IOException("Bank file '" + bankFile + "' must be a JSON object or array.");
        } catch (RuntimeException exception) {
            throw new IOException("Bank file '" + bankFile + "' could not be parsed.", exception);
        }
    }

    private static BankDefinition parseFlatBankArray(Path bankFile, JsonArray offersArray, HolderLookup.Provider provider) throws IOException {
        List<BankOfferDefinition> offers = parseOffersArray(offersArray, "bank file '" + bankFile + "'", provider);
        return new BankDefinition(List.of(new BankCategoryDefinition("Default", offers, ConditionSet.NONE)), ConditionSet.NONE, bankFile);
    }

    private static BankDefinition parseBankObject(Path bankFile, JsonObject rootObject, HolderLookup.Provider provider) throws IOException {
        String context = "bank file '" + bankFile + "'";
        ConditionSet bankConditions = ConditionSet.readOptional(rootObject, context);
        boolean hasOffers = rootObject.has("offers");
        boolean hasCategories = rootObject.has("categories");

        if (hasOffers == hasCategories) {
            throw new IOException("Bank file '" + bankFile + "' must define exactly one of 'offers' or 'categories'.");
        }

        if (hasOffers) {
            JsonArray offersArray = ConfigParsing.readRequiredArray(rootObject, "offers", context);
            List<BankOfferDefinition> offers = parseOffersArray(offersArray, context, provider);
            return new BankDefinition(List.of(new BankCategoryDefinition("Default", offers, ConditionSet.NONE)), bankConditions, bankFile);
        }

        JsonArray categoriesArray = ConfigParsing.readRequiredArray(rootObject, "categories", context);
        List<BankCategoryDefinition> categories = new ArrayList<>();
        for (int categoryIndex = 0; categoryIndex < categoriesArray.size(); categoryIndex++) {
            JsonElement categoryElement = categoriesArray.get(categoryIndex);
            if (!categoryElement.isJsonObject()) {
                throw new IOException("Category #" + categoryIndex + " in " + context + " must be an object.");
            }

            JsonObject categoryObject = categoryElement.getAsJsonObject();
            String categoryContext = context + ", category #" + categoryIndex;
            String categoryName = ConfigParsing.readRequiredString(categoryObject, "name", categoryContext);
            ConditionSet categoryConditions = ConditionSet.readOptional(categoryObject, categoryContext);
            JsonArray offersArray = ConfigParsing.readRequiredArray(categoryObject, "offers", categoryContext);
            categories.add(new BankCategoryDefinition(categoryName, parseOffersArray(offersArray, categoryContext, provider), categoryConditions));
        }

        return new BankDefinition(categories, bankConditions, bankFile);
    }

    private static List<BankOfferDefinition> parseOffersArray(JsonArray offersArray, String context, HolderLookup.Provider provider) throws IOException {
        List<BankOfferDefinition> offers = new ArrayList<>();
        for (int offerIndex = 0; offerIndex < offersArray.size(); offerIndex++) {
            JsonElement offerElement = offersArray.get(offerIndex);
            if (!offerElement.isJsonObject()) {
                throw new IOException("Offer #" + offerIndex + " in " + context + " must be an object.");
            }

            JsonObject offerObject = offerElement.getAsJsonObject();
            String offerContext = context + ", offer #" + offerIndex;
            ItemStack stack = readBankItemStack(offerObject, provider, offerContext);
            BigInteger price = ConfigParsing.readBigInteger(offerObject, "price", offerContext);
            if (price.signum() < 0) {
                throw new IOException("Field 'price' in " + offerContext + " must be positive or zero.");
            }

            ConditionSet conditions = ConditionSet.readOptional(offerObject, offerContext);
            offers.add(new BankOfferDefinition(stack, price, conditions));
        }
        return List.copyOf(offers);
    }

    private static ItemStack readBankItemStack(JsonObject object, HolderLookup.Provider provider, String context) throws IOException {
        if (object.has("count") && !object.get("count").isJsonNull()) {
            throw new IOException("Field 'count' is not supported in bank offers anymore. Remove it from " + context + ".");
        }

        String stackDefinition = ConfigParsing.readOptionalString(object, "stack", null, context);
        JsonElement itemElement = object.get("item");
        boolean hasItem = itemElement != null && !itemElement.isJsonNull();
        if ((stackDefinition == null) == !hasItem) {
            throw new IOException("Exactly one of 'item' or 'stack' must be set in " + context + ".");
        }

        if (stackDefinition != null) {
            try {
                ItemParser.ItemResult parsed = new ItemParser(provider).parse(new StringReader(stackDefinition));
                return new ItemStack(parsed.item(), 1, parsed.components());
            } catch (CommandSyntaxException exception) {
                throw new IOException("Field 'stack' in " + context + " is invalid: " + stackDefinition, exception);
            }
        }

        return new ItemStack(ConfigParsing.readItem(object, "item", context), 1);
    }

    private static void writeBankDefinition(Path bankFile, BankDefinition bank) throws IOException {
        Files.createDirectories(bankFile.getParent());
        try (Writer writer = Files.newBufferedWriter(bankFile)) {
            GSON.toJson(toJson(bank), writer);
        }
    }

    private static JsonElement toJson(BankDefinition bank) {
        JsonObject root = new JsonObject();
        JsonArray categoriesArray = new JsonArray();
        for (BankCategoryDefinition category : bank.categories()) {
            JsonObject categoryObject = new JsonObject();
            categoryObject.addProperty("name", category.name());
            JsonArray offersArray = new JsonArray();
            for (BankOfferDefinition offer : category.offers()) {
                JsonObject offerObject = new JsonObject();
                offerObject.addProperty("item", BuiltInRegistries.ITEM.getKey(offer.createItemStack().getItem()).toString());
                offerObject.addProperty("price", offer.price());
                offersArray.add(offerObject);
            }
            categoryObject.add("offers", offersArray);
            categoriesArray.add(categoryObject);
        }
        root.add("categories", categoriesArray);
        return root;
    }

    private static BankDefinition createDefaultGlobalBankDefinition() {
        List<BankCategoryDefinition> categories = List.of(
                new BankCategoryDefinition("Ores", List.of(
                        new BankOfferDefinition(new ItemStack(Items.COAL, 1), BigInteger.valueOf(3L), ConditionSet.NONE),
                        new BankOfferDefinition(new ItemStack(Items.IRON_INGOT, 1), BigInteger.valueOf(8L), ConditionSet.NONE),
                        new BankOfferDefinition(new ItemStack(Items.GOLD_INGOT, 1), BigInteger.valueOf(12L), ConditionSet.NONE),
                        new BankOfferDefinition(new ItemStack(Items.DIAMOND, 1), BigInteger.valueOf(75L), ConditionSet.NONE)
                ), ConditionSet.NONE),
                new BankCategoryDefinition("Crops", List.of(
                        new BankOfferDefinition(new ItemStack(Items.WHEAT, 1), BigInteger.valueOf(2L), ConditionSet.NONE),
                        new BankOfferDefinition(new ItemStack(Items.CARROT, 1), BigInteger.valueOf(3L), ConditionSet.NONE),
                        new BankOfferDefinition(new ItemStack(Items.POTATO, 1), BigInteger.valueOf(3L), ConditionSet.NONE)
                ), ConditionSet.NONE),
                new BankCategoryDefinition("Mob Drops", List.of(
                        new BankOfferDefinition(new ItemStack(Items.ROTTEN_FLESH, 1), BigInteger.valueOf(1L), ConditionSet.NONE),
                        new BankOfferDefinition(new ItemStack(Items.BONE, 1), BigInteger.valueOf(2L), ConditionSet.NONE),
                        new BankOfferDefinition(new ItemStack(Items.STRING, 1), BigInteger.valueOf(2L), ConditionSet.NONE)
                ), ConditionSet.NONE)
        );
        return new BankDefinition(categories, ConditionSet.NONE, getGlobalBankFile());
    }
}
