package fr.cobbledollars.commandshops.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShopFilesParsingTest {
    @TempDir
    Path tempDir;

    @Test
    void parseShopFileLoadsFiniteStockRestockAndPurchaseBonuses() throws IOException {
        Path shopFile = writeShopJson("""
                {
                  "categories": [
                    {
                      "name": "General",
                      "offers": [
                        {
                          "id": "emerald_bundle",
                          "match": {
                            "include": [
                              { "item": "minecraft:emerald" }
                            ]
                          },
                          "count": 2,
                          "price": 15,
                          "stock": 5,
                          "restock": {
                            "type": "interval",
                            "amount": 2,
                            "every_seconds": 30
                          },
                          "purchase_bonuses": [
                            {
                              "required_bundles": 2,
                              "rewards": [
                                {
                                  "item": "minecraft:diamond",
                                  "count": 1
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        ShopDefinition shop = ShopFiles.parseShopFile("test_shop", shopFile, TestRegistryAccess.provider());
        ShopOfferDefinition offer = shop.getOfferById("emerald_bundle");

        assertEquals("test_shop", shop.id());
        assertEquals(1, shop.categories().size());
        assertEquals(1, shop.offers().size());
        assertEquals(2, offer.count());
        assertEquals(5, offer.stock());
        assertInstanceOf(RestockRule.IntervalRestockRule.class, offer.restockRule());
        assertEquals(1, offer.purchaseBonuses().size());
        assertEquals(2, offer.purchaseBonuses().getFirst().requiredBundles());
    }

    @Test
    void parseShopFileRejectsRestockWithoutFiniteStock() throws IOException {
        Path shopFile = writeShopJson("""
                {
                  "categories": [
                    {
                      "name": "Broken",
                      "offers": [
                        {
                          "id": "emerald_bundle",
                          "match": {
                            "include": [
                              { "item": "minecraft:emerald" }
                            ]
                          },
                          "price": 15,
                          "restock": {
                            "type": "interval",
                            "amount": 1,
                            "every_seconds": 60
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        IOException exception = assertThrows(
                IOException.class,
                () -> ShopFiles.parseShopFile("test_shop", shopFile, TestRegistryAccess.provider())
        );

        assertEquals(
                "Field 'stock' in shop 'test_shop', category 'Broken', offer 'emerald_bundle' must be set when 'restock' is used.",
                exception.getMessage()
        );
    }

    @Test
    void parseShopFileRejectsInvalidDailyRestockZone() throws IOException {
        Path shopFile = writeShopJson("""
                {
                  "categories": [
                    {
                      "name": "Broken",
                      "offers": [
                        {
                          "id": "diamond_bundle",
                          "match": {
                            "include": [
                              { "item": "minecraft:diamond" }
                            ]
                          },
                          "price": 75,
                          "stock": 2,
                          "restock": {
                            "type": "daily_reset",
                            "hour": 4,
                            "minute": 0,
                            "time_zone": "Mars/Olympus"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        IOException exception = assertThrows(
                IOException.class,
                () -> ShopFiles.parseShopFile("test_shop", shopFile, TestRegistryAccess.provider())
        );

        assertEquals(
                "Field 'time_zone' in shop 'test_shop', category 'Broken', offer 'diamond_bundle', restock is invalid: Mars/Olympus",
                exception.getMessage()
        );
    }

    @Test
    void parseShopFileRejectsPurchaseBonusThresholdAboveFiniteStock() throws IOException {
        Path shopFile = writeShopJson("""
                {
                  "categories": [
                    {
                      "name": "Broken",
                      "offers": [
                        {
                          "id": "bread_bundle",
                          "match": {
                            "include": [
                              { "item": "minecraft:bread" }
                            ]
                          },
                          "price": 10,
                          "stock": 2,
                          "purchase_bonuses": [
                            {
                              "required_bundles": 3,
                              "rewards": [
                                {
                                  "item": "minecraft:apple",
                                  "count": 1
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        IOException exception = assertThrows(
                IOException.class,
                () -> ShopFiles.parseShopFile("test_shop", shopFile, TestRegistryAccess.provider())
        );

        assertEquals(
                "Field 'purchase_bonuses.required_bundles' in shop 'test_shop', category 'Broken', offer 'bread_bundle' cannot be greater than finite stock 2.",
                exception.getMessage()
        );
    }

    private Path writeShopJson(String json) throws IOException {
        Path shopFile = tempDir.resolve("shop.json");
        Files.writeString(shopFile, json);
        return shopFile;
    }
}
