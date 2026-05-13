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
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;

public final class ShopFiles {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final String SHOP_FILENAME = "shop.json";
    private static final Path CONFIG_DIRECTORY = FMLPaths.CONFIGDIR.get().resolve(CobbleDollarsCommandShopsMod.MODID);
    private static final Path SHOP_DIRECTORY = CONFIG_DIRECTORY.resolve("shops");

    private ShopFiles() {
    }

    public static Path getConfigDirectory() {
        return CONFIG_DIRECTORY;
    }

    public static Path getShopDirectory() {
        return SHOP_DIRECTORY;
    }

    public static Path resolveShopFile(Path shopFolder) {
        return shopFolder.resolve(SHOP_FILENAME);
    }

    public static void ensureDefaultShopsExist() throws IOException {
        Files.createDirectories(SHOP_DIRECTORY);
        ensureShopExists("general_store", createGeneralStoreShop());
        ensureShopExists("blacksmith", createBlacksmithShop());
        ensureShopExists("explorer", createExplorerShop());
        ensureShopJsonExists("syntax_showcase", createSyntaxShowcaseShopJson());
        BankFiles.ensureLocalBankJsonExists("syntax_showcase", BankFiles.createSyntaxShowcaseBankJson());
    }

    public static ShopDefinition parseShopFile(String shopId, Path shopFile, HolderLookup.Provider provider) throws IOException {
        String normalizedShopId = normalizeId(shopId, "shop id");
        try (Reader reader = Files.newBufferedReader(shopFile)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                throw new IOException("Shop file '" + shopFile + "' is empty.");
            }
            if (!root.isJsonObject()) {
                throw new IOException("Shop file '" + shopFile + "' must be a JSON object.");
            }
            return parseModernShop(normalizedShopId, shopFile, root.getAsJsonObject(), provider);
        } catch (RuntimeException exception) {
            throw new IOException("Shop file '" + shopFile + "' could not be parsed.", exception);
        }
    }

    public static String normalizeId(String id, String label) {
        String normalizedId = id.toLowerCase(Locale.ROOT);
        if (!normalizedId.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + id + ". Use only lowercase letters, numbers, underscores, and hyphens.");
        }
        return normalizedId;
    }

    private static ShopDefinition parseModernShop(String shopId, Path shopFile, JsonObject rootObject, HolderLookup.Provider provider) throws IOException {
        String context = "shop file '" + shopFile + "'";
        ConditionSet shopConditions = ConditionSet.readOptional(rootObject, context);
        String denyMessage = ConfigParsing.readOptionalString(rootObject, "deny_message", null, context);
        if (denyMessage != null && denyMessage.isBlank()) {
            denyMessage = null;
        }
        JsonArray categoriesArray = ConfigParsing.readRequiredArray(rootObject, "categories", context);

        Set<String> seenOfferIds = new HashSet<>();
        List<ShopCategoryDefinition> categories = new ArrayList<>();
        for (int categoryIndex = 0; categoryIndex < categoriesArray.size(); categoryIndex++) {
            JsonElement categoryElement = categoriesArray.get(categoryIndex);
            if (!categoryElement.isJsonObject()) {
                throw new IOException("Category #" + categoryIndex + " in " + context + " must be an object.");
            }

            JsonObject categoryObject = categoryElement.getAsJsonObject();
            String categoryContext = "shop '" + shopId + "', category #" + categoryIndex;
            String categoryName = ConfigParsing.readRequiredString(categoryObject, "name", categoryContext);
            ConditionSet categoryConditions = ConditionSet.readOptional(categoryObject, categoryContext);
            JsonArray offersArray = ConfigParsing.readRequiredArray(categoryObject, "offers", "shop '" + shopId + "', category '" + categoryName + "'");

            List<ShopOfferDefinition> offers = new ArrayList<>();
            for (int offerIndex = 0; offerIndex < offersArray.size(); offerIndex++) {
                ShopOfferDefinition offer = parseOffer(offersArray.get(offerIndex), shopId, categoryName, offerIndex, provider);
                if (!seenOfferIds.add(offer.id())) {
                    throw new IOException("Duplicate offer id '" + offer.id() + "' in shop '" + shopId + "'.");
                }
                offers.add(offer);
            }
            categories.add(new ShopCategoryDefinition(categoryName, offers, categoryConditions));
        }

        return new ShopDefinition(shopId, categories, shopConditions, denyMessage, shopFile);
    }

    private static ShopOfferDefinition parseOffer(JsonElement offerElement, String shopId, String categoryName, int offerIndex, HolderLookup.Provider provider) throws IOException {
        String context = "shop '" + shopId + "', category '" + categoryName + "', offer #" + offerIndex;
        if (!offerElement.isJsonObject()) {
            throw new IOException("Each offer in " + context + " must be an object.");
        }

        JsonObject offerObject = offerElement.getAsJsonObject();
        String offerId = normalizeId(ConfigParsing.readRequiredString(offerObject, "id", context), "offer id");
        return parseOfferBody(offerObject, shopId, categoryName, offerId, provider);
    }

    private static ShopOfferDefinition parseOfferBody(JsonObject offerObject, String shopId, String categoryName, String offerId, HolderLookup.Provider provider) throws IOException {
        String context = "shop '" + shopId + "', category '" + categoryName + "', offer '" + offerId + "'";
        ItemMatchExpression match = ConfigParsing.readItemMatchExpression(offerObject, provider, context);
        int count = ConfigParsing.readInt(offerObject, "count", 1, context);
        if (count <= 0) {
            throw new IOException("Field 'count' in " + context + " must be greater than 0.");
        }
        BigInteger price = ConfigParsing.readBigInteger(offerObject, "price", context);
        if (price.signum() < 0) {
            throw new IOException("Field 'price' in " + context + " must be positive or zero.");
        }

        int stock = ConfigParsing.readInt(offerObject, "stock", -1, context);
        if (stock < -1) {
            throw new IOException("Field 'stock' in " + context + " must be -1 or greater.");
        }

        RestockRule restockRule = readRestockRule(offerObject, context);
        if (restockRule != null && stock < 0) {
            throw new IOException("Field 'stock' in " + context + " must be set when 'restock' is used.");
        }

        ConditionSet conditions = ConditionSet.readOptional(offerObject, context);
        return new ShopOfferDefinition(offerId, match, count, price, stock, restockRule, conditions);
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
        String type = ConfigParsing.readRequiredString(restockObject, "type", context + ", restock").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "interval" -> {
                int amount = ConfigParsing.readInt(restockObject, "amount", -1, context + ", restock");
                long everySeconds = ConfigParsing.readLong(restockObject, "every_seconds", -1L, context + ", restock");
                if (amount <= 0) {
                    throw new IOException("Field 'amount' in " + context + ", restock must be greater than 0.");
                }
                if (everySeconds <= 0L) {
                    throw new IOException("Field 'every_seconds' in " + context + ", restock must be greater than 0.");
                }
                yield new RestockRule.IntervalRestockRule(amount, everySeconds);
            }
            case "daily_reset" -> {
                int hour = ConfigParsing.readInt(restockObject, "hour", -1, context + ", restock");
                int minute = ConfigParsing.readInt(restockObject, "minute", 0, context + ", restock");
                if (hour < 0 || hour > 23) {
                    throw new IOException("Field 'hour' in " + context + ", restock must be between 0 and 23.");
                }
                if (minute < 0 || minute > 59) {
                    throw new IOException("Field 'minute' in " + context + ", restock must be between 0 and 59.");
                }

                String timeZone = ConfigParsing.readOptionalString(restockObject, "time_zone", ZoneId.systemDefault().getId(), context + ", restock");
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

    private static void writeShop(Path shopFile, ShopDefinition shop) throws IOException {
        Files.createDirectories(shopFile.getParent());
        try (Writer writer = Files.newBufferedWriter(shopFile)) {
            GSON.toJson(toJson(shop), writer);
        }
    }

    private static void ensureShopExists(String shopId, ShopDefinition shop) throws IOException {
        Path shopFile = resolveShopFile(SHOP_DIRECTORY.resolve(shopId));
        if (Files.exists(shopFile)) {
            return;
        }
        writeShop(shopFile, shop);
    }

    private static void ensureShopJsonExists(String shopId, String shopJson) throws IOException {
        Path shopFolder = SHOP_DIRECTORY.resolve(shopId);
        Files.createDirectories(shopFolder);
        Path shopFile = resolveShopFile(shopFolder);
        if (Files.exists(shopFile)) {
            return;
        }
        Files.writeString(shopFile, shopJson);
    }

    private static ShopDefinition createGeneralStoreShop() {
        List<ShopOfferDefinition> foodOffers = List.of(
                new ShopOfferDefinition("bread_bundle", exactItemMatch(Items.BREAD, "minecraft:bread"), 6, BigInteger.valueOf(24L), -1, null, ConditionSet.NONE),
                new ShopOfferDefinition("cooked_beef", exactItemMatch(Items.COOKED_BEEF, "minecraft:cooked_beef"), 8, BigInteger.valueOf(48L), -1, null, ConditionSet.NONE),
                new ShopOfferDefinition("golden_apple", exactItemMatch(Items.GOLDEN_APPLE, "minecraft:golden_apple"), 1, BigInteger.valueOf(140L), 3,
                        new RestockRule.DailyRestockRule(4, 0, ZoneId.systemDefault().getId()), ConditionSet.NONE)
        );
        List<ShopOfferDefinition> utilityOffers = List.of(
                new ShopOfferDefinition("torch_stack", exactItemMatch(Items.TORCH, "minecraft:torch"), 32, BigInteger.valueOf(18L), -1, null, ConditionSet.NONE),
                new ShopOfferDefinition("oak_logs", exactItemMatch(Items.OAK_LOG, "minecraft:oak_log"), 16, BigInteger.valueOf(30L), -1, null, ConditionSet.NONE),
                new ShopOfferDefinition("ender_pearl_pair", exactItemMatch(Items.ENDER_PEARL, "minecraft:ender_pearl"), 2, BigInteger.valueOf(90L), 6,
                        new RestockRule.IntervalRestockRule(1, 300L), ConditionSet.NONE)
        );
        List<ShopCategoryDefinition> categories = List.of(
                new ShopCategoryDefinition("Food", foodOffers, ConditionSet.NONE),
                new ShopCategoryDefinition("Utilities", utilityOffers, ConditionSet.NONE)
        );
        return new ShopDefinition("general_store", categories, ConditionSet.NONE, null, resolveShopFile(SHOP_DIRECTORY.resolve("general_store")));
    }

    private static ShopDefinition createBlacksmithShop() {
        List<ShopOfferDefinition> weaponOffers = List.of(
                new ShopOfferDefinition("iron_sword", exactItemMatch(Items.IRON_SWORD, "minecraft:iron_sword"), 1, BigInteger.valueOf(90L), -1, null, ConditionSet.NONE),
                new ShopOfferDefinition("crossbow", exactItemMatch(Items.CROSSBOW, "minecraft:crossbow"), 1, BigInteger.valueOf(120L), 4,
                        new RestockRule.IntervalRestockRule(1, 900L), ConditionSet.NONE)
        );
        List<ShopOfferDefinition> toolOffers = List.of(
                new ShopOfferDefinition("iron_pickaxe", exactItemMatch(Items.IRON_PICKAXE, "minecraft:iron_pickaxe"), 1, BigInteger.valueOf(110L), -1, null, ConditionSet.NONE),
                new ShopOfferDefinition("diamond_pickaxe", exactItemMatch(Items.DIAMOND_PICKAXE, "minecraft:diamond_pickaxe"), 1, BigInteger.valueOf(450L), 2,
                        new RestockRule.DailyRestockRule(4, 0, ZoneId.systemDefault().getId()), ConditionSet.NONE)
        );
        List<ShopCategoryDefinition> categories = List.of(
                new ShopCategoryDefinition("Weapons", weaponOffers, ConditionSet.NONE),
                new ShopCategoryDefinition("Tools", toolOffers, ConditionSet.NONE)
        );
        return new ShopDefinition("blacksmith", categories, ConditionSet.NONE, null, resolveShopFile(SHOP_DIRECTORY.resolve("blacksmith")));
    }

    private static ShopDefinition createExplorerShop() {
        List<ShopOfferDefinition> travelOffers = List.of(
                new ShopOfferDefinition("compass", exactItemMatch(Items.COMPASS, "minecraft:compass"), 1, BigInteger.valueOf(60L), -1, null, ConditionSet.NONE),
                new ShopOfferDefinition("map_bundle", exactItemMatch(Items.MAP, "minecraft:map"), 3, BigInteger.valueOf(45L), -1, null, ConditionSet.NONE),
                new ShopOfferDefinition("boat", exactItemMatch(Items.OAK_BOAT, "minecraft:oak_boat"), 1, BigInteger.valueOf(35L), -1, null, ConditionSet.NONE)
        );
        List<ShopOfferDefinition> supplyOffers = List.of(
                new ShopOfferDefinition("arrow_stack", exactItemMatch(Items.ARROW, "minecraft:arrow"), 32, BigInteger.valueOf(40L), -1, null, ConditionSet.NONE),
                new ShopOfferDefinition("lead_pair", exactItemMatch(Items.LEAD, "minecraft:lead"), 2, BigInteger.valueOf(70L), 5,
                        new RestockRule.IntervalRestockRule(1, 600L), ConditionSet.NONE),
                new ShopOfferDefinition("water_bucket", exactItemMatch(Items.WATER_BUCKET, "minecraft:water_bucket"), 1, BigInteger.valueOf(55L), -1, null, ConditionSet.NONE)
        );
        List<ShopCategoryDefinition> categories = List.of(
                new ShopCategoryDefinition("Travel", travelOffers, ConditionSet.NONE),
                new ShopCategoryDefinition("Supplies", supplyOffers, ConditionSet.NONE)
        );
        return new ShopDefinition("explorer", categories, ConditionSet.NONE, null, resolveShopFile(SHOP_DIRECTORY.resolve("explorer")));
    }

    private static JsonElement toJson(ShopDefinition shop) {
        JsonObject root = new JsonObject();
        if (shop.denyMessage() != null) {
            root.addProperty("deny_message", shop.denyMessage());
        }
        ConditionSet.writeOptional(root, shop.conditions());
        JsonArray categoriesArray = new JsonArray();
        for (ShopCategoryDefinition category : shop.categories()) {
            JsonObject categoryObject = new JsonObject();
            categoryObject.addProperty("name", category.name());
            ConditionSet.writeOptional(categoryObject, category.conditions());
            JsonArray offersArray = new JsonArray();

            for (ShopOfferDefinition offer : category.offers()) {
                JsonObject offerObject = new JsonObject();
                offerObject.addProperty("id", offer.id());
                ConfigParsing.writeMatchExpression(offerObject, offer.match());
                offerObject.addProperty("count", offer.count());
                offerObject.addProperty("price", offer.price());
                if (offer.hasFiniteStock()) {
                    offerObject.addProperty("stock", offer.stock());
                }
                ConditionSet.writeOptional(offerObject, offer.conditions());
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

    private static ItemMatchExpression exactItemMatch(net.minecraft.world.item.Item item, String itemId) {
        return ItemMatchExpression.include(new ItemMatchAtom.ExactItem(item, itemId));
    }

    private static String createSyntaxShowcaseShopJson() {
        return """
                {
                  "deny_message": "This showcase is blocked for your current access profile.",
                  "conditions": {
                    "player_tags_none": ["syntax_showcase_banned"]
                  },
                  "categories": [
                    {
                      "name": "Fallback Pricing",
                      "offers": [
                        {
                          "id": "all_logs",
                          "match": {
                            "include": [
                              { "tag": "minecraft:logs" }
                            ]
                          },
                          "count": 16,
                          "price": 30
                        },
                        {
                          "id": "oak_log_vip",
                          "match": {
                            "include": [
                              { "item": "minecraft:oak_log" }
                            ]
                          },
                          "count": 16,
                          "price": 24,
                          "conditions": {
                            "player_tags_any": ["vip_shop"]
                          }
                        }
                      ]
                    },
                    {
                      "name": "Exact Stack",
                      "offers": [
                        {
                          "id": "syntax_ticket",
                          "match": {
                            "include": [
                              { "stack": "minecraft:paper[custom_data={quest_id:\\\"syntax_ticket\\\"}]" }
                            ]
                          },
                          "count": 1,
                          "price": 250,
                          "stock": 2,
                          "restock": {
                            "type": "interval",
                            "amount": 1,
                            "every_seconds": 300
                          },
                          "conditions": {
                            "player_tags_any": ["quest_debug"]
                          }
                        }
                      ]
                    },
                    {
                      "name": "Condition Matrix",
                      "conditions": {
                        "player_tags_all": ["syntax_showcase_access", "merchant_permit"]
                      },
                      "offers": [
                        {
                          "id": "dawn_compass",
                          "match": {
                            "include": [
                              { "item": "minecraft:clock" }
                            ]
                          },
                          "count": 1,
                          "price": 40,
                          "conditions": {
                            "advancements_any": ["minecraft:story/mine_stone"],
                            "dimensions_any": ["minecraft:overworld"],
                            "time_ranges_any": [
                              { "start_tick": 0, "end_tick": 2000 }
                            ]
                          }
                        },
                        {
                          "id": "veteran_rations",
                          "match": {
                            "include": [
                              { "item": "minecraft:golden_carrot" }
                            ]
                          },
                          "count": 4,
                          "price": 90,
                          "conditions": {
                            "advancements_all": [
                              "minecraft:story/mine_diamond",
                              "minecraft:story/enter_the_nether"
                            ],
                            "scores_all": [
                              { "objective": "reputation", "min": 25 }
                            ]
                          }
                        }
                      ]
                    },
                    {
                      "name": "Mixed Include",
                      "offers": [
                        {
                          "id": "scout_supplies",
                          "match": {
                            "include": [
                              { "item": "minecraft:compass" },
                              { "item": "minecraft:map" },
                              { "item": "minecraft:spyglass" }
                            ],
                            "exclude": [
                              { "item": "minecraft:map" }
                            ]
                          },
                          "count": 1,
                          "price": 60
                        }
                      ]
                    },
                    {
                      "name": "Mod Scoped",
                      "conditions": {
                        "player_tags_any": ["syntax_showcase_access"]
                      },
                      "offers": [
                        {
                          "id": "minecraft_misc",
                          "match": {
                            "include": [
                              { "mod": "minecraft" }
                            ],
                            "exclude": [
                              { "tag": "minecraft:logs" },
                              { "tag": "minecraft:planks" }
                            ]
                          },
                          "count": 1,
                          "price": 2
                        }
                      ]
                    }
                  ]
                }
                """;
    }
}
