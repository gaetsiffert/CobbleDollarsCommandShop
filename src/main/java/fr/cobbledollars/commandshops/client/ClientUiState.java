package fr.cobbledollars.commandshops.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.network.payload.ClientOverlayMessagePayload;
import fr.cobbledollars.commandshops.network.payload.ShopUiStatePayload;
import fr.harmex.cobbledollars.common.client.gui.screen.ShopScreen;
import fr.harmex.cobbledollars.common.client.gui.screen.widget.CategoryListWidget;
import fr.harmex.cobbledollars.common.client.gui.screen.widget.OfferListWidget;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

@EventBusSubscriber(modid = CobbleDollarsCommandShopsMod.MODID, value = Dist.CLIENT)
public final class ClientUiState {
    private static SessionState currentShopState;
    private static OverlayMessage overlayMessage;

    private ClientUiState() {
    }

    public static void acceptOverlayMessage(ClientOverlayMessagePayload payload) {
        overlayMessage = new OverlayMessage(payload.message(), payload.color(), System.currentTimeMillis() + Math.max(1L, payload.ttlMillis()));
    }

    public static void acceptShopUiState(ShopUiStatePayload payload) {
        HashMap<OfferKey, ShopUiStatePayload.OfferState> offersByKey = new HashMap<>();
        for (ShopUiStatePayload.OfferState offerState : payload.offers()) {
            offersByKey.put(new OfferKey(offerState.categoryIndex(), offerState.offerIndex()), offerState);
        }
        currentShopState = new SessionState(payload.sessionUuid(), Map.copyOf(offersByKey));
    }

    public static ShopUiStatePayload.OfferState getSelectedOfferState(ShopScreen screen) {
        SessionState sessionState = currentShopState;
        if (sessionState == null) {
            return null;
        }

        ShopMenu menu = getMenu(screen);
        if (menu == null || menu.getCobbleMerchant() == null || !sessionState.sessionUuid().equals(menu.getCobbleMerchant().getMerchantUUID())) {
            return null;
        }

        CategoryListWidget.CategoryEntry categoryEntry = screen.getCategoryList().getSelected();
        OfferListWidget.OfferEntry offerEntry = screen.getOfferList().getSelected();
        if (categoryEntry == null || offerEntry == null || offerEntry.isAddOffer()) {
            return null;
        }

        Integer categoryIndex = categoryEntry.getCategoryIndex();
        Integer offerIndex = offerEntry.getOfferIndex();
        if (categoryIndex == null || offerIndex == null) {
            return null;
        }
        return sessionState.offersByKey().get(new OfferKey(categoryIndex, offerIndex));
    }

    public static void clear() {
        currentShopState = null;
        overlayMessage = null;
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        GuiGraphics guiGraphics = event.getGuiGraphics();
        int nextLineY = 8;

        if (hasOverlayMessage(nowMillis)) {
            drawCenteredLine(guiGraphics, minecraft.font, overlayMessage.message(), overlayMessage.color(), nextLineY);
            nextLineY += 12;
        }

        if (event.getScreen() instanceof ShopScreen shopScreen) {
            ShopUiStatePayload.OfferState offerState = getSelectedOfferState(shopScreen);
            Component statusLine = ClientUiFormatter.formatSelectedOfferStatus(offerState, nowMillis);
            if (statusLine != null) {
                drawCenteredLine(guiGraphics, minecraft.font, statusLine, 16777045, nextLineY);
            }
        }
    }

    @SubscribeEvent
    public static void onHudRender(RenderGuiLayerEvent.Post event) {
        if (!VanillaGuiLayers.OVERLAY_MESSAGE.equals(event.getName())) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.font == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        if (!hasOverlayMessage(nowMillis)) {
            return;
        }

        drawCenteredLine(event.getGuiGraphics(), minecraft.font, overlayMessage.message(), overlayMessage.color(), 8);
    }

    private static boolean hasOverlayMessage(long nowMillis) {
        return overlayMessage != null && overlayMessage.expiresAtMillis() > nowMillis;
    }

    private static void drawCenteredLine(GuiGraphics guiGraphics, Font font, Component text, int color, int y) {
        String renderedText = text.getString();
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int x = (screenWidth - font.width(renderedText)) / 2;
        guiGraphics.drawString(font, renderedText, x, y, color, true);
    }

    @SuppressWarnings("unchecked")
    private static ShopMenu getMenu(ShopScreen screen) {
        return ((MenuAccess<ShopMenu>) screen).getMenu();
    }

    private record OfferKey(int categoryIndex, int offerIndex) {
    }

    private record SessionState(UUID sessionUuid, Map<OfferKey, ShopUiStatePayload.OfferState> offersByKey) {
    }

    private record OverlayMessage(Component message, int color, long expiresAtMillis) {
    }
}
