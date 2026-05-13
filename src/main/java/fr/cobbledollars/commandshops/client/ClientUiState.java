package fr.cobbledollars.commandshops.client;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.network.payload.BankUiStatePayload;
import fr.cobbledollars.commandshops.network.payload.ClientOverlayMessagePayload;
import fr.cobbledollars.commandshops.network.payload.ShopUiStatePayload;
import fr.harmex.cobbledollars.common.client.config.ClientShopConfig;
import fr.harmex.cobbledollars.common.client.gui.screen.BankScreen;
import fr.harmex.cobbledollars.common.client.gui.screen.ShopScreen;
import fr.harmex.cobbledollars.common.client.gui.screen.widget.CategoryListWidget;
import fr.harmex.cobbledollars.common.client.gui.screen.widget.OfferListWidget;
import fr.harmex.cobbledollars.common.world.inventory.BankMenu;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Bank;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = CobbleDollarsCommandShopsMod.MODID, value = Dist.CLIENT)
public final class ClientUiState {
    private static final int BANK_SCREEN_WIDTH = 170;
    private static final int BANK_SCREEN_HEIGHT = 204;
    private static final int TOAST_MAX_WIDTH = 220;
    private static final int HUD_MARGIN = 10;
    private static final int PANEL_MARGIN = 6;
    private static final int SLOT_OUTLINE_COLOR = 0xFF55AA55;
    private static final int SLOT_FILL_COLOR = 0x22228822;
    private static final int TEXT_PRIMARY = 0xFFF3F4F8;
    private static final int TEXT_MUTED = 0xFFB7C8B8;
    private static final int TEXT_GOLD = 0xFFF5C75D;
    private static final int MODAL_SCRIM = 0xE0000000;
    private static final int MODAL_BACKGROUND = 0xFF162018;
    private static final int MODAL_BORDER = 0xFF55AA55;
    private static final int MODAL_TITLE_BAR = 0xFF102214;
    private static final int BUTTON_BACKGROUND = 0xFF223322;
    private static final int BUTTON_BACKGROUND_HOVER = 0xFF335533;
    private static final int BUTTON_BORDER = 0xFF55AA55;
    private static final int BUTTON_TEXT = 0xFF6CD987;
    private static final int ACCEPTED_ITEMS_PER_PAGE = 8;
    private static final float POPUP_LAYER_Z = 4000.0F;

    private static SessionState currentShopState;
    private static ClientBankState currentBankState;
    private static OverlayMessage overlayMessage;
    private static BankScreen trackedBankScreen;
    private static boolean acceptedItemsModalOpen;
    private static int acceptedItemsPage;
    private static String acceptedItemsSearchQuery = "";
    private static boolean acceptedItemsSearchFocused;
    private static boolean suppressBankTooltipAugment;
    private static EditBox acceptedItemsSearchBox;
    private static boolean modalRenderHandledInPre;

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

    public static void acceptBankUiState(BankUiStatePayload payload) {
        currentBankState = ClientBankState.fromPayload(payload);
    }

    public static void clear() {
        currentShopState = null;
        currentBankState = null;
        overlayMessage = null;
        trackedBankScreen = null;
        acceptedItemsModalOpen = false;
        acceptedItemsPage = 0;
        acceptedItemsSearchQuery = "";
        acceptedItemsSearchFocused = false;
        suppressBankTooltipAugment = false;
        acceptedItemsSearchBox = null;
        modalRenderHandledInPre = false;
    }

