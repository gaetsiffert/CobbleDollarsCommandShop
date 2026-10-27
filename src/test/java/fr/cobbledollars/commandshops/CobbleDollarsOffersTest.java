package fr.cobbledollars.commandshops;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import fr.cobbledollars.commandshops.network.payload.ShopUiStatePayload;

import fr.harmex.cobbledollars.common.api.shop.Category;
import fr.harmex.cobbledollars.common.api.shop.Offer;
import fr.harmex.cobbledollars.common.api.shop.Shop;
import fr.harmex.cobbledollars.common.api.bank.Bank;
import fr.cobbledollars.commandshops.shop.BankDefinition;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class CobbleDollarsOffersTest {
    @Test
    void bundlesRetainTheirCountWithoutChangingItemComponents() {
        ItemStack template = new ItemStack(Items.BREAD, 6);
        Offer offer = CobbleDollarsOffers.createShopOffer(template, BigInteger.valueOf(24), 3);

        assertEquals(1, offer.getItems().getFirst().getCount());
        assertEquals(6, CobbleDollarsOffers.getItem(offer).getCount());
        assertEquals(template.getComponents(), offer.getItems().getFirst().getComponents());
        assertFalse(template.has(DataComponents.CUSTOM_NAME));
        assertEquals(6, template.getCount());
    }

    @Test
    void nativeStockUpdatesKeepBundleOffersValid() {
        Offer offer = CobbleDollarsOffers.createShopOffer(new ItemStack(Items.BREAD, 6), BigInteger.valueOf(24), 3);
        Shop shop = new Shop(List.of(new Category("Food", List.of(offer))));
        Offer updated = shop.editOfferStock(0, 0, 2, 0).get(0).getOffers().getFirst();

        assertEquals(2, updated.getStock());
        assertTrue(CobbleDollarsOffers.equalsWithoutStock(offer, updated));
    }

    @Test
    void nativeNetworkCodecPreservesBundleSizeAndDisplayComponents() {
        Offer offer = CobbleDollarsOffers.createShopOffer(new ItemStack(Items.BREAD, 6), BigInteger.valueOf(24), 3);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            Offer.STREAM_CODEC.encode(buffer, offer);
            Offer decoded = Offer.STREAM_CODEC.decode(buffer);
            assertEquals(6, CobbleDollarsOffers.getItem(decoded).getCount());
            assertEquals(offer.getItems().getFirst().getComponents(), decoded.getItems().getFirst().getComponents());
            assertTrue(CobbleDollarsOffers.equalsWithoutStock(offer, decoded));
        } finally {
            buffer.release();
        }
    }

    @Test
    void emptyCustomBankSurvivesNativeNetworkSerialization() {
        assertEmptyBankRejectsEveryStack(BankDefinition.emptyBank());
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            Bank.DIRECT_STREAM_CODEC.encode(buffer, BankDefinition.emptyBank());
            Bank decoded = Bank.DIRECT_STREAM_CODEC.decode(buffer);
            assertFalse(decoded.getOffers().isEmpty());
            assertEmptyBankRejectsEveryStack(decoded);
        } finally {
            buffer.release();
        }
    }

    private static void assertEmptyBankRejectsEveryStack(Bank bank) {
        assertFalse(bank.getOffers().isEmpty(), "Avoid the native global-bank fallback");
        assertFalse(bank.contains(ItemStack.EMPTY), "Empty inventory slots must never glow green");
        assertNull(bank.getOffer(ItemStack.EMPTY));
        for (var item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            assertFalse(bank.contains(stack), () -> "Empty bank accepted " + BuiltInRegistries.ITEM.getKey(item));
            assertNull(bank.getOffer(stack));
        }
        for (var item : List.of(Items.STONE, Items.DIAMOND_SWORD)) {
            for (int maxStackSize : List.of(1, 16, 64, 99)) {
                ItemStack stack = new ItemStack(item);
                stack.set(DataComponents.MAX_STACK_SIZE, maxStackSize);
                assertFalse(bank.contains(stack), "Component overrides must not satisfy the contradictory rule");
                assertNull(bank.getOffer(stack));
            }
        }
    }

    @Test
    void optionalUiMetadataPreservesBundleSizeAfterNativeStockUpdates() {
        var state = new ShopUiStatePayload.OfferState(0, 1, 2, 6, -1L, 0, null, List.of());
        var payload = new ShopUiStatePayload(UUID.randomUUID(), List.of(state));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            ShopUiStatePayload.STREAM_CODEC.encode(buffer, payload);
            var decoded = ShopUiStatePayload.STREAM_CODEC.decode(buffer);
            assertEquals(payload, decoded);
            assertEquals(6, decoded.offers().getFirst().bundleCount());
        } finally {
            buffer.release();
        }
    }

    @Test
    void offerValidationStillChecksPriceItemAndComponents() {
        Offer expected = new Offer(new ItemStack(Items.DIAMOND_SWORD), BigInteger.TEN, 3);
        assertTrue(CobbleDollarsOffers.equalsWithoutStock(expected, expected.editStock(1)));
        assertFalse(CobbleDollarsOffers.equalsWithoutStock(expected, expected.editPrice(BigInteger.ONE)));
        assertFalse(CobbleDollarsOffers.equalsWithoutStock(expected,
                new Offer(new ItemStack(Items.IRON_SWORD), BigInteger.TEN, 3)));
        ItemStack renamed = new ItemStack(Items.DIAMOND_SWORD);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
        assertFalse(CobbleDollarsOffers.equalsWithoutStock(expected, new Offer(renamed, BigInteger.TEN, 3)));
    }
}
