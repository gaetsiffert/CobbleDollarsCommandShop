package fr.cobbledollars.commandshops.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(EphemeralTestServerProvider.class)
class ShopRegistryServerTest {
    private static final String CUSTOM_SHOP_ID = "runtime_test_shop";
    private static final String INVALID_SHOP_ID = "runtime_invalid_shop";
    private static final String CUSTOM_BANK_SHOP_ID = "runtime_bank_shop";
    private static final String INVALID_BANK_SHOP_ID = "runtime_invalid_bank_shop";

    @AfterEach
    void clearRegistryState() throws IOException {
        ShopRegistry.clear();
        deleteRecursively(ShopRegistry.getShopDirectory());
        deleteRecursively(ShopRegistry.getShopDirectory().resolve(CUSTOM_SHOP_ID));
        deleteRecursively(ShopRegistry.getShopDirectory().resolve(INVALID_SHOP_ID));
        deleteRecursively(ShopRegistry.getShopDirectory().resolve(CUSTOM_BANK_SHOP_ID));
        deleteRecursively(ShopRegistry.getShopDirectory().resolve(INVALID_BANK_SHOP_ID));
    }

    @Test
    void initializes_default_shops_against_server_registries(MinecraftServer server) throws IOException {
        ShopRegistry.clear();
        deleteRecursively(ShopRegistry.getShopDirectory());

        ShopRegistry.ReloadSummary summary = ShopRegistry.initialize(server.registryAccess());
        boolean cobblemonLoaded = ModList.get().isLoaded("cobblemon");
        assertTrue(Files.isDirectory(ShopRegistry.getShopDirectory()));
        assertTrue(Files.isRegularFile(ShopRegistry.getGlobalBankFile()));
        assertTrue(summary.shopCount() >= (cobblemonLoaded ? 7 : 4));
        assertTrue(summary.localBankCount() >= 1);

        List<String> shopIds = ShopRegistry.listShopIds();
        assertTrue(shopIds.containsAll(List.of("blacksmith", "explorer", "general_store", "syntax_showcase")));
        if (cobblemonLoaded) {
            assertTrue(shopIds.containsAll(List.of("trainer_supply", "breeder_corner", "night_market")));
        }

        ShopDefinition generalStore = ShopRegistry.getShop("general_store");
        assertNotNull(generalStore);
        assertEquals("general_store", generalStore.id());
        assertTrue(generalStore.categories().size() >= 1);
        assertTrue(Files.isRegularFile(generalStore.sourceFile()));
        if (cobblemonLoaded) {
            ShopDefinition nightMarket = ShopRegistry.getShop("night_market");
            assertNotNull(nightMarket);
            assertTrue(nightMarket.hasTimeConditions());
            assertNull(nightMarket.denyMessage());
            assertEquals("This market opens only after nightfall.", nightMarket.conditions()
                    .firstFailure(context(Set.of(), 6000, Map.of())).denyMessage());
        }
    }

    @Test
    void generated_defaults_use_modern_formats_without_changing_condition_semantics(MinecraftServer server) throws IOException {
        deleteRecursively(ShopRegistry.getShopDirectory());
        Files.deleteIfExists(ShopRegistry.getGlobalBankFile());
        ShopRegistry.initialize(server.registryAccess());

        JsonElement globalBank = JsonParser.parseString(Files.readString(ShopRegistry.getGlobalBankFile()));
        assertTrue(globalBank.isJsonObject(), "A fresh global bank must support root settings without conversion.");
        assertTrue(globalBank.getAsJsonObject().get("categories").isJsonArray());
        int conditionBlocks = 0;
        try (var paths = Files.walk(ShopRegistry.getShopDirectory())) {
            for (Path file : paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json")).toList()) {
                JsonElement root = JsonParser.parseString(Files.readString(file));
                assertTrue(root.isJsonObject(), "Fresh shops and banks must use object roots: " + file);
                assertFalse(root.getAsJsonObject().has("deny_message"), "Fresh access messages belong to their condition rule: " + file);
                conditionBlocks += assertOrderedConditionBlocks(root, file.toString());
            }
        }
        assertTrue(conditionBlocks > 0, "The recursive format check must include the generated showcase conditions.");

        ShopDefinition showcase = ShopRegistry.getShop("syntax_showcase");
        ConditionSet.Failure blocked = showcase.conditions().firstFailure(context(Set.of("syntax_showcase_banned"), 6000, Map.of()));
        assertEquals("player_tags_none", blocked.type());
        assertEquals("This showcase is blocked for your current access profile.", blocked.denyMessage());
        assertNull(showcase.conditions().firstFailure(context(Set.of(), 6000, Map.of())));

        ConditionSet categoryAccess = showcase.categories().stream().filter(category -> category.name().equals("Condition Matrix"))
                .findFirst().orElseThrow().conditions();
        assertEquals("player_tags_all", categoryAccess.firstFailure(context(Set.of("syntax_showcase_access"), 6000, Map.of())).type());
        assertNull(categoryAccess.firstFailure(context(Set.of("syntax_showcase_access", "merchant_permit"), 6000, Map.of())));

        ConditionSet quartzAccess = ShopRegistry.getBankDefinition("syntax_showcase").categories().stream()
                .flatMap(category -> category.offers().stream()).filter(offer -> offer.price().intValueExact() == 9)
                .findFirst().orElseThrow().conditions();
        assertEquals("player_tags_all", quartzAccess.firstFailure(context(Set.of(), 6000, Map.of())).type(),
                "Modern examples must keep the legacy evaluator's family order, independent of JSON member order.");
        assertEquals("advancements_all", quartzAccess.firstFailure(context(Set.of("syntax_showcase_access", "merchant_permit"), 6000, Map.of())).type());
        assertNull(quartzAccess.firstFailure(context(Set.of("syntax_showcase_access", "merchant_permit"), 6000,
                Map.of(ResourceLocation.parse("minecraft:story/mine_diamond"), true,
                        ResourceLocation.parse("minecraft:story/enter_the_nether"), true))));
    }

