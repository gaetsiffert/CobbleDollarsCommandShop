package fr.cobbledollars.commandshops.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;
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
        deleteRecursively(ShopRegistry.getShopDirectory().resolve(CUSTOM_SHOP_ID));
        deleteRecursively(ShopRegistry.getShopDirectory().resolve(INVALID_SHOP_ID));
        deleteRecursively(ShopRegistry.getShopDirectory().resolve(CUSTOM_BANK_SHOP_ID));
        deleteRecursively(ShopRegistry.getShopDirectory().resolve(INVALID_BANK_SHOP_ID));
    }

    @Test
    void initializes_default_shops_against_server_registries(MinecraftServer server) throws IOException {
        ShopRegistry.clear();

        ShopRegistry.ReloadSummary summary = ShopRegistry.initialize(server.registryAccess());
        assertTrue(Files.isDirectory(ShopRegistry.getShopDirectory()));
        assertTrue(Files.isRegularFile(ShopRegistry.getGlobalBankFile()));
        assertTrue(summary.shopCount() >= 4);
        assertTrue(summary.localBankCount() >= 1);

        List<String> shopIds = ShopRegistry.listShopIds();
        assertTrue(shopIds.containsAll(List.of("blacksmith", "explorer", "general_store", "syntax_showcase")));

        ShopDefinition generalStore = ShopRegistry.getShop("general_store");
        assertNotNull(generalStore);
        assertEquals("general_store", generalStore.id());
        assertTrue(generalStore.categories().size() >= 1);
        assertTrue(Files.isRegularFile(generalStore.sourceFile()));
    }

    @Test
    void reload_picks_up_added_and_removed_custom_shop(MinecraftServer server) throws IOException {
        ShopRegistry.clear();

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
    void reload_rejects_invalid_shop_without_replacing_previous_state(MinecraftServer server) throws IOException {
        ShopRegistry.clear();

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
