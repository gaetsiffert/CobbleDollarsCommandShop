package fr.cobbledollars.commandshops.command;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fr.cobbledollars.commandshops.feedback.FeedbackFiles;
import fr.cobbledollars.commandshops.feedback.ShopFeedbackService;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.PlayerShopStockData;
import fr.cobbledollars.commandshops.shop.ResolvedShopOffer;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopOfferDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.cobbledollars.commandshops.shop.ShopVisibilityData;
import fr.cobbledollars.commandshops.shop.TransactionAuditLogger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class CommandShopCommands {
    private static final DynamicCommandExceptionType SHOP_ERROR = new DynamicCommandExceptionType(
            message -> message instanceof Component component ? component : Component.literal(String.valueOf(message))
    );
    private static final SimpleCommandExceptionType TARGET_REQUIRED = new SimpleCommandExceptionType(
            Component.translatable("cobbledollarscommandshops.command.target_required")
    );

    private CommandShopCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cdshops")
                .requires(CommandShopCommands::canUseCommand)
                .then(Commands.literal("open")
                        .then(Commands.argument("shop", StringArgumentType.word())
                                .suggests(CommandShopCommands::suggestShopIds)
                                .executes(context -> openForImplicitTarget(context.getSource(), StringArgumentType.getString(context, "shop")))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(context -> openForTargets(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "shop"),
                                                EntityArgument.getPlayers(context, "targets"))))))
                .then(Commands.literal("reload")
                        .executes(context -> reload(context.getSource())))
                .then(Commands.literal("restock")
                        .then(Commands.argument("shop", StringArgumentType.word())
                                .suggests(CommandShopCommands::suggestShopIds)
                                .then(Commands.literal("all")
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(context -> restockShop(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "shop"),
                                                        EntityArgument.getPlayers(context, "targets")))))
                                .then(Commands.literal("offer")
                                        .then(Commands.argument("offer", StringArgumentType.word())
                                                .suggests(CommandShopCommands::suggestOfferIds)
                                                .then(Commands.argument("targets", EntityArgument.players())
                                                        .executes(context -> restockOffer(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "shop"),
                                                                StringArgumentType.getString(context, "offer"),
                                                                EntityArgument.getPlayers(context, "targets"))))))))
                .then(Commands.literal("stock")
                        .then(Commands.argument("shop", StringArgumentType.word())
                                .suggests(CommandShopCommands::suggestShopIds)
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> showStock(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "shop"),
                                                EntityArgument.getPlayer(context, "target"))))))
                .then(Commands.literal("visibility")
                        .then(Commands.literal("list")
                                .executes(context -> listVisibility(context.getSource())))
                        .then(Commands.argument("shop", StringArgumentType.word())
                                .suggests(CommandShopCommands::suggestShopIds)
                                .then(Commands.literal("enable")
                                        .executes(context -> enableVisibility(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "shop"))))
                                .then(Commands.literal("disable")
                                        .executes(context -> disableVisibility(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "shop"),
                                                null))
                                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                                .executes(context -> disableVisibility(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "shop"),
                                                        StringArgumentType.getString(context, "message")))))
                                .then(Commands.literal("status")
                                        .executes(context -> showVisibilityStatus(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "shop"))))))
                .then(Commands.literal("list")
                        .executes(context -> listShops(context.getSource())))
                .then(Commands.literal("where")
                        .executes(context -> showDirectory(context.getSource()))));
    }

    private static boolean canUseCommand(CommandSourceStack source) {
        return source.hasPermission(2) || isCustomNpcSource(source);
    }

    private static boolean isCustomNpcSource(CommandSourceStack source) {
        Entity entity = source.getEntity();
        return entity != null && entity.getClass().getName().startsWith("noppes.npcs.entity.");
    }

    private static int openForImplicitTarget(CommandSourceStack source, String shopId) throws CommandSyntaxException {
        ServerPlayer player = resolveImplicitTarget(source);
        return openForTargets(source, shopId, List.of(player));
    }

    private static int openForTargets(CommandSourceStack source, String shopId, Collection<ServerPlayer> targets) throws CommandSyntaxException {
        ShopDefinition shop = loadShop(shopId);
        ShopVisibilityData.VisibilityStatus visibilityStatus = ShopVisibilityData.get(source.getServer()).status(shop.id());
        if (!visibilityStatus.enabled()) {
            throw SHOP_ERROR.create(visibilityStatus.denialMessage(shop.id()));
        }
        for (ServerPlayer target : targets) {
            CommandShopSessions.queueOpenShop(target, shop);
        }

        source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.open.success", shop.id(), targets.size()), false);
        return targets.size();
    }

    private static int reload(CommandSourceStack source) throws CommandSyntaxException {
        try {
            ShopRegistry.ReloadSummary summary = ShopRegistry.reload(source.getServer().registryAccess());
            ShopFeedbackService.reload();
            CommandShopSessions.refreshAllSessions(source.getServer());
            source.sendSuccess(() -> Component.translatable(
                            "cobbledollarscommandshops.command.reload.success",
                            summary.shopCount(),
                            summary.localBankCount(),
                            String.valueOf(summary.globalBankFile()),
                            String.valueOf(FeedbackFiles.getConfigFile())),
                    true);
            return 1;
        } catch (Exception exception) {
            throw SHOP_ERROR.create(Component.translatable("cobbledollarscommandshops.command.reload.failure", exception.getMessage()));
        }
    }

    private static int restockShop(CommandSourceStack source, String shopId, Collection<ServerPlayer> targets) throws CommandSyntaxException {
        ShopDefinition shop = loadShop(shopId);
        long nowMillis = System.currentTimeMillis();

        for (ServerPlayer target : targets) {
            if (target.getServer() == null) {
                continue;
            }
            PlayerShopStockData.get(target.getServer()).restockShop(target.getUUID(), shop, nowMillis);
            CommandShopSessions.refreshPlayerSession(target);
        }

        source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.restock_shop.success", shop.id(), targets.size()), true);
        return targets.size();
    }

    private static int restockOffer(CommandSourceStack source, String shopId, String offerId, Collection<ServerPlayer> targets) throws CommandSyntaxException {
        ShopDefinition shop = loadShop(shopId);
        ShopOfferDefinition offer = shop.getOfferById(offerId);
        if (offer == null) {
            throw SHOP_ERROR.create(Component.translatable("cobbledollarscommandshops.command.offer_not_found", offerId, shop.id()));
        }

        if (!offer.hasFiniteStock()) {
            throw SHOP_ERROR.create(Component.translatable("cobbledollarscommandshops.command.offer_unlimited_stock", offer.id(), shop.id()));
        }

        long nowMillis = System.currentTimeMillis();
        for (ServerPlayer target : targets) {
            if (target.getServer() == null) {
                continue;
            }
            PlayerShopStockData.get(target.getServer()).restockOffer(target.getUUID(), shop, offer, nowMillis);
            CommandShopSessions.refreshPlayerSession(target);
        }

        source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.restock_offer.success", offer.id(), shop.id(), targets.size()), true);
        return targets.size();
    }

    private static int showStock(CommandSourceStack source, String shopId, ServerPlayer target) throws CommandSyntaxException {
        ShopDefinition shop = loadShop(shopId);
        if (target.getServer() == null) {
            throw SHOP_ERROR.create(Component.translatable("cobbledollarscommandshops.command.target_not_attached"));
        }
        PlayerShopStockData stockData = PlayerShopStockData.get(target.getServer());
        long nowMillis = System.currentTimeMillis();

        MutableComponent message = Component.empty().append(
                Component.translatable("cobbledollarscommandshops.command.stock.prefix", target.getGameProfile().getName(), shop.id())
        );
        StringBuilder details = new StringBuilder();

        boolean appended = false;
        for (ShopOfferDefinition offer : shop.offers()) {
            if (!offer.hasFiniteStock()) {
                continue;
            }

            List<ResolvedShopOffer> resolvedOffers = offer.createResolvedOffers();
            if (resolvedOffers.size() == 1) {
                if (appended) {
                    details.append(", ");
                }
                details.append(offer.id()).append("=").append(stockData.resolveStock(target.getUUID(), shop, resolvedOffers.get(0), nowMillis));
                appended = true;
                continue;
            }

            for (ResolvedShopOffer resolvedOffer : resolvedOffers) {
                if (appended) {
                    details.append(", ");
                }
                details.append(offer.id())
                        .append("[")
                        .append(BuiltInRegistries.ITEM.getKey(resolvedOffer.itemStack().getItem()))
                        .append("]=")
                        .append(stockData.resolveStock(target.getUUID(), shop, resolvedOffer, nowMillis));
                appended = true;
            }
        }

        if (!appended) {
            message.append(Component.translatable("cobbledollarscommandshops.command.stock.none"));
        } else {
            message.append(Component.literal(details.toString()));
        }

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int enableVisibility(CommandSourceStack source, String shopId) throws CommandSyntaxException {
        ShopDefinition shop = loadShop(shopId);
        ShopVisibilityData.get(source.getServer()).enable(shop.id());
        TransactionAuditLogger.logVisibilityChanged(shop.id(), true, null, source.getTextName(), source.getEntity() == null ? null : source.getEntity().getStringUUID());
        source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.visibility.enable.success", shop.id()), true);
        return 1;
    }

    private static int disableVisibility(CommandSourceStack source, String shopId, String message) throws CommandSyntaxException {
        ShopDefinition shop = loadShop(shopId);
        ShopVisibilityData.VisibilityStatus status = ShopVisibilityData.get(source.getServer()).disable(
                shop.id(),
                message,
                source.getTextName(),
                System.currentTimeMillis()
        );
        CommandShopSessions.closeShopSessions(source.getServer(), shop.id(), status.denialMessage(shop.id()));
        TransactionAuditLogger.logVisibilityChanged(shop.id(), false, status.message(), source.getTextName(), source.getEntity() == null ? null : source.getEntity().getStringUUID());
        if (status.message() == null) {
            source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.visibility.disable.success", shop.id()), true);
        } else {
            source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.visibility.disable.success_with_message", shop.id(), status.message()), true);
        }
        return 1;
    }

    private static int showVisibilityStatus(CommandSourceStack source, String shopId) throws CommandSyntaxException {
        ShopDefinition shop = loadShop(shopId);
        ShopVisibilityData.VisibilityStatus status = ShopVisibilityData.get(source.getServer()).status(shop.id());
        if (status.enabled()) {
            source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.visibility.status.enabled", shop.id()), false);
        } else if (status.message() == null) {
            source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.visibility.status.disabled", shop.id()), false);
        } else {
            source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.visibility.status.disabled_with_message", shop.id(), status.message()), false);
        }
        return 1;
    }

    private static int listVisibility(CommandSourceStack source) {
        List<String> shopIds = ShopRegistry.listShopIds();
        if (shopIds.isEmpty()) {
            source.sendFailure(Component.translatable("cobbledollarscommandshops.command.list.none", String.valueOf(ShopRegistry.getShopDirectory())));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.visibility.list.header", shopIds.size()), false);
        ShopVisibilityData visibilityData = ShopVisibilityData.get(source.getServer());
        for (String shopId : shopIds) {
            ShopVisibilityData.VisibilityStatus status = visibilityData.status(shopId);
            if (status.enabled()) {
                source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.visibility.list.entry.enabled", shopId), false);
            } else if (status.message() == null) {
                source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.visibility.list.entry.disabled", shopId), false);
            } else {
                source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.visibility.list.entry.disabled_with_message", shopId, status.message()), false);
            }
        }
        return shopIds.size();
    }

    private static int listShops(CommandSourceStack source) {
        List<String> shopIds = ShopRegistry.listShopIds();
        if (shopIds.isEmpty()) {
            source.sendFailure(Component.translatable("cobbledollarscommandshops.command.list.none", String.valueOf(ShopRegistry.getShopDirectory())));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.list.available", String.join(", ", shopIds)), false);
        return shopIds.size();
    }

    private static int showDirectory(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.where",
                String.valueOf(ShopRegistry.getShopDirectory()),
                String.valueOf(ShopRegistry.getGlobalBankFile())
        ), false);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestShopIds(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(ShopRegistry.listShopIds(), builder);
    }

    private static CompletableFuture<Suggestions> suggestOfferIds(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        try {
            ShopDefinition shop = loadShop(StringArgumentType.getString(context, "shop"));
            return SharedSuggestionProvider.suggest(shop.offerIds(), builder);
        } catch (IllegalArgumentException | CommandSyntaxException exception) {
            return Suggestions.empty();
        }
    }

    private static ServerPlayer resolveImplicitTarget(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return player;
        }
        throw TARGET_REQUIRED.create();
    }

    private static ShopDefinition loadShop(String shopId) throws CommandSyntaxException {
        ShopDefinition shop = ShopRegistry.getShop(shopId);
        if (shop == null) {
            throw SHOP_ERROR.create(Component.translatable(
                    "cobbledollarscommandshops.command.shop_not_found",
                    shopId,
                    String.valueOf(ShopRegistry.getShopDirectory())
            ));
        }
        return shop;
    }
}