    @Test
    void generating_defaults_never_rewrites_existing_legacy_files(MinecraftServer server) throws IOException {
        deleteRecursively(ShopRegistry.getShopDirectory());
        ShopRegistry.initialize(server.registryAccess());
        Path shop = ShopRegistry.getShopDirectory().resolve("syntax_showcase/shop.json");
        Path bank = ShopRegistry.getShopDirectory().resolve("syntax_showcase/bank.json");
        String legacyShop = "{\"deny_message\":\"Existing access message\",\"conditions\":{\"player_tags_none\":[\"blocked\"]},\"categories\":[]}\n";
        String legacyBank = "[ ]\n";
        byte[] originalGlobalBank = Files.readAllBytes(ShopRegistry.getGlobalBankFile());
        try {
            Files.writeString(shop, legacyShop);
            Files.writeString(bank, legacyBank);
            Files.writeString(ShopRegistry.getGlobalBankFile(), legacyBank);

            ShopFiles.ensureDefaultShopsExist();
            BankFiles.ensureDefaultGlobalBankExists();
            ShopRegistry.reload(server.registryAccess());

            assertEquals(legacyShop, Files.readString(shop));
            assertEquals(legacyBank, Files.readString(bank));
            assertEquals(legacyBank, Files.readString(ShopRegistry.getGlobalBankFile()));
            assertEquals("Existing access message", ShopRegistry.getShop("syntax_showcase").denyMessage());
            assertEquals("player_tags_none", ShopRegistry.getShop("syntax_showcase").conditions()
                    .firstFailure(context(Set.of("blocked"), 6000, Map.of())).type());
            assertTrue(ShopRegistry.getBankDefinition("syntax_showcase").categories().getFirst().offers().isEmpty());
        } finally {
            Files.write(ShopRegistry.getGlobalBankFile(), originalGlobalBank);
        }
    }

    @Test
    void reload_picks_up_added_and_removed_custom_shop(MinecraftServer server) throws IOException {
        ShopRegistry.clear();
        deleteRecursively(ShopRegistry.getShopDirectory());

        ShopRegistry.ReloadSummary initialSummary = ShopRegistry.initialize(server.registryAccess());
        Path customShopDirectory = ShopRegistry.getShopDirectory().resolve(CUSTOM_SHOP_ID);
        Path customShopFile = customShopDirectory.resolve("shop.json");

        Files.createDirectories(customShopDirectory);
        Files.writeString(customShopFile, """
                {
                  "categories": [
                    {
                      "name": "Runtime Test",
                      "offers": [
                        {
                          "id": "emerald_bundle",
                          "match": {
                            "include": [
                              { "item": "minecraft:emerald" }
                            ]
                          },
                          "count": 3,
                          "price": 42
                        }
                      ]
                    }
                  ]
                }
                """);

        ShopRegistry.ReloadSummary addedSummary = ShopRegistry.reload(server.registryAccess());
        assertEquals(initialSummary.shopCount() + 1, addedSummary.shopCount());
        assertEquals(initialSummary.localBankCount(), addedSummary.localBankCount());
        assertTrue(ShopRegistry.listShopIds().contains(CUSTOM_SHOP_ID));

        ShopDefinition customShop = ShopRegistry.getShop(CUSTOM_SHOP_ID);
        assertNotNull(customShop);
        assertEquals(CUSTOM_SHOP_ID, customShop.id());
        assertEquals(1, customShop.categories().size());
        assertEquals(1, customShop.offers().size());
        assertEquals(customShopFile, customShop.sourceFile());

        deleteRecursively(customShopDirectory);

        ShopRegistry.ReloadSummary removedSummary = ShopRegistry.reload(server.registryAccess());
        assertEquals(initialSummary.shopCount(), removedSummary.shopCount());
        assertEquals(initialSummary.localBankCount(), removedSummary.localBankCount());
        assertNull(ShopRegistry.getShop(CUSTOM_SHOP_ID));
        assertTrue(!ShopRegistry.listShopIds().contains(CUSTOM_SHOP_ID));
    }

