package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Category;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
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
    private static final String EXAMPLE_SHOP_ID = "example";
    private static final Path SHOP_DIRECTORY = FMLPaths.CONFIGDIR.get().resolve(CobbleDollarsCommandShopsMod.MODID).resolve("shops");

    private ShopFiles() {
    }

    public static Path getShopDirectory() {
        return SHOP_DIRECTORY;
    }

    public static void ensureExampleShopExists() throws IOException {
        ensureShopDirectory();
        Path exampleShopFile = getShopFile(EXAMPLE_SHOP_ID);
        if (Files.exists(exampleShopFile)) {
            return;
        }

        writeShop(exampleShopFile, createExampleShop());
    }

    public static Shop loadShop(String shopId) throws IOException {
        ensureShopDirectory();
        Path shopFile = getShopFile(shopId);
        if (Files.notExists(shopFile)) {
            throw new IOException("Shop '" + shopId + "' was not found at " + shopFile);
        }

        try (Reader reader = Files.newBufferedReader(shopFile)) {
            JsonElement root = JsonParser.parseReader(reader);
            Shop shop = parseShop(root, shopId);
            if (shop == null) {
                throw new IOException("Shop '" + shopId + "' is empty");
            }
            return shop;
        } catch (RuntimeException exception) {
            throw new IOException("Shop '" + shopId + "' could not be parsed", exception);
        }
    }

    public static List<String> listShopIds() throws IOException {
        ensureShopDirectory();
        try (Stream<Path> paths = Files.walk(SHOP_DIRECTORY)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(SHOP_DIRECTORY::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/'))
                    .filter(path -> path.endsWith(SHOP_EXTENSION))
                    .map(path -> path.substring(0, path.length() - SHOP_EXTENSION.length()))
                    .sorted()
                    .toList();
        }
    }

    public static Path getShopFile(String shopId) {
        String normalizedId = normalizeShopId(shopId);
        Path resolved = SHOP_DIRECTORY.resolve(normalizedId + SHOP_EXTENSION).normalize();
        if (!resolved.startsWith(SHOP_DIRECTORY.normalize())) {
            throw new IllegalArgumentException("Invalid shop id: " + shopId);
        }
        return resolved;
    }

    private static void ensureShopDirectory() throws IOException {
        Files.createDirectories(SHOP_DIRECTORY);
    }

    private static String normalizeShopId(String shopId) {
        String normalizedId = shopId.toLowerCase(Locale.ROOT);
        if (!normalizedId.matches("[a-z0-9_./-]+") || normalizedId.contains("..") || normalizedId.startsWith("/") || normalizedId.endsWith("/")) {
            throw new IllegalArgumentException("Invalid shop id: " + shopId);
        }
        return normalizedId;
    }

    private static void writeShop(Path shopFile, Shop shop) throws IOException {
        Files.createDirectories(shopFile.getParent());
        try (Writer writer = Files.newBufferedWriter(shopFile)) {
            GSON.toJson(toJson(shop), writer);
        }
    }

    private static Shop createExampleShop() {
        java.util.ArrayList<Offer> offers = new java.util.ArrayList<>();
        offers.add(new Offer(new ItemStack(Items.EMERALD, 4), BigInteger.valueOf(25), -1));
        offers.add(new Offer(new ItemStack(Items.GOLDEN_APPLE, 1), BigInteger.valueOf(125), -1));

        Shop shop = new Shop();
        shop.add(new Category("General", offers));
        return shop;
    }

    private static Shop parseShop(JsonElement root, String shopId) throws IOException {
        if (root == null || root.isJsonNull()) {
            throw new IOException("Shop '" + shopId + "' is empty");
        }
        if (!root.isJsonArray()) {
            throw new IOException("Shop '" + shopId + "' must be a JSON array of categories.");
        }

        Shop shop = new Shop();
        JsonArray categoryArray = root.getAsJsonArray();
        for (int categoryIndex = 0; categoryIndex < categoryArray.size(); categoryIndex++) {
            JsonElement categoryElement = categoryArray.get(categoryIndex);
            if (!categoryElement.isJsonObject()) {
                throw new IOException("Category #" + categoryIndex + " in shop '" + shopId + "' must be an object.");
            }

            JsonObject categoryObject = categoryElement.getAsJsonObject();
            if (categoryObject.entrySet().isEmpty()) {
                throw new IOException("Category #" + categoryIndex + " in shop '" + shopId + "' is empty.");
            }

            for (Map.Entry<String, JsonElement> entry : categoryObject.entrySet()) {
                String categoryName = entry.getKey();
                JsonElement offersElement = entry.getValue();
                if (!offersElement.isJsonArray()) {
                    throw new IOException("Category '" + categoryName + "' in shop '" + shopId + "' must contain an array of offers.");
                }

                java.util.ArrayList<Offer> offers = new java.util.ArrayList<>();
                JsonArray offersArray = offersElement.getAsJsonArray();
                for (int offerIndex = 0; offerIndex < offersArray.size(); offerIndex++) {
                    offers.add(parseOffer(offersArray.get(offerIndex), shopId, categoryName, categoryIndex, offerIndex));
                }

                shop.add(new Category(categoryName, offers));
            }
        }

        return shop;
    }

    private static Offer parseOffer(JsonElement offerElement, String shopId, String categoryName, int categoryIndex, int offerIndex) throws IOException {
        String context = "shop '" + shopId + "', category '" + categoryName + "', offer #" + offerIndex;
        if (!offerElement.isJsonObject()) {
            throw new IOException("Each offer in " + context + " must be an object.");
        }

        JsonObject offerObject = offerElement.getAsJsonObject();
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

        return new Offer(new ItemStack(item, count), price, stock);
    }

    private static JsonElement toJson(Shop shop) {
        JsonArray categories = new JsonArray();
        for (Category category : shop) {
            JsonObject categoryObject = new JsonObject();
            JsonArray offers = new JsonArray();

            for (Offer offer : category.getOffers()) {
                ItemStack itemStack = offer.getItem();
                JsonObject offerObject = new JsonObject();
                offerObject.addProperty("item", BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString());
                offerObject.addProperty("count", itemStack.getCount());
                offerObject.addProperty("price", offer.getPrice());
                if (offer.getStock() >= 0) {
                    offerObject.addProperty("stock", offer.getStock());
                }
                offers.add(offerObject);
            }

            categoryObject.add(category.getName(), offers);
            categories.add(categoryObject);
        }
        return categories;
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
