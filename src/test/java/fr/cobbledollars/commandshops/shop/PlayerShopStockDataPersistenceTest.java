package fr.cobbledollars.commandshops.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class PlayerShopStockDataPersistenceTest {
    @Test
    void saveAndLoadPreservesStockPerPlayer() throws ReflectiveOperationException {
        PlayerShopStockData stockData = load(new CompoundTag());
        ShopDefinition shop = createIntervalShop();
        ResolvedShopOffer offer = shop.getOfferById("emerald_bundle").createResolvedOffers().getFirst();
        UUID firstPlayer = UUID.fromString("2b31f66e-c10f-4c3e-9d3c-0a0af0c87d1b");
        UUID secondPlayer = UUID.fromString("5c3dfec4-9d34-4e52-9a44-4ac243fc9756");
        long anchorMillis = 1_700_000_000_000L;

        stockData.consumeStock(firstPlayer, shop, offer, 2, anchorMillis);
        stockData.consumeStock(secondPlayer, shop, offer, 1, anchorMillis);

        CompoundTag saved = stockData.save(new CompoundTag(), TestRegistryAccess.provider());
        PlayerShopStockData reloaded = load(saved);

        assertEquals(1, reloaded.resolveStock(firstPlayer, shop, offer, anchorMillis));
        assertEquals(2, reloaded.resolveStock(secondPlayer, shop, offer, anchorMillis));

        PlayerShopStockData.RestockPreview preview = reloaded.previewNextRestock(firstPlayer, shop, offer, anchorMillis);
        assertTrue(preview.hasNextRestock());
        assertEquals(anchorMillis + 1_000L, preview.nextRestockAtMillis());
        assertEquals(1, preview.nextRestockAmount());
    }

    @Test
    void dailyRestockPreviewSurvivesReloadAndRefillsAtBoundary() throws ReflectiveOperationException {
        PlayerShopStockData stockData = load(new CompoundTag());
        ShopDefinition shop = createDailyResetShop();
        ResolvedShopOffer offer = shop.getOfferById("diamond_bundle").createResolvedOffers().getFirst();
        UUID playerUuid = UUID.fromString("22c84ff6-0178-4c99-994b-80f2ccfa89d9");
        ZoneId zoneId = ZoneId.of("Europe/Paris");
        long beforeBoundary = ZonedDateTime.of(2026, 1, 10, 3, 59, 30, 0, zoneId).toInstant().toEpochMilli();
        long boundary = ZonedDateTime.of(2026, 1, 10, 4, 0, 0, 0, zoneId).toInstant().toEpochMilli();

        stockData.consumeStock(playerUuid, shop, offer, 3, beforeBoundary);

        CompoundTag saved = stockData.save(new CompoundTag(), TestRegistryAccess.provider());
        PlayerShopStockData reloaded = load(saved);

        PlayerShopStockData.RestockPreview preview = reloaded.previewNextRestock(playerUuid, shop, offer, beforeBoundary);
        assertTrue(preview.hasNextRestock());
        assertEquals(boundary, preview.nextRestockAtMillis());
        assertEquals(3, preview.nextRestockAmount());
        assertEquals(1, reloaded.resolveStock(playerUuid, shop, offer, beforeBoundary));
        assertEquals(4, reloaded.resolveStock(playerUuid, shop, offer, boundary));
    }

    private static PlayerShopStockData load(CompoundTag tag) throws ReflectiveOperationException {
        Method loadMethod = PlayerShopStockData.class.getDeclaredMethod(
                "load",
                CompoundTag.class,
                net.minecraft.core.HolderLookup.Provider.class
        );
        loadMethod.setAccessible(true);
        return (PlayerShopStockData) loadMethod.invoke(null, tag, TestRegistryAccess.provider());
    }

    private static ShopDefinition createIntervalShop() {
        ShopOfferDefinition offer = new ShopOfferDefinition(
                "emerald_bundle",
                ItemMatchExpression.include(new ItemMatchAtom.ExactItem(Items.EMERALD, "minecraft:emerald")),
                1,
                BigInteger.valueOf(15L),
                3,
                new RestockRule.IntervalRestockRule(1, 1L),
                ConditionSet.NONE,
                List.of()
        );
        return createShop("interval_stock_test", offer);
    }

    private static ShopDefinition createDailyResetShop() {
        ShopOfferDefinition offer = new ShopOfferDefinition(
                "diamond_bundle",
                ItemMatchExpression.include(new ItemMatchAtom.ExactItem(Items.DIAMOND, "minecraft:diamond")),
                1,
                BigInteger.valueOf(75L),
                4,
                new RestockRule.DailyRestockRule(4, 0, "Europe/Paris"),
                ConditionSet.NONE,
                List.of()
        );
        return createShop("daily_stock_test", offer);
    }

    private static ShopDefinition createShop(String shopId, ShopOfferDefinition offer) {
        return new ShopDefinition(
                shopId,
                List.of(new ShopCategoryDefinition("Default", List.of(offer), ConditionSet.NONE)),
                ConditionSet.NONE,
                null,
                Path.of(shopId + ".json")
        );
    }
}