    @Test
    void initialize_does_not_recreate_missing_default_shop_when_shops_directory_already_exists(MinecraftServer server)
            throws IOException {
        ShopRegistry.clear();
        deleteRecursively(ShopRegistry.getShopDirectory());

        try {
            ShopRegistry.initialize(server.registryAccess());
            Path generalStoreDirectory = ShopRegistry.getShopDirectory().resolve("general_store");
            assertTrue(Files.isDirectory(generalStoreDirectory));

            deleteRecursively(generalStoreDirectory);
            assertTrue(Files.isDirectory(ShopRegistry.getShopDirectory()),
                    "The parent shops directory should still exist for this regression test.");

            ShopRegistry.clear();
            ShopRegistry.ReloadSummary summary = ShopRegistry.initialize(server.registryAccess());

            assertNull(ShopRegistry.getShop("general_store"));
            assertTrue(!ShopRegistry.listShopIds().contains("general_store"));
            assertTrue(summary.shopCount() >= 1,
                    "The registry should still load any remaining shops even when one default shop was removed.");
            assertTrue(!Files.exists(generalStoreDirectory),
                    "A missing default shop should not be recreated when the shops directory already exists.");
        } finally {
            ShopRegistry.clear();
            deleteRecursively(ShopRegistry.getShopDirectory());
        }
    }

    @Test
    void reload_rejects_invalid_shop_without_replacing_previous_state(MinecraftServer server) throws IOException {
        ShopRegistry.clear();
        deleteRecursively(ShopRegistry.getShopDirectory());

        ShopRegistry.ReloadSummary initialSummary = ShopRegistry.initialize(server.registryAccess());
        List<String> initialShopIds = ShopRegistry.listShopIds();
        Path invalidShopDirectory = ShopRegistry.getShopDirectory().resolve(INVALID_SHOP_ID);
        Path invalidShopFile = invalidShopDirectory.resolve("shop.json");

        Files.createDirectories(invalidShopDirectory);
        Files.writeString(invalidShopFile, """
                {
                  "categories": [
                    {
                      "name": "Broken Shop",
                      "offers": [
                        {
                          "id": "duplicate_offer",
                          "match": {
                            "include": [
                              { "item": "minecraft:iron_ingot" }
                            ]
                          },
                          "price": 5
                        },
                        {
                          "id": "duplicate_offer",
                          "match": {
                            "include": [
                              { "item": "minecraft:gold_ingot" }
                            ]
                          },
                          "price": 7
                        }
                      ]
                    }
                  ]
                }
                """);

        IOException exception = assertThrows(IOException.class, () -> ShopRegistry.reload(server.registryAccess()));
        assertTrue(exception.getMessage().contains("Duplicate offer id 'duplicate_offer'"));
        assertEquals(initialSummary.shopCount(), ShopRegistry.listShopIds().size());
        assertEquals(initialShopIds, ShopRegistry.listShopIds());
        assertNull(ShopRegistry.getShop(INVALID_SHOP_ID));
        assertNotNull(ShopRegistry.getShop("general_store"));
    }

