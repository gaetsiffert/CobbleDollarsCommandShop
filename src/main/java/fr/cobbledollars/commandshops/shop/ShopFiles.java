package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;

public final class ShopFiles {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final String SHOP_EXTENSION = ".json";
    private static final String EXAMPLE_FILENAME = "example.json";
    private static final Path SHOP_DIRECTORY = FMLPaths.CONFIGDIR.get().resolve(CobbleDollarsCommandShopsMod.MODID).resolve("shops");

    private ShopFiles() {
    }

    public static Path getShopDirectory() {
        return SHOP_DIRECTORY;
    }

    public static void ensureExampleShopExists() throws IOException {
        ensureShopDirectory();
        Path exampleShopFile = SHOP_DIRECTORY.resolve(EXAMPLE_FILENAME);
        if (Files.exists(exampleShopFile)) {
            return;
        }

        writeShop(exampleShopFile, createExampleShop());
    }

    public static ShopDefinition loadShop(String shopId) throws IOException {
        Map<String, ShopDefinition> shops = loadAllShops();
        ShopDefinition shop = shops.get(normalizeId(shopId, "shop id"));
        if (shop == null) {
            throw new IOException("Shop '" + shopId + "' was not found in " + SHOP_DIRECTORY + ".");
        }
        return shop;
    }

    public static List<String> listShopIds() throws IOException {
        return loadAllShops().keySet().stream().sorted().toList();
    }

    public static Map<String, ShopDefinition> loadAllShops() throws IOException {
        ensureShopDirectory();
        java.util.LinkedHashMap<String, ShopDefinition> shops = new java.util.LinkedHashMap<>();

        try (Stream<Path> paths = Files.walk(SHOP_DIRECTORY)) {
            List<Path> shopFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(SHOP_EXTENSION))
                    .sorted()
                    .toList();

            for (Path shopFile : shopFiles) {
                ShopDefinition shop = parseShopFile(shopFile);
                ShopDefinition existing = shops.putIfAbsent(shop.id(), shop);
                if (existing != null) {
                    throw new IOException("Duplicate shop id '" + shop.id() + "' in " + existing.sourceFile() + " and " + shop.sourceFile() + ".");
                }
            }
        }