    public static boolean renderCustomShopOfferTooltip(Offer offer, Minecraft minecraft, GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (!(minecraft.screen instanceof ShopScreen shopScreen) || minecraft.font == null) {
            return false;
        }

        OfferContext context = resolveOfferContext(shopScreen, offer);
        ShopUiStatePayload.OfferState offerState = context != null ? context.offerState() : null;
        int stock = offerState != null ? offerState.stock() : offer.getStock();

        ArrayList<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, offer.getItem()));
        if (lines.isEmpty()) {
            lines.add(offer.getItem().getHoverName());
        }

        lines.set(0, buildOfferHeadline(offer, stock));
        lines.add(Component.empty());
        lines.add(labelValue("cobbledollarscommandshops.ui.label.unit_price", ClientUiFormatter.formatMoney(offer.getPrice()))
                .withStyle(style -> style.withColor(TEXT_GOLD)));
        lines.add(labelValue("cobbledollarscommandshops.ui.label.stock", formatStockValue(stock)));
        if (offerState != null && offerState.hasNextRestock()) {
            lines.add(Component.translatable(
                    "cobbledollarscommandshops.ui.tooltip.restock_at",
                    offerState.nextRestockAmount(),
                    ClientUiFormatter.formatAbsoluteRestockTime(offerState)
            ));
            lines.add(Component.translatable(
                    "cobbledollarscommandshops.ui.tooltip.restock_in",
                    ClientUiFormatter.formatDuration(Math.max(0L, offerState.nextRestockAtMillis() - System.currentTimeMillis()))
            ));
        }

        Optional<TooltipComponent> tooltipImage = offer.getItem().getTooltipImage();
        guiGraphics.renderTooltip(minecraft.font, lines, tooltipImage, offer.getItem(), mouseX, mouseY);
        return true;
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof BankScreen)) {
            return;
        }
        if (suppressBankTooltipAugment) {
            return;
        }

        Offer offer = getClientBankOffer(event.getItemStack());
        if (offer == null) {
            return;
        }

        List<Component> toolTip = event.getToolTip();
        toolTip.add(Component.empty());
        toolTip.add(labelValue("cobbledollarscommandshops.ui.label.unit_price", ClientUiFormatter.formatMoney(offer.getPrice()))
                .withStyle(style -> style.withColor(TEXT_GOLD)));
        toolTip.add(labelValue(
                "cobbledollarscommandshops.ui.label.stack_value",
                ClientUiFormatter.formatMoney(offer.getPrice().multiply(BigInteger.valueOf(event.getItemStack().getCount())))
        ));
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BankScreen bankScreen)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font == null) {
            return;
        }

        acceptedItemsSearchBox = new EditBox(minecraft.font, 0, 0, 10, 12, Component.translatable("cobbledollarscommandshops.ui.bank.search_placeholder"));
        acceptedItemsSearchBox.setMaxLength(80);
        acceptedItemsSearchBox.setBordered(false);
        acceptedItemsSearchBox.setTextColor(TEXT_PRIMARY);
        acceptedItemsSearchBox.setTextColorUneditable(TEXT_MUTED);
        acceptedItemsSearchBox.setHint(Component.translatable("cobbledollarscommandshops.ui.bank.search_placeholder"));
        acceptedItemsSearchBox.setResponder(value -> {
            acceptedItemsSearchQuery = value;
            acceptedItemsPage = 0;
        });
        acceptedItemsSearchBox.setValue(acceptedItemsSearchQuery);
        acceptedItemsSearchBox.setFocused(false);
        trackBankScreen(bankScreen);
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof BankScreen) {
            acceptedItemsSearchBox = null;
            acceptedItemsModalOpen = false;
            acceptedItemsSearchFocused = false;
            acceptedItemsSearchQuery = "";
            acceptedItemsPage = 0;
            currentBankState = null;
            modalRenderHandledInPre = false;
        }
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof BankScreen bankScreen) || !acceptedItemsModalOpen) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null) {
            return;
        }

        event.setCanceled(true);
        modalRenderHandledInPre = true;

        long nowMillis = System.currentTimeMillis();
        OverlayMessage activeOverlay = getOverlayMessage(nowMillis);
        GuiGraphics guiGraphics = event.getGuiGraphics();

        bankScreen.render(guiGraphics, -10_000, -10_000, event.getPartialTick());
        if (activeOverlay != null) {
            renderOverlayToast(guiGraphics, minecraft.font, activeOverlay, nowMillis, HUD_MARGIN, HUD_MARGIN);
        }
        renderAcceptedSlotHighlights(guiGraphics, bankScreen);
        renderAcceptedItemsButton(guiGraphics, minecraft.font, bankScreen, event.getMouseX(), event.getMouseY());
        renderAcceptedItemsModal(guiGraphics, minecraft.font, bankScreen, event.getMouseX(), event.getMouseY());
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (modalRenderHandledInPre && event.getScreen() instanceof BankScreen) {
            modalRenderHandledInPre = false;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        OverlayMessage activeOverlay = getOverlayMessage(nowMillis);
        GuiGraphics guiGraphics = event.getGuiGraphics();

        if (event.getScreen() instanceof BankScreen bankScreen) {
            trackBankScreen(bankScreen);
            if (activeOverlay != null) {
                renderOverlayToast(guiGraphics, minecraft.font, activeOverlay, nowMillis, HUD_MARGIN, HUD_MARGIN);
            }
            renderAcceptedSlotHighlights(guiGraphics, bankScreen);
            renderAcceptedItemsButton(guiGraphics, minecraft.font, bankScreen, event.getMouseX(), event.getMouseY());
            if (acceptedItemsModalOpen) {
                renderAcceptedItemsModal(guiGraphics, minecraft.font, bankScreen, event.getMouseX(), event.getMouseY());
            }
            return;
        }

        trackedBankScreen = null;
        acceptedItemsModalOpen = false;
        acceptedItemsPage = 0;
        acceptedItemsSearchFocused = false;

        if (activeOverlay != null) {
            renderOverlayToast(guiGraphics, minecraft.font, activeOverlay, nowMillis, HUD_MARGIN, HUD_MARGIN);
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
        OverlayMessage activeOverlay = getOverlayMessage(nowMillis);
        if (activeOverlay == null) {
            return;
        }

        renderOverlayToast(event.getGuiGraphics(), minecraft.font, activeOverlay, nowMillis, HUD_MARGIN, HUD_MARGIN);
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof BankScreen bankScreen) || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }

        int mouseX = (int) Math.round(event.getMouseX());
        int mouseY = (int) Math.round(event.getMouseY());
        Rect buttonRect = getAcceptedItemsButtonRect(Minecraft.getInstance(), bankScreen);
        if (buttonRect.contains(mouseX, mouseY)) {
            acceptedItemsModalOpen = true;
            acceptedItemsSearchFocused = true;
            ensureAcceptedItemsSearchBox();
            if (acceptedItemsSearchBox != null) {
                acceptedItemsSearchBox.setFocused(true);
            }
            event.setCanceled(true);
            return;
        }

        if (!acceptedItemsModalOpen) {
            return;
        }

        AcceptedItemsModalLayout layout = getAcceptedItemsModalLayout(Minecraft.getInstance());
        if (layout.searchRect().contains(mouseX, mouseY)) {
            acceptedItemsSearchFocused = true;
            if (acceptedItemsSearchBox != null) {
                acceptedItemsSearchBox.mouseClicked(mouseX, mouseY, event.getButton());
                acceptedItemsSearchFocused = acceptedItemsSearchBox.isFocused();
            }
            event.setCanceled(true);
            return;
        }
        if (layout.closeRect().contains(mouseX, mouseY)) {
            acceptedItemsModalOpen = false;
            acceptedItemsSearchFocused = false;
            event.setCanceled(true);
            return;
        }
        if (layout.previousRect() != null && layout.previousRect().contains(mouseX, mouseY)) {
            acceptedItemsPage = Math.max(0, acceptedItemsPage - 1);
            event.setCanceled(true);
            return;
        }
        if (layout.nextRect() != null && layout.nextRect().contains(mouseX, mouseY)) {
            acceptedItemsPage = Math.min(getAcceptedItemsPageCount() - 1, acceptedItemsPage + 1);
            event.setCanceled(true);
            return;
        }

        if (!layout.panelRect().contains(mouseX, mouseY)) {
            acceptedItemsModalOpen = false;
            acceptedItemsSearchFocused = false;
            event.setCanceled(true);
            return;
        }

        acceptedItemsSearchFocused = false;
        if (acceptedItemsSearchBox != null) {
            acceptedItemsSearchBox.setFocused(false);
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof BankScreen) || !acceptedItemsModalOpen) {
            return;
        }

        AcceptedItemsModalLayout layout = getAcceptedItemsModalLayout(Minecraft.getInstance());
        int mouseX = (int) Math.round(event.getMouseX());
        int mouseY = (int) Math.round(event.getMouseY());
        if (!layout.panelRect().contains(mouseX, mouseY)) {
            return;
        }

        double deltaY = event.getScrollDeltaY();
        if (deltaY > 0.0D) {
            changeAcceptedItemsPage(-1);
        } else if (deltaY < 0.0D) {
            changeAcceptedItemsPage(1);
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof BankScreen) || !acceptedItemsModalOpen) {
            return;
        }
        if (event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE || Minecraft.getInstance().options.keyInventory.matches(event.getKeyCode(), event.getScanCode())) {
            acceptedItemsModalOpen = false;
            acceptedItemsSearchFocused = false;
            if (acceptedItemsSearchBox != null) {
                acceptedItemsSearchBox.setFocused(false);
            }
            event.setCanceled(true);
            return;
        }
        if (acceptedItemsSearchFocused && acceptedItemsSearchBox != null && acceptedItemsSearchBox.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (!(event.getScreen() instanceof BankScreen) || !acceptedItemsModalOpen || !acceptedItemsSearchFocused) {
            return;
        }
        if (acceptedItemsSearchBox != null && acceptedItemsSearchBox.charTyped(event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    private static void trackBankScreen(BankScreen bankScreen) {
        if (trackedBankScreen != bankScreen) {
            trackedBankScreen = bankScreen;
            currentBankState = null;
            acceptedItemsModalOpen = false;
            acceptedItemsPage = 0;
            acceptedItemsSearchQuery = "";
            acceptedItemsSearchFocused = false;
            if (acceptedItemsSearchBox != null) {
                acceptedItemsSearchBox.setValue("");
                acceptedItemsSearchBox.setFocused(false);
            }
        }
    }

    private static OfferContext resolveOfferContext(ShopScreen screen, Offer targetOffer) {
        CategoryListWidget.CategoryEntry categoryEntry = screen.getCategoryList().getSelected();
        if (categoryEntry == null || categoryEntry.getCategoryIndex() == null) {
            return null;
        }

        int categoryIndex = categoryEntry.getCategoryIndex();
        for (OfferListWidget.OfferEntry entry : screen.getOfferList().children()) {
            if (entry == null || entry.isAddOffer() || entry.getOfferIndex() == null) {
                continue;
            }

            Offer entryOffer = entry.getOffer();
            if (entryOffer != targetOffer && !entryOffer.equalsWithoutStock(targetOffer)) {
                continue;
            }

            return new OfferContext(entryOffer, getOfferState(screen, categoryIndex, entry.getOfferIndex()));
        }
        return null;
    }

    private static ShopUiStatePayload.OfferState getOfferState(ShopScreen screen, int categoryIndex, int offerIndex) {
        SessionState sessionState = currentShopState;
        if (sessionState == null) {
            return null;
        }

        ShopMenu menu = getShopMenu(screen);
        if (menu == null || menu.getCobbleMerchant() == null || !sessionState.sessionUuid().equals(menu.getCobbleMerchant().getMerchantUUID())) {
            return null;
        }

        return sessionState.offersByKey().get(new OfferKey(categoryIndex, offerIndex));
    }

    private static MutableComponent buildOfferHeadline(Offer offer, int stock) {
        MutableComponent headline = colored(offer.getItem().getHoverName().copy(), 0xFF55FFFF)
                .append(Component.literal(" $ " + offer.getPrice()).withStyle(style -> style.withColor(0xFF55FF55)));
        if (stock == 0) {
            headline.append(Component.literal(" ")).append(
                    Component.translatable("cobbledollarscommandshops.ui.value.out_of_stock")
                            .withStyle(style -> style.withColor(0xFFFF6666))
            );
        }
        return headline;
    }

    private static Component formatStockValue(int stock) {
        if (stock < 0) {
            return Component.translatable("cobbledollarscommandshops.ui.value.unlimited");
        }
        if (stock == 0) {
            return Component.translatable("cobbledollarscommandshops.ui.value.out_of_stock")
                    .withStyle(style -> style.withColor(0xFFFF6666));
        }
        return Component.literal(Integer.toString(stock))
                .withStyle(style -> style.withColor(0xFF6CD987));
    }

    private static void renderAcceptedSlotHighlights(GuiGraphics guiGraphics, BankScreen screen) {
        BankMenu menu = getBankMenu(screen);
        if (menu == null) {
            return;
        }

        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || getClientBankOffer(stack) == null) {
                continue;
            }

            int slotX = screen.getX() + slot.x;
            int slotY = screen.getY() + slot.y;
            guiGraphics.fill(slotX + 1, slotY + 1, slotX + 15, slotY + 15, SLOT_FILL_COLOR);
            drawSlotOutline(guiGraphics, slotX, slotY, SLOT_OUTLINE_COLOR);
        }
    }

    private static void renderAcceptedItemsButton(GuiGraphics guiGraphics, Font font, BankScreen screen, int mouseX, int mouseY) {
        Rect rect = getAcceptedItemsButtonRect(Minecraft.getInstance(), screen);
        boolean hovered = rect.contains(mouseX, mouseY);
        drawButton(guiGraphics, font, rect, Component.translatable("cobbledollarscommandshops.ui.bank.accepted_button").getString(), hovered);
    }

    private static void renderAcceptedItemsModal(GuiGraphics guiGraphics, Font font, BankScreen screen, int mouseX, int mouseY) {
        AcceptedItemsModalLayout layout = getAcceptedItemsModalLayout(Minecraft.getInstance());
        ensureAcceptedItemsSearchBox();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, POPUP_LAYER_Z);
        guiGraphics.flush();
        guiGraphics.fill(0, 0, guiGraphics.guiWidth(), guiGraphics.guiHeight(), MODAL_SCRIM);

        drawPanel(guiGraphics, layout.panelRect(), MODAL_BACKGROUND, MODAL_BORDER);
        guiGraphics.fill(layout.headerRect().x(), layout.headerRect().y(), layout.headerRect().right(), layout.headerRect().bottom(), MODAL_TITLE_BAR);
        guiGraphics.drawString(font, Component.translatable("cobbledollarscommandshops.ui.panel.bank.accepted"), layout.headerRect().x() + 6, layout.headerRect().y() + 3, TEXT_PRIMARY, true);

        boolean closeHovered = layout.closeRect().contains(mouseX, mouseY);
        guiGraphics.drawString(font, "x", layout.closeRect().x(), layout.closeRect().y(), closeHovered ? 0xFFFF7777 : 0xFFCCCCCC, false);
        renderSearchBox(guiGraphics, font, layout.searchRect(), mouseX, mouseY);

        List<AcceptedItemEntry> entries = getAcceptedItemEntriesForPage();
        int startX = layout.contentRect().x();
        int startY = layout.contentRect().y();
        int cellWidth = 112;
        int cellHeight = 22;
        int gapX = 10;
        int gapY = 6;
        Offer hoveredOffer = null;

        for (int index = 0; index < entries.size(); index++) {
            int column = index % 2;
            int row = index / 2;
            int cellX = startX + (column * (cellWidth + gapX));
            int cellY = startY + (row * (cellHeight + gapY));
            Rect cellRect = new Rect(cellX, cellY, cellWidth, cellHeight);
            boolean hovered = cellRect.contains(mouseX, mouseY);
            guiGraphics.fill(cellRect.x(), cellRect.y(), cellRect.right(), cellRect.bottom(), hovered ? 0xFF26442C : 0xFF1B261D);
            drawThinBorder(guiGraphics, cellRect, hovered ? 0xFF73C77A : 0xFF46724B);
            guiGraphics.renderItem(entries.get(index).offer().getItem(), cellRect.x() + 4, cellRect.y() + 3);
            guiGraphics.drawString(font, trimToWidth(font, entries.get(index).name(), 84), cellRect.x() + 24, cellRect.y() + 3, TEXT_PRIMARY, false);
            guiGraphics.drawString(font, ClientUiFormatter.formatCompactMoney(entries.get(index).offer().getPrice()), cellRect.x() + 24, cellRect.y() + 12, TEXT_GOLD, false);
            if (hovered) {
                hoveredOffer = entries.get(index).offer();
            }
        }

        if (entries.isEmpty()) {
            guiGraphics.drawCenteredString(
                    font,
                    Component.translatable("cobbledollarscommandshops.ui.bank.no_results"),
                    layout.contentRect().x() + layout.contentRect().width() / 2,
                    layout.contentRect().y() + 38,
                    TEXT_MUTED
            );
        }

        int pageCount = getAcceptedItemsPageCount();
        if (pageCount > 1) {
            Rect previousRect = layout.previousRect();
            Rect nextRect = layout.nextRect();
            boolean previousHovered = previousRect != null && previousRect.contains(mouseX, mouseY);
            boolean nextHovered = nextRect != null && nextRect.contains(mouseX, mouseY);
            if (previousRect != null) {
                drawButton(guiGraphics, font, previousRect, "<", previousHovered);
            }
            if (nextRect != null) {
                drawButton(guiGraphics, font, nextRect, ">", nextHovered);
            }
            String pageLabel = Component.translatable("cobbledollarscommandshops.ui.bank.page", acceptedItemsPage + 1, pageCount).getString();
            guiGraphics.drawCenteredString(font, pageLabel, layout.footerRect().x() + layout.footerRect().width() / 2, layout.footerRect().y() + 3, TEXT_MUTED);
        }

        if (hoveredOffer != null) {
            guiGraphics.renderTooltip(font, buildAcceptedOfferTooltip(hoveredOffer), hoveredOffer.getItem().getTooltipImage(), hoveredOffer.getItem(), mouseX, mouseY);
        }
        guiGraphics.flush();
        guiGraphics.pose().popPose();
    }

    private static List<Component> buildAcceptedOfferTooltip(Offer offer) {
        suppressBankTooltipAugment = true;
        try {
            ArrayList<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), offer.getItem()));
            lines.add(Component.empty());
            lines.add(labelValue("cobbledollarscommandshops.ui.label.unit_price", ClientUiFormatter.formatMoney(offer.getPrice()))
                    .withStyle(style -> style.withColor(TEXT_GOLD)));
            return lines;
        } finally {
            suppressBankTooltipAugment = false;
        }
    }

    private static Rect getAcceptedItemsButtonRect(Minecraft minecraft, BankScreen screen) {
        Font font = minecraft.font;
        String label = Component.translatable("cobbledollarscommandshops.ui.bank.accepted_button").getString();
        int width = Math.max(68, font.width(label) + 10);
        int height = 12;
        int centeredX = screen.getX() + (BANK_SCREEN_WIDTH - width) / 2;
        int x = Math.max(PANEL_MARGIN, Math.min(centeredX, minecraft.getWindow().getGuiScaledWidth() - PANEL_MARGIN - width));
        int preferredY = screen.getY() + BANK_SCREEN_HEIGHT + 4;
        int y = Math.min(preferredY, minecraft.getWindow().getGuiScaledHeight() - PANEL_MARGIN - height);
        return new Rect(x, y, width, height);
    }

    private static AcceptedItemsModalLayout getAcceptedItemsModalLayout(Minecraft minecraft) {
        int width = 250;
        int height = 176;
        int x = (minecraft.getWindow().getGuiScaledWidth() - width) / 2;
        int y = (minecraft.getWindow().getGuiScaledHeight() - height) / 2;
        Rect panel = new Rect(x, y, width, height);
        Rect header = new Rect(x + 1, y + 1, width - 2, 14);
        Rect search = new Rect(x + 10, y + 20, width - 20, 14);
        Rect content = new Rect(x + 10, y + 40, width - 20, 106);
        Rect footer = new Rect(x + 10, y + height - 18, width - 20, 12);
        Rect close = new Rect(x + width - 12, y + 3, 8, 8);
        int pageCount = getAcceptedItemsPageCount();
        Rect previous = pageCount > 1 ? new Rect(footer.x(), footer.y(), 14, 12) : null;
        Rect next = pageCount > 1 ? new Rect(footer.right() - 14, footer.y(), 14, 12) : null;
        return new AcceptedItemsModalLayout(panel, header, search, content, footer, close, previous, next);
    }

    private static List<AcceptedItemEntry> getAcceptedItemEntriesForPage() {
        List<AcceptedItemEntry> filteredEntries = getFilteredAcceptedItemEntries();
        int pageCount = getAcceptedItemsPageCount();
        acceptedItemsPage = Math.max(0, Math.min(acceptedItemsPage, pageCount - 1));
        int start = acceptedItemsPage * ACCEPTED_ITEMS_PER_PAGE;
        int end = Math.min(filteredEntries.size(), start + ACCEPTED_ITEMS_PER_PAGE);
        return new ArrayList<>(filteredEntries.subList(start, end));
    }

    private static int getAcceptedItemsPageCount() {
        int size = getFilteredAcceptedItemEntries().size();
        return Math.max(1, (size + ACCEPTED_ITEMS_PER_PAGE - 1) / ACCEPTED_ITEMS_PER_PAGE);
    }

    private static List<AcceptedItemEntry> getFilteredAcceptedItemEntries() {
        List<Offer> offers = getClientBankOffers();
        ArrayList<AcceptedItemEntry> entries = new ArrayList<>(offers.size());
        for (Offer offer : offers) {
            entries.add(new AcceptedItemEntry(offer, offer.getItem().getHoverName().getString()));
        }
        entries.sort(Comparator
                .comparing(ClientUiState::getAcceptedItemModSortKey, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AcceptedItemEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ClientUiState::getAcceptedItemRegistrySortKey, String.CASE_INSENSITIVE_ORDER));
        String query = acceptedItemsSearchQuery.trim().toLowerCase();
        if (query.isEmpty()) {
            return entries;
        }

        ArrayList<AcceptedItemEntry> filtered = new ArrayList<>();
        for (AcceptedItemEntry entry : entries) {
            if (entry.name().toLowerCase().contains(query)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    private static void renderSearchBox(GuiGraphics guiGraphics, Font font, Rect rect, int mouseX, int mouseY) {
        boolean hovered = rect.contains(mouseX, mouseY);
        int borderColor = acceptedItemsSearchFocused ? 0xFF73C77A : hovered ? 0xFF60AE66 : 0xFF46724B;
        guiGraphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), 0xFF1B261D);
        drawThinBorder(guiGraphics, rect, borderColor);
        if (acceptedItemsSearchBox != null) {
            acceptedItemsSearchBox.setX(rect.x() + 3);
            acceptedItemsSearchBox.setY(rect.y() + 2);
            acceptedItemsSearchBox.setWidth(rect.width() - 6);
            acceptedItemsSearchBox.setHeight(rect.height() - 4);
            acceptedItemsSearchBox.setFocused(acceptedItemsSearchFocused);
            acceptedItemsSearchBox.renderWidget(guiGraphics, mouseX, mouseY, 0.0F);
        }
    }

    private static void ensureAcceptedItemsSearchBox() {
        if (acceptedItemsSearchBox != null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font == null) {
            return;
        }

        acceptedItemsSearchBox = new EditBox(minecraft.font, 0, 0, 10, 12, Component.translatable("cobbledollarscommandshops.ui.bank.search_placeholder"));
        acceptedItemsSearchBox.setMaxLength(80);
        acceptedItemsSearchBox.setBordered(false);
        acceptedItemsSearchBox.setTextColor(TEXT_PRIMARY);
        acceptedItemsSearchBox.setTextColorUneditable(TEXT_MUTED);
        acceptedItemsSearchBox.setHint(Component.translatable("cobbledollarscommandshops.ui.bank.search_placeholder"));
        acceptedItemsSearchBox.setResponder(value -> {
            acceptedItemsSearchQuery = value;
            acceptedItemsPage = 0;
        });
        acceptedItemsSearchBox.setValue(acceptedItemsSearchQuery);
        acceptedItemsSearchBox.setFocused(acceptedItemsSearchFocused);
    }

    private static void changeAcceptedItemsPage(int delta) {
        int pageCount = getAcceptedItemsPageCount();
        if (pageCount <= 1) {
            acceptedItemsPage = 0;
            return;
        }
        acceptedItemsPage = Math.max(0, Math.min(acceptedItemsPage + delta, pageCount - 1));
    }

    private static String getAcceptedItemModSortKey(AcceptedItemEntry entry) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(entry.offer().getItem().getItem());
        return itemId.getNamespace();
    }

    private static String getAcceptedItemRegistrySortKey(AcceptedItemEntry entry) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(entry.offer().getItem().getItem());
        return itemId.toString().toLowerCase(Locale.ROOT);
    }

    private static Bank getClientBank() {
        Bank bank = ClientShopConfig.INSTANCE.getBank();
        return bank == null ? new Bank() : bank;
    }

    private static List<Offer> getClientBankOffers() {
        ClientBankState bankState = currentBankState;
        if (bankState != null) {
            return bankState.offers();
        }
        return getClientBank();
    }

    private static Offer getClientBankOffer(ItemStack stack) {
        ClientBankState bankState = currentBankState;
        if (bankState != null) {
            return bankState.get(stack);
        }
        return getClientBank().get(stack);
    }

    private static OverlayMessage getOverlayMessage(long nowMillis) {
        if (overlayMessage == null) {
            return null;
        }
        if (overlayMessage.expiresAtMillis() <= nowMillis) {
            overlayMessage = null;
            return null;
        }
        return overlayMessage;
    }

    private static void renderOverlayToast(GuiGraphics guiGraphics, Font font, OverlayMessage activeOverlay, long nowMillis, int x, int y) {
        int toastAlpha = getToastAlpha(activeOverlay, nowMillis);
        int messageColor = withAlpha(normalizeColor(activeOverlay.color()), toastAlpha);
        int accentColor = withAlpha(normalizeColor(activeOverlay.color()), Math.max(140, toastAlpha));
        int textWidth = Math.min(TOAST_MAX_WIDTH - 20, Math.max(120, font.width(activeOverlay.message().getString()) + 8));
        List<FormattedCharSequence> lines = font.split(activeOverlay.message(), textWidth);
        int height = 12 + (lines.size() * 10);

        drawPanel(guiGraphics, new Rect(x, y, textWidth + 18, height), withAlpha(0xFF171A22, Math.min(toastAlpha, 232)), withAlpha(0xFF2B3240, toastAlpha));
        guiGraphics.fill(x, y, x + 4, y + height, accentColor);
        int lineY = y + 6;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(font, line, x + 10, lineY, messageColor, false);
            lineY += 10;
        }
    }

    private static void drawPanel(GuiGraphics guiGraphics, Rect rect, int backgroundColor, int borderColor) {
        guiGraphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), backgroundColor);
        drawThinBorder(guiGraphics, rect, borderColor);
    }

    private static void drawButton(GuiGraphics guiGraphics, Font font, Rect rect, String label, boolean hovered) {
        guiGraphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), hovered ? BUTTON_BACKGROUND_HOVER : BUTTON_BACKGROUND);
        drawThinBorder(guiGraphics, rect, BUTTON_BORDER);
        guiGraphics.drawCenteredString(font, label, rect.x() + rect.width() / 2, rect.y() + 2, BUTTON_TEXT);
    }

    private static void drawSlotOutline(GuiGraphics guiGraphics, int x, int y, int color) {
        guiGraphics.hLine(x, x + 15, y, color);
        guiGraphics.hLine(x, x + 15, y + 15, color);
        guiGraphics.vLine(x, y, y + 15, color);
        guiGraphics.vLine(x + 15, y, y + 15, color);
    }

    private static void drawThinBorder(GuiGraphics guiGraphics, Rect rect, int color) {
        guiGraphics.hLine(rect.x(), rect.right() - 1, rect.y(), color);
        guiGraphics.hLine(rect.x(), rect.right() - 1, rect.bottom() - 1, color);
        guiGraphics.vLine(rect.x(), rect.y(), rect.bottom() - 1, color);
        guiGraphics.vLine(rect.right() - 1, rect.y(), rect.bottom() - 1, color);
    }

    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return end <= 0 ? ellipsis : text.substring(0, end) + ellipsis;
    }

    private static MutableComponent colored(MutableComponent component, int color) {
        return component.withStyle(style -> style.withColor(color));
    }

    private static MutableComponent labelValue(String labelKey, Component value) {
        return Component.empty()
                .append(Component.translatable(labelKey))
                .append(Component.literal(": "))
                .append(value);
    }

    private static int getToastAlpha(OverlayMessage activeOverlay, long nowMillis) {
        long remainingMillis = Math.max(0L, activeOverlay.expiresAtMillis() - nowMillis);
        if (remainingMillis >= 400L) {
            return 255;
        }
        return clamp((int) ((remainingMillis * 255L) / 400L), 96, 255);
    }

    private static int normalizeColor(int color) {
        return (color >>> 24) == 0 ? 0xFF000000 | color : color;
    }

    private static int withAlpha(int color, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @SuppressWarnings("unchecked")
    private static ShopMenu getShopMenu(ShopScreen screen) {
        return ((MenuAccess<ShopMenu>) screen).getMenu();
    }

    @SuppressWarnings("unchecked")
    private static BankMenu getBankMenu(BankScreen screen) {
        return ((MenuAccess<BankMenu>) screen).getMenu();
    }

    private record OfferKey(int categoryIndex, int offerIndex) {
    }

    private record SessionState(UUID sessionUuid, Map<OfferKey, ShopUiStatePayload.OfferState> offersByKey) {
    }

    private record ClientBankState(Map<BankOfferKey, Offer> exactOffersByKey, Map<Item, Offer> genericOffersByItem, List<Offer> offers) {
        private static ClientBankState fromPayload(BankUiStatePayload payload) {
            HashMap<BankOfferKey, Offer> exactOffers = new HashMap<>();
            for (BankUiStatePayload.Entry entry : payload.exactOffers()) {
                exactOffers.put(new BankOfferKey(entry.stack().getItem(), entry.stack().getComponents()), new Offer(entry.stack().copy(), entry.price(), -1));
            }

            HashMap<Item, Offer> genericOffers = new HashMap<>();
            for (BankUiStatePayload.Entry entry : payload.genericOffers()) {
                genericOffers.put(entry.stack().getItem(), new Offer(entry.stack().copy(), entry.price(), -1));
            }

            LinkedHashMap<BankOfferKey, Offer> visibleOffers = new LinkedHashMap<>(exactOffers.size() + genericOffers.size());
            for (Offer offer : exactOffers.values()) {
                visibleOffers.put(new BankOfferKey(offer.getItem().getItem(), offer.getItem().getComponents()), offer);
            }
            for (Offer offer : genericOffers.values()) {
                visibleOffers.putIfAbsent(new BankOfferKey(offer.getItem().getItem(), offer.getItem().getComponents()), offer);
            }
            return new ClientBankState(Map.copyOf(exactOffers), Map.copyOf(genericOffers), List.copyOf(visibleOffers.values()));
        }

        private Offer get(ItemStack stack) {
            Offer offer = exactOffersByKey.get(new BankOfferKey(stack.getItem(), stack.getComponents()));
            if (offer != null) {
                return offer;
            }
            return genericOffersByItem.get(stack.getItem());
        }
    }

    private record BankOfferKey(Item item, DataComponentMap components) {
    }

    private record OverlayMessage(Component message, int color, long expiresAtMillis) {
    }

    private record OfferContext(Offer offer, ShopUiStatePayload.OfferState offerState) {
    }

    private record AcceptedItemEntry(Offer offer, String name) {
    }

    private record Rect(int x, int y, int width, int height) {
        private int right() {
            return x + width;
        }

        private int bottom() {
            return y + height;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }

    private record AcceptedItemsModalLayout(
            Rect panelRect,
            Rect headerRect,
            Rect searchRect,
            Rect contentRect,
            Rect footerRect,
            Rect closeRect,
            Rect previousRect,
            Rect nextRect
    ) {
    }
}