    @Test
    void reload_picks_up_and_removes_custom_local_bank(MinecraftServer server)
            throws IOException {
        ShopRegistry.clear();
        deleteRecursively(ShopRegistry.getShopDirectory());

        ShopRegistry.ReloadSummary initialSummary = ShopRegistry.initialize(server.registryAccess());
        Path customShopDirectory = ShopRegistry.getShopDirectory().resolve(CUSTOM_BANK_SHOP_ID);
        Path customBankFile = customShopDirectory.resolve("bank.json");
        Files.createDirectories(customShopDirectory);
        Files.writeString(customShopDirectory.resolve("shop.json"), simpleShopJson("diamond_offer", "minecraft:diamond", 75));
        Files.writeString(customBankFile, simpleBankJson("minecraft:diamond", 999));

        ShopRegistry.ReloadSummary addedSummary = ShopRegistry.reload(server.registryAccess());
        assertEquals(initialSummary.localBankCount() + 1, addedSummary.localBankCount());
        assertNotNull(ShopRegistry.getShop(CUSTOM_BANK_SHOP_ID));

        BankDefinition localBank = ShopRegistry.getBankDefinition(CUSTOM_BANK_SHOP_ID);
        assertEquals(customBankFile, localBank.sourceFile());

        Files.delete(customBankFile);

        ShopRegistry.ReloadSummary removedSummary = ShopRegistry.reload(server.registryAccess());
        assertEquals(initialSummary.localBankCount(), removedSummary.localBankCount());

        BankDefinition fallbackBank = ShopRegistry.getBankDefinition(CUSTOM_BANK_SHOP_ID);
        assertEquals(ShopRegistry.getGlobalBankFile(), fallbackBank.sourceFile());
    }

    @Test
    void reload_rejects_invalid_local_bank_without_replacing_previous_state(MinecraftServer server)
            throws IOException {
        ShopRegistry.clear();
        deleteRecursively(ShopRegistry.getShopDirectory());

        ShopRegistry.initialize(server.registryAccess());
        Path customShopDirectory = ShopRegistry.getShopDirectory().resolve(INVALID_BANK_SHOP_ID);
        Path customBankFile = customShopDirectory.resolve("bank.json");
        Files.createDirectories(customShopDirectory);
        Files.writeString(customShopDirectory.resolve("shop.json"), simpleShopJson("diamond_offer", "minecraft:diamond", 75));
        Files.writeString(customBankFile, simpleBankJson("minecraft:diamond", 999));

        ShopRegistry.ReloadSummary initialSummary = ShopRegistry.reload(server.registryAccess());
        List<String> initialShopIds = ShopRegistry.listShopIds();

        assertEquals(customBankFile, ShopRegistry.getBankDefinition(INVALID_BANK_SHOP_ID).sourceFile());

        Files.writeString(customBankFile, """
                {
                  "offers": [
                    {
                      "match": {
                        "include": [
                          { "item": "minecraft:diamond" }
                        ]
                      },
                      "count": 64,
                      "price": 999
                    }
                  ]
                }
                """);

        IOException exception = assertThrows(IOException.class, () -> ShopRegistry.reload(server.registryAccess()));
        assertTrue(exception.getMessage().contains("Field 'count' is not supported in bank offers anymore."));
        assertEquals(initialSummary.shopCount(), ShopRegistry.listShopIds().size());
        assertEquals(initialShopIds, ShopRegistry.listShopIds());
        assertEquals(customBankFile, ShopRegistry.getBankDefinition(INVALID_BANK_SHOP_ID).sourceFile());
    }

    private static int assertOrderedConditionBlocks(JsonElement element, String file) {
        int blocks = 0;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("conditions")) {
                    assertTrue(entry.getValue().isJsonArray(), "A generated condition must already be an ordered list: " + file);
                    blocks++;
                }
                blocks += assertOrderedConditionBlocks(entry.getValue(), file);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) blocks += assertOrderedConditionBlocks(child, file);
        }
        return blocks;
    }

    private static ConditionSet.ConditionContext context(Set<String> tags, long time, Map<ResourceLocation, Boolean> advancements) {
        return new ConditionSet.ConditionContext(tags, ResourceLocation.parse("minecraft:overworld"), time, advancements, Map.of());
    }

    private static String simpleShopJson(String offerId, String itemId, int price) {
        return """
                {
                  "categories": [
                    {
                      "name": "Runtime Test",
                      "offers": [
                        {
                          "id": "%s",
                          "match": {
                            "include": [
                              { "item": "%s" }
                            ]
                          },
                          "count": 1,
                          "price": %d
                        }
                      ]
                    }
                  ]
                }
                """.formatted(offerId, itemId, price);
    }

    private static String simpleBankJson(String itemId, int price) {
        return """
                {
                  "offers": [
                    {
                      "match": {
                        "include": [
                          { "item": "%s" }
                        ]
                      },
                      "price": %d
                    }
                  ]
                }
                """.formatted(itemId, price);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(currentPath -> {
                try {
                    Files.deleteIfExists(currentPath);
                } catch (IOException exception) {
                    throw new RuntimeException("Failed to delete test path: " + currentPath, exception);
                }
            });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }
}
