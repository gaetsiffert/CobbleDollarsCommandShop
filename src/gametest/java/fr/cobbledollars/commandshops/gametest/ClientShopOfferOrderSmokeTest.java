package fr.cobbledollars.commandshops.gametest;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.CobbleDollarsOffers;
import fr.cobbledollars.commandshops.client.ClientUiState;
import fr.cobbledollars.commandshops.network.payload.ShopUiStatePayload;
import fr.harmex.cobbledollars.common.api.shop.Category;
import fr.harmex.cobbledollars.common.api.shop.Offer;
import fr.harmex.cobbledollars.common.api.shop.Shop;
import fr.harmex.cobbledollars.common.client.gui.screen.ShopScreen;
import fr.harmex.cobbledollars.common.client.gui.screen.widget.OfferListWidget;
import fr.harmex.cobbledollars.common.world.entity.ClientSideCobbleDollarsShopHolder;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import fr.harmex.cobbledollars.common.world.item.trading.CobbleDollarsShopHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Runs against the real native widget and the applied mixin from the opt-in admin client smoke test. */
public final class ClientShopOfferOrderSmokeTest {
    private ClientShopOfferOrderSmokeTest() {
    }

    public static void verify(Minecraft minecraft) {
        Screen originalScreen = minecraft.screen;
        ClientUiState.clear();
        try {
            Category category = new Category("Configured order", List.of(
                    offer(Items.POTION, 3, 160, 0),
                    offer(Items.HONEY_BOTTLE, 2, 90, 1),
                    offer(Items.GLASS_BOTTLE, 2, 90, 2),
                    offer(Items.APPLE, 5, 250, 3)
            ));
            Inventory inventory = new Inventory(null);
            UUID sessionUuid = UUID.randomUUID();
            ClientSideCobbleDollarsShopHolder holder = new ClientSideCobbleDollarsShopHolder();
            holder.setMerchantUUID(sessionUuid);
            holder.setShopRef(new CobbleDollarsShopHolder.ShopRef.Inline(new Shop(List.of(category))));
            ShopMenu menu = new ShopMenu(1, inventory, holder);
            ShopScreen screen = new ShopScreen(menu, inventory, Component.literal("Offer order fixture"));
            screen.init(minecraft, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
            // Assign only for session matching; do not open a container or send any network packets.
            minecraft.screen = screen;
            OfferListWidget widget = screen.getOfferList();

            widget.createOfferEntries(category, false);
            assertOrder(widget, List.of(1, 2, 0, 3), "Native price ordering must remain unchanged without command session metadata.");
            widget.search("Order entry");
            assertOrder(widget, List.of(1, 2, 0, 3), "Native search must still use price ordering.");
            var selected = widget.getSelected();

            // The native packet arrives before the optional metadata during a normal open.
            ClientUiState.acceptShopUiState(new ShopUiStatePayload(sessionUuid, List.of()));
            assertOrder(widget, List.of(0, 1, 2, 3), "Late metadata must restore configured order immediately.");
            require(widget.getSelected() == selected, "Reordering must preserve the selected offer.");

            widget.search("Order entry 0");
            assertOrder(widget, List.of(0), "Search must still filter command offers.");
            widget.search("");
            assertOrder(widget, List.of(0, 1, 2, 3), "Clearing search must preserve configured order.");
            widget.createOfferEntries(category, false);
            assertOrder(widget, List.of(0, 1, 2, 3), "Recreating entries must preserve configured order.");

            Category reordered = new Category("Other configured order", List.of(
                    category.getOffers().get(3), category.getOffers().get(0),
                    category.getOffers().get(2), category.getOffers().get(1)
            ));
            menu.setShop(new CobbleDollarsShopHolder.ShopRef.Inline(new Shop(List.of(reordered))));
            widget.createOfferEntries(reordered, false);
            assertOrder(widget, List.of(0, 1, 2, 3), "Category changes must follow the current configuration.");
            require(widget.children().getFirst().getItem().is(Items.APPLE), "The reordered first configured item must be displayed first.");

            holder.setMerchantUUID(UUID.randomUUID());
            widget.createOfferEntries(category, false);
            assertOrder(widget, List.of(1, 2, 0, 3), "Another merchant must retain native ordering even after visiting a command shop.");
            CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_SHOP_OFFER_ORDER_RESULT: PASS; native order, late sync, selection, search, category changes and session isolation.");
        } finally {
            minecraft.screen = originalScreen;
            ClientUiState.clear();
        }
    }

    private static Offer offer(Item item, int count, long price, int index) {
        ItemStack stack = new ItemStack(item, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Order entry " + index));
        return CobbleDollarsOffers.createShopOffer(stack, BigInteger.valueOf(price), -1);
    }

    private static void assertOrder(OfferListWidget widget, List<Integer> expected, String message) {
        List<Integer> actual = widget.children().stream().map(OfferListWidget.OfferEntry::getOfferIndex).toList();
        require(expected.equals(actual), message + " Expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