        return Map.copyOf(shops);
    }

    private static ShopDefinition parseShopFile(Path shopFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(shopFile)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                throw new IOException("Shop file '" + shopFile + "' is empty.");
            }

            if (root.isJsonArray()) {
                return parseLegacyShop(shopFile, root.getAsJsonArray());
            }
            if (root.isJsonObject()) {
                return parseModernShop(shopFile, root.getAsJsonObject());
            }

            throw new IOException("Shop file '" + shopFile + "' must be a JSON object.");
        } catch (RuntimeException exception) {
            throw new IOException("Shop file '" + shopFile + "' could not be parsed.", exception);
        }
    }

    private static ShopDefinition parseModernShop(Path shopFile, JsonObject rootObject) throws IOException {
        String context = "shop file '" + shopFile + "'";
        String shopId = readRequiredId(rootObject, "id", context);
        JsonArray categoriesArray = readRequiredArray(rootObject, "categories", context);

        Set<String> seenOfferIds = new HashSet<>();
        List<ShopCategoryDefinition> categories = new ArrayList<>();
        for (int categoryIndex = 0; categoryIndex < categoriesArray.size(); categoryIndex++) {
            JsonElement categoryElement = categoriesArray.get(categoryIndex);
            if (!categoryElement.isJsonObject()) {
                throw new IOException("Category #" + categoryIndex + " in " + context + " must be an object.");
            }

            JsonObject categoryObject = categoryElement.getAsJsonObject();
            String categoryName = readRequiredString(categoryObject, "name", "shop '" + shopId + "', category #" + categoryIndex);
            JsonArray offersArray = readRequiredArray(categoryObject, "offers", "shop '" + shopId + "', category '" + categoryName + "'");

            List<ShopOfferDefinition> offers = new ArrayList<>();
            for (int offerIndex = 0; offerIndex < offersArray.size(); offerIndex++) {
                ShopOfferDefinition offer = parseOffer(offersArray.get(offerIndex), shopId, categoryName, offerIndex);
                if (!seenOfferIds.add(offer.id())) {
                    throw new IOException("Duplicate offer id '" + offer.id() + "' in shop '" + shopId + "'.");
                }
                offers.add(offer);
            }
            categories.add(new ShopCategoryDefinition(categoryName, offers));
        }

        return new ShopDefinition(shopId, categories, shopFile);
    }

    private static ShopDefinition parseLegacyShop(Path shopFile, JsonArray categoryArray) throws IOException {
        String legacyId = normalizeId(stripExtension(shopFile.getFileName().toString()), "legacy shop filename");
        Set<String> seenOfferIds = new HashSet<>();
        List<ShopCategoryDefinition> categories = new ArrayList<>();

        for (int categoryIndex = 0; categoryIndex < categoryArray.size(); categoryIndex++) {
            JsonElement categoryElement = categoryArray.get(categoryIndex);
            if (!categoryElement.isJsonObject()) {
                throw new IOException("Category #" + categoryIndex + " in legacy shop '" + legacyId + "' must be an object.");
            }

            JsonObject categoryObject = categoryElement.getAsJsonObject();
            if (categoryObject.entrySet().isEmpty()) {
                throw new IOException("Category #" + categoryIndex + " in legacy shop '" + legacyId + "' is empty.");
            }

            for (Map.Entry<String, JsonElement> entry : categoryObject.entrySet()) {
                String categoryName = entry.getKey();
                if (!entry.getValue().isJsonArray()) {
                    throw new IOException("Category '" + categoryName + "' in legacy shop '" + legacyId + "' must contain an array of offers.");
                }

                JsonArray offersArray = entry.getValue().getAsJsonArray();
                List<ShopOfferDefinition> offers = new ArrayList<>();
                for (int offerIndex = 0; offerIndex < offersArray.size(); offerIndex++) {
                    ShopOfferDefinition offer = parseLegacyOffer(offersArray.get(offerIndex), legacyId, categoryName, offerIndex);
                    if (!seenOfferIds.add(offer.id())) {
                        throw new IOException("Duplicate legacy offer id '" + offer.id() + "' in shop '" + legacyId + "'. Add explicit ids or rename the duplicated item entries.");
                    }
                    offers.add(offer);
                }
                categories.add(new ShopCategoryDefinition(categoryName, offers));
            }
        }

        return new ShopDefinition(legacyId, categories, shopFile);
    }

    private static ShopOfferDefinition parseOffer(JsonElement offerElement, String shopId, String categoryName, int offerIndex) throws IOException {
        String context = "shop '" + shopId + "', category '" + categoryName + "', offer #" + offerIndex;
        if (!offerElement.isJsonObject()) {
            throw new IOException("Each offer in " + context + " must be an object.");
        }

        JsonObject offerObject = offerElement.getAsJsonObject();
        String offerId = readRequiredId(offerObject, "id", context);
        return parseOfferBody(offerObject, shopId, categoryName, offerIndex, offerId);
    }

    private static ShopOfferDefinition parseLegacyOffer(JsonElement offerElement, String shopId, String categoryName, int offerIndex) throws IOException {
        String context = "legacy shop '" + shopId + "', category '" + categoryName + "', offer #" + offerIndex;
        if (!offerElement.isJsonObject()) {
            throw new IOException("Each offer in " + context + " must be an object.");
        }

        JsonObject offerObject = offerElement.getAsJsonObject();
        String derivedId = deriveLegacyOfferId(offerObject, offerIndex, context);
        return parseOfferBody(offerObject, shopId, categoryName, offerIndex, derivedId);
    }

    private static ShopOfferDefinition parseOfferBody(JsonObject offerObject, String shopId, String categoryName, int offerIndex, String offerId) throws IOException {
        String context = "shop '" + shopId + "', category '" + categoryName + "', offer '" + offerId + "'";
        Item item = readItem(offerObject, "item", context);
        int count = readInt(offerObject, "count", 1, context);
        if (count <= 0) {
            throw new IOException("Field 'count' in " + context + " must be greater than 0.");
        }

        BigInteger price = readBigInteger(offerObject, "price", context);
        if (price.signum() < 0) {
            throw new IOException("Field 'price' in " + context + " must be positive or zero.");
        }

        int stock = readInt(offerObject, "stock", -1, context);
        if (stock < -1) {
            throw new IOException("Field 'stock' in " + context + " must be -1 or greater.");
        }

        RestockRule restockRule = readRestockRule(offerObject, context);
        if (restockRule != null && stock < 0) {
            throw new IOException("Field 'stock' in " + context + " must be set when 'restock' is used.");
        }

        return new ShopOfferDefinition(offerId, new ItemStack(item, count), price, stock, restockRule);
    }

    private static RestockRule readRestockRule(JsonObject object, String context) throws IOException {
        JsonElement restockElement = object.get("restock");
        if (restockElement == null || restockElement.isJsonNull()) {
            return null;
        }
        if (!restockElement.isJsonObject()) {
            throw new IOException("Field 'restock' in " + context + " must be an object.");
        }

        JsonObject restockObject = restockElement.getAsJsonObject();
        String type = readRequiredString(restockObject, "type", context + ", restock").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "interval" -> {
                int amount = readInt(restockObject, "amount", -1, context + ", restock");
                long everySeconds = readLong(restockObject, "every_seconds", -1L, context + ", restock");
                if (amount <= 0) {
                    throw new IOException("Field 'amount' in " + context + ", restock must be greater than 0.");
                }
                if (everySeconds <= 0L) {
                    throw new IOException("Field 'every_seconds' in " + context + ", restock must be greater than 0.");
                }
                yield new RestockRule.IntervalRestockRule(amount, everySeconds);
            }
            case "daily_reset" -> {
                int hour = readInt(restockObject, "hour", -1, context + ", restock");
                int minute = readInt(restockObject, "minute", 0, context + ", restock");
                if (hour < 0 || hour > 23) {
                    throw new IOException("Field 'hour' in " + context + ", restock must be between 0 and 23.");
                }
                if (minute < 0 || minute > 59) {
                    throw new IOException("Field 'minute' in " + context + ", restock must be between 0 and 59.");
                }

                String timeZone = readOptionalString(restockObject, "time_zone", ZoneId.systemDefault().getId(), context + ", restock");
                try {
                    ZoneId.of(timeZone);
                } catch (RuntimeException exception) {
                    throw new IOException("Field 'time_zone' in " + context + ", restock is invalid: " + timeZone, exception);
                }
                yield new RestockRule.DailyRestockRule(hour, minute, timeZone);
            }
            default -> throw new IOException("Unknown restock type '" + type + "' in " + context + ".");
        };
    }

    private static void ensureShopDirectory() throws IOException {
        Files.createDirectories(SHOP_DIRECTORY);
    }

    private static void writeShop(Path shopFile, ShopDefinition shop) throws IOException {
        Files.createDirectories(shopFile.getParent());
        try (Writer writer = Files.newBufferedWriter(shopFile)) {
            GSON.toJson(toJson(shop), writer);
        }
    }

    private static ShopDefinition createExampleShop() {
        List<ShopOfferDefinition> generalOffers = List.of(
                new ShopOfferDefinition("emerald_pack", new ItemStack(Items.EMERALD, 4), BigInteger.valueOf(25L), -1, null),
                new ShopOfferDefinition("daily_apple", new ItemStack(Items.GOLDEN_APPLE, 1), BigInteger.valueOf(125L), 3,
                        new RestockRule.DailyRestockRule(4, 0, ZoneId.systemDefault().getId())),
                new ShopOfferDefinition("interval_pearl", new ItemStack(Items.ENDER_PEARL, 2), BigInteger.valueOf(80L), 6,
                        new RestockRule.IntervalRestockRule(1, 300L))
        );
        List<ShopCategoryDefinition> categories = List.of(new ShopCategoryDefinition("General", generalOffers));
        return new ShopDefinition("example", categories, SHOP_DIRECTORY.resolve(EXAMPLE_FILENAME));
    }

    private static JsonElement toJson(ShopDefinition shop) {
        JsonObject root = new JsonObject();
        root.addProperty("id", shop.id());
        JsonArray categoriesArray = new JsonArray();
        for (ShopCategoryDefinition category : shop.categories()) {
            JsonObject categoryObject = new JsonObject();
            categoryObject.addProperty("name", category.name());
            JsonArray offersArray = new JsonArray();

            for (ShopOfferDefinition offer : category.offers()) {
                JsonObject offerObject = new JsonObject();
                offerObject.addProperty("id", offer.id());
                offerObject.addProperty("item", BuiltInRegistries.ITEM.getKey(offer.createItemStack().getItem()).toString());
                offerObject.addProperty("count", offer.createItemStack().getCount());
                offerObject.addProperty("price", offer.price());
                if (offer.hasFiniteStock()) {
                    offerObject.addProperty("stock", offer.stock());
                }
                if (offer.restockRule() instanceof RestockRule.IntervalRestockRule intervalRule) {
                    JsonObject restockObject = new JsonObject();
                    restockObject.addProperty("type", intervalRule.type());
                    restockObject.addProperty("amount", intervalRule.amount());
                    restockObject.addProperty("every_seconds", intervalRule.everySeconds());
                    offerObject.add("restock", restockObject);
                } else if (offer.restockRule() instanceof RestockRule.DailyRestockRule dailyRule) {
                    JsonObject restockObject = new JsonObject();
                    restockObject.addProperty("type", dailyRule.type());
                    restockObject.addProperty("hour", dailyRule.hour());
                    restockObject.addProperty("minute", dailyRule.minute());
                    restockObject.addProperty("time_zone", dailyRule.timeZone());
                    offerObject.add("restock", restockObject);
                }
                offersArray.add(offerObject);
            }

            categoryObject.add("offers", offersArray);
            categoriesArray.add(categoryObject);
        }
        root.add("categories", categoriesArray);
        return root;
    }

    private static String deriveLegacyOfferId(JsonObject offerObject, int offerIndex, String context) throws IOException {
        String explicitId = readOptionalString(offerObject, "id", null, context);
        if (explicitId != null && !explicitId.isBlank()) {
            return normalizeId(explicitId, "offer id");
        }

        String itemId = readRequiredString(offerObject, "item", context);
        String path;
        try {
            path = ResourceLocation.parse(itemId).getPath();
        } catch (RuntimeException exception) {
            throw new IOException("Field 'item' in " + context + " is not a valid item id: " + itemId, exception);
        }
        return normalizeId(path + "_" + offerIndex, "legacy offer id");
    }

    private static String readRequiredId(JsonObject object, String key, String context) throws IOException {
        return normalizeId(readRequiredString(object, key, context), key);
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

    private static String readOptionalString(JsonObject object, String key, String defaultValue, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Field '" + key + "' in " + context + " must be a string.");
        }
        return element.getAsString();
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

    private static long readLong(JsonObject object, String key, long defaultValue, String context) throws IOException {
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

    private static String normalizeId(String id, String label) {
        String normalizedId = id.toLowerCase(Locale.ROOT);
        if (!normalizedId.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + id + ". Use only lowercase letters, numbers, underscores, and hyphens.");
        }
        return normalizedId;
    }

    private static String stripExtension(String fileName) {
        if (fileName.endsWith(SHOP_EXTENSION)) {
            return fileName.substring(0, fileName.length() - SHOP_EXTENSION.length());
        }
        return fileName;
    }
}
