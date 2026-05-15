package fr.cobbledollars.commandshops.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

@ForEachTest(idPrefix = "shop_registry.", groups = "shop_registry", side = Dist.DEDICATED_SERVER)
public final class CommandShopRegistryGameTests {
    private CommandShopRegistryGameTests() {
    }

    @TestHolder(
            value = "default_shops_are_available",
            title = "Default shops are available",
            description = "Verifies that the default shop and bank files are loaded before GameTests run."
    )
    @GameTest(batch = "shop_registry.default_shops", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void default_shops_are_available(ExtendedGameTestHelper helper) {
        List<String> shopIds = ShopRegistry.listShopIds();
        helper.assertTrue(shopIds.containsAll(List.of("blacksmith", "explorer", "general_store", "syntax_showcase")),
                "Default shop ids are missing: " + shopIds);
        if (ModList.get().isLoaded("cobblemon")) {
            helper.assertTrue(shopIds.containsAll(List.of("trainer_supply", "breeder_corner", "night_market")),
                    "Cobblemon starter shop ids are missing: " + shopIds);
        }
        helper.assertTrue(Files.isDirectory(ShopRegistry.getShopDirectory()), "Shop directory was not created.");
        helper.assertTrue(Files.isRegularFile(ShopRegistry.getGlobalBankFile()), "Global bank file is missing.");

        ShopDefinition generalStore = ShopRegistry.getShop("general_store");
        helper.assertTrue(generalStore != null, "general_store was not loaded.");
        helper.assertValueEqual("general_store", generalStore.id(), "general_store id changed.");
        helper.assertTrue(!generalStore.categories().isEmpty(), "general_store has no categories.");
        if (ModList.get().isLoaded("cobblemon")) {
            ShopDefinition nightMarket = ShopRegistry.getShop("night_market");
            helper.assertTrue(nightMarket != null, "night_market was not loaded.");
            helper.assertTrue(nightMarket.hasTimeConditions(), "night_market should have a night-only time condition.");
            helper.assertTrue(nightMarket.denyMessage() != null && !nightMarket.denyMessage().isBlank(),
                    "night_market should expose a deny message.");
        }
        helper.succeed();
    }

    @TestHolder(
            value = "reload_adds_and_removes_custom_shop",
            title = "Reload adds and removes a custom shop",
            description = "Creates a temporary shop file, reloads the registry, then removes the file and reloads again."
    )
    @GameTest(batch = "shop_registry.reload_custom_shop", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void reload_adds_and_removes_custom_shop(ExtendedGameTestHelper helper) {
        try {
            CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.CUSTOM_SHOP_ID);

            int initialShopCount = ShopRegistry.listShopIds().size();
            CommandShopGameTestSupport.writeShopJson(CommandShopGameTestSupport.CUSTOM_SHOP_ID, """
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

            ShopRegistry.ReloadSummary addedSummary = ShopRegistry.reload(helper.getLevel().registryAccess());
            helper.assertValueEqual(initialShopCount + 1, addedSummary.shopCount(), "Reload did not pick up the custom shop.");

            ShopDefinition customShop = ShopRegistry.getShop(CommandShopGameTestSupport.CUSTOM_SHOP_ID);
            helper.assertTrue(customShop != null, "Custom shop was not registered after reload.");
            helper.assertValueEqual(CommandShopGameTestSupport.CUSTOM_SHOP_ID, customShop.id(), "Custom shop id mismatch.");
            helper.assertValueEqual(1, customShop.categories().size(), "Custom shop category count mismatch.");
            helper.assertValueEqual(1, customShop.offers().size(), "Custom shop offer count mismatch.");

            CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.CUSTOM_SHOP_ID);

            ShopRegistry.ReloadSummary removedSummary = ShopRegistry.reload(helper.getLevel().registryAccess());
            helper.assertValueEqual(initialShopCount, removedSummary.shopCount(), "Reload did not remove the deleted custom shop.");
            helper.assertTrue(ShopRegistry.getShop(CommandShopGameTestSupport.CUSTOM_SHOP_ID) == null,
                    "Deleted custom shop is still present after reload.");
        } catch (IOException exception) {
            helper.fail("Unexpected IO error during custom shop reload test: " + exception.getMessage());
            return;
        } finally {
            try {
                CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.CUSTOM_SHOP_ID);
            } catch (IOException ignored) {
            }
        }

        helper.succeed();
    }

    @TestHolder(
            value = "invalid_reload_keeps_previous_registry_state",
            title = "Invalid reload keeps previous registry state",
            description = "Writes an invalid shop file and verifies that reload fails without replacing the previous registry state."
    )
    @GameTest(batch = "shop_registry.invalid_reload", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void invalid_reload_keeps_previous_registry_state(ExtendedGameTestHelper helper) {
        try {
            CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.INVALID_SHOP_ID);

            int initialShopCount = ShopRegistry.listShopIds().size();
            List<String> initialShopIds = ShopRegistry.listShopIds();
            CommandShopGameTestSupport.writeShopJson(CommandShopGameTestSupport.INVALID_SHOP_ID, """
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

            try {
                ShopRegistry.reload(helper.getLevel().registryAccess());
                helper.fail("Reload unexpectedly accepted an invalid shop definition.");
                return;
            } catch (IOException expected) {
                helper.assertTrue(expected.getMessage().contains("Duplicate offer id 'duplicate_offer'"),
                        "Reload failed for an unexpected reason: " + expected.getMessage());
            }

            helper.assertValueEqual(initialShopCount, ShopRegistry.listShopIds().size(),
                    "Invalid reload replaced the previous registry state.");
            helper.assertValueEqual(initialShopIds, ShopRegistry.listShopIds(),
                    "Shop id ordering/state changed after invalid reload.");
            helper.assertTrue(ShopRegistry.getShop(CommandShopGameTestSupport.INVALID_SHOP_ID) == null,
                    "Invalid shop remained registered after reload failure.");
            helper.assertTrue(ShopRegistry.getShop("general_store") != null,
                    "A valid shop disappeared after reload failure.");
        } catch (IOException exception) {
            helper.fail("Unexpected IO error during invalid reload test: " + exception.getMessage());
            return;
        } finally {
            try {
                CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.INVALID_SHOP_ID);
            } catch (IOException ignored) {
            }
        }

        helper.succeed();
    }
}
