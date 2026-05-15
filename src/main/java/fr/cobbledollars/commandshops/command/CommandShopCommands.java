package fr.cobbledollars.commandshops.command;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fr.cobbledollars.commandshops.audit.AuditFiles;
import fr.cobbledollars.commandshops.audit.AuditLogService;
import fr.cobbledollars.commandshops.audit.AuditStatsService;
import fr.cobbledollars.commandshops.audit.AuditStatsSnapshot;
import fr.cobbledollars.commandshops.audit.AuditTimeWindow;
import fr.cobbledollars.commandshops.audit.AuditTopTarget;
import fr.cobbledollars.commandshops.feedback.FeedbackFiles;
import fr.cobbledollars.commandshops.feedback.ShopFeedbackService;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.PlayerShopStockData;
import fr.cobbledollars.commandshops.shop.ResolvedShopOffer;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopOfferDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.cobbledollars.commandshops.shop.ShopVisibilityData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.math.BigInteger;

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
                .then(Commands.literal("open")
                        .then(Commands.argument("shop", StringArgumentType.word())
                                .suggests(CommandShopCommands::suggestShopIds)
                                .executes(context -> openForImplicitTarget(context.getSource(), StringArgumentType.getString(context, "shop")))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .requires(CommandShopCommands::canUseCommand)
                                        .executes(context -> openForTargets(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "shop"),
                                                EntityArgument.getPlayers(context, "targets"))))))
                .then(Commands.literal("reload")
                        .requires(CommandShopCommands::canUseCommand)
                        .executes(context -> reload(context.getSource())))
                .then(Commands.literal("restock")
                        .requires(CommandShopCommands::canUseCommand)
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
                        .requires(CommandShopCommands::canUseCommand)
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
                                .requires(CommandShopCommands::canUseCommand)
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
                        .requires(CommandShopCommands::canUseCommand)
                        .executes(context -> showDirectory(context.getSource())))
                .then(createStatsCommand().requires(CommandShopCommands::canUseCommand)));
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> createStatsCommand() {
        return Commands.literal("stats")
                .then(Commands.literal("summary")
                        .executes(context -> showStatsSummary(context.getSource(), AuditTimeWindow.ALL))
                        .then(Commands.argument("window", StringArgumentType.word())
                                .suggests(CommandShopCommands::suggestAuditWindows)
                                .executes(context -> showStatsSummary(
                                        context.getSource(),
                                        parseAuditWindow(StringArgumentType.getString(context, "window"))))))
                .then(Commands.literal("top")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(CommandShopCommands::suggestAuditTopTargets)
                                .executes(context -> showStatsTop(
                                        context.getSource(),
                                        parseAuditTopTarget(StringArgumentType.getString(context, "target")),
                                        AuditTimeWindow.ALL,
                                        5))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 20))
                                        .executes(context -> showStatsTop(
                                                context.getSource(),
                                                parseAuditTopTarget(StringArgumentType.getString(context, "target")),
                                                AuditTimeWindow.ALL,
                                                IntegerArgumentType.getInteger(context, "limit"))))
                                .then(Commands.argument("window", StringArgumentType.word())
                                        .suggests(CommandShopCommands::suggestAuditWindows)
                                        .executes(context -> showStatsTop(
                                                context.getSource(),
                                                parseAuditTopTarget(StringArgumentType.getString(context, "target")),
                                                parseAuditWindow(StringArgumentType.getString(context, "window")),
                                                5))
                                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 20))
                                                .executes(context -> showStatsTop(
                                                        context.getSource(),
                                                        parseAuditTopTarget(StringArgumentType.getString(context, "target")),
                                                        parseAuditWindow(StringArgumentType.getString(context, "window")),
                                                        IntegerArgumentType.getInteger(context, "limit")))))))
                .then(Commands.literal("shop")
                        .then(Commands.argument("shop", StringArgumentType.word())
                                .suggests(CommandShopCommands::suggestShopIds)
                                .executes(context -> showStatsShop(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "shop"),
                                        AuditTimeWindow.ALL))
                                .then(Commands.argument("window", StringArgumentType.word())
                                        .suggests(CommandShopCommands::suggestAuditWindows)
                                        .executes(context -> showStatsShop(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "shop"),
                                                parseAuditWindow(StringArgumentType.getString(context, "window")))))))
                .then(Commands.literal("player")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(CommandShopCommands::suggestAuditPlayers)
                                .executes(context -> showStatsPlayer(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "player"),
                                        AuditTimeWindow.ALL))
                                .then(Commands.argument("window", StringArgumentType.word())
                                        .suggests(CommandShopCommands::suggestAuditWindows)
                                        .executes(context -> showStatsPlayer(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "player"),
                                                parseAuditWindow(StringArgumentType.getString(context, "window")))))))
                .then(Commands.literal("item")
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .suggests(CommandShopCommands::suggestAuditItems)
                                .executes(context -> showStatsItem(
                                        context.getSource(),
                                        ResourceLocationArgument.getId(context, "item").toString(),
                                        AuditTimeWindow.ALL))
                                .then(Commands.argument("window", StringArgumentType.word())
                                        .suggests(CommandShopCommands::suggestAuditWindows)
                                        .executes(context -> showStatsItem(
                                                context.getSource(),
                                                ResourceLocationArgument.getId(context, "item").toString(),
                                                parseAuditWindow(StringArgumentType.getString(context, "window"))))))); 
    }

    private static boolean canUseCommand(CommandSourceStack source) {
        return source.hasPermission(2);
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
            AuditLogService.reload();
            CommandShopSessions.refreshAllSessions(source.getServer());
            source.sendSuccess(() -> Component.translatable(
                            "cobbledollarscommandshops.command.reload.success",
                            summary.shopCount(),
                            summary.localBankCount(),
                            String.valueOf(summary.globalBankFile()),
                            String.valueOf(FeedbackFiles.getConfigFile()),
                            String.valueOf(AuditFiles.getConfigFile())),
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
        AuditLogService.logVisibilityChanged(shop.id(), true, null, source.getTextName(), source.getEntity() == null ? null : source.getEntity().getStringUUID());
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
        AuditLogService.logVisibilityChanged(shop.id(), false, status.message(), source.getTextName(), source.getEntity() == null ? null : source.getEntity().getStringUUID());
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

    private static int showStatsSummary(CommandSourceStack source, AuditTimeWindow window) throws CommandSyntaxException {
        AuditStatsSnapshot snapshot = loadAuditStats(window);
        if (!snapshot.hasData()) {
            source.sendFailure(Component.translatable("cobbledollarscommandshops.command.stats.no_data", windowLabel(window)));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.stats.summary.header", windowLabel(window)), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.summary.buys",
                snapshot.buySuccessCount(),
                formatMoney(snapshot.totalSpent())
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.summary.sells",
                snapshot.sellSuccessCount(),
                formatMoney(snapshot.totalEarned())
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.summary.failures",
                snapshot.buyFailureCount(),
                snapshot.sellFailureCount()
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.summary.visibility",
                snapshot.visibilityChangeCount()
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.summary.net",
                formatMoney(snapshot.netFlow())
        ), false);
        return 1;
    }

    private static int showStatsTop(CommandSourceStack source, AuditTopTarget target, AuditTimeWindow window, int limit) throws CommandSyntaxException {
        AuditStatsSnapshot snapshot = loadAuditStats(window);
        if (!snapshot.hasData()) {
            source.sendFailure(Component.translatable("cobbledollarscommandshops.command.stats.no_data", windowLabel(window)));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.top.header",
                Component.translatable(target.translationKey()),
                windowLabel(window),
                limit
        ), false);

        return switch (target) {
            case SHOPS -> renderTopShops(source, snapshot.topShops(limit));
            case OFFERS -> renderTopOffers(source, snapshot.topOffers(limit));
            case PLAYERS -> renderTopPlayers(source, snapshot.topPlayers(limit));
            case ITEMS -> renderTopItems(source, snapshot.topItems(limit));
        };
    }

    private static int showStatsShop(CommandSourceStack source, String shopId, AuditTimeWindow window) throws CommandSyntaxException {
        AuditStatsSnapshot snapshot = loadAuditStats(window);
        AuditStatsSnapshot.ShopStats stats = snapshot.shop(shopId);
        if (stats == null) {
            source.sendFailure(Component.translatable("cobbledollarscommandshops.command.stats.shop.none", shopId, windowLabel(window)));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.stats.shop.header", stats.shopId(), windowLabel(window)), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.line.buys",
                stats.buySuccessCount(),
                formatMoney(stats.spent())
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.line.sells",
                stats.sellSuccessCount(),
                formatMoney(stats.earned())
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.line.failures",
                stats.buyFailureCount(),
                stats.sellFailureCount()
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.shop.visibility",
                stats.visibilityChangeCount()
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.line.items",
                stats.boughtItemCount(),
                stats.soldItemCount()
        ), false);
        return 1;
    }

    private static int showStatsPlayer(CommandSourceStack source, String playerQuery, AuditTimeWindow window) throws CommandSyntaxException {
        AuditStatsSnapshot snapshot = loadAuditStats(window);
        AuditStatsSnapshot.PlayerStats stats = snapshot.findPlayer(playerQuery);
        if (stats == null) {
            source.sendFailure(Component.translatable("cobbledollarscommandshops.command.stats.player.none", playerQuery, windowLabel(window)));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.stats.player.header", stats.playerName(), stats.playerUuid(), windowLabel(window)), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.line.buys",
                stats.buySuccessCount(),
                formatMoney(stats.spent())
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.line.sells",
                stats.sellSuccessCount(),
                formatMoney(stats.earned())
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.line.failures",
                stats.buyFailureCount(),
                stats.sellFailureCount()
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.line.items",
                stats.boughtItemCount(),
                stats.soldItemCount()
        ), false);
        return 1;
    }

    private static int showStatsItem(CommandSourceStack source, String itemQuery, AuditTimeWindow window) throws CommandSyntaxException {
        AuditStatsSnapshot snapshot = loadAuditStats(window);
        AuditStatsSnapshot.ItemStats stats = snapshot.item(itemQuery);
        if (stats == null) {
            source.sendFailure(Component.translatable("cobbledollarscommandshops.command.stats.item.none", itemQuery, windowLabel(window)));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("cobbledollarscommandshops.command.stats.item.header", stats.itemId(), stats.itemName(), windowLabel(window)), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.item.bought",
                stats.boughtCount(),
                formatMoney(stats.spent())
        ), false);
        source.sendSuccess(() -> Component.translatable(
                "cobbledollarscommandshops.command.stats.item.sold",
                stats.soldCount(),
                formatMoney(stats.earned())
        ), false);
        return 1;
    }

    private static int renderTopShops(CommandSourceStack source, List<AuditStatsSnapshot.ShopStats> stats) {
        if (stats.isEmpty()) {
            source.sendFailure(Component.translatable("cobbledollarscommandshops.command.stats.top.none"));
            return 0;
        }
        for (int index = 0; index < stats.size(); index++) {
            AuditStatsSnapshot.ShopStats entry = stats.get(index);
            int rank = index + 1;
            source.sendSuccess(() -> Component.translatable(
                    "cobbledollarscommandshops.command.stats.top.entry.shop",
                    rank,
                    entry.shopId(),
                    formatMoney(entry.totalVolume()),
                    entry.buySuccessCount(),
                    entry.sellSuccessCount()
            ), false);
        }
        return stats.size();
    }

    private static int renderTopOffers(CommandSourceStack source, List<AuditStatsSnapshot.OfferStats> stats) {
        if (stats.isEmpty()) {
            source.sendFailure(Component.translatable("cobbledollarscommandshops.command.stats.top.none"));
            return 0;
        }
        for (int index = 0; index < stats.size(); index++) {
            AuditStatsSnapshot.OfferStats entry = stats.get(index);
            int rank = index + 1;
            source.sendSuccess(() -> Component.translatable(
                    "cobbledollarscommandshops.command.stats.top.entry.offer",
                    rank,
                    entry.shopId(),
                    entry.offerId(),
                    formatMoney(entry.spent()),
                    entry.buySuccessCount()
            ), false);
        }
        return stats.size();
    }

    private static int renderTopPlayers(CommandSourceStack source, List<AuditStatsSnapshot.PlayerStats> stats) {
        if (stats.isEmpty()) {
            source.sendFailure(Component.translatable("cobbledollarscommandshops.command.stats.top.none"));
            return 0;
        }
        for (int index = 0; index < stats.size(); index++) {
            AuditStatsSnapshot.PlayerStats entry = stats.get(index);
            int rank = index + 1;
            source.sendSuccess(() -> Component.translatable(
                    "cobbledollarscommandshops.command.stats.top.entry.player",
                    rank,
                    entry.playerName(),
                    formatMoney(entry.spent()),
                    formatMoney(entry.earned()),
                    entry.buySuccessCount(),
                    entry.sellSuccessCount()
            ), false);
        }
        return stats.size();
    }

    private static int renderTopItems(CommandSourceStack source, List<AuditStatsSnapshot.ItemStats> stats) {
        if (stats.isEmpty()) {
            source.sendFailure(Component.translatable("cobbledollarscommandshops.command.stats.top.none"));
            return 0;
        }
        for (int index = 0; index < stats.size(); index++) {
            AuditStatsSnapshot.ItemStats entry = stats.get(index);
            int rank = index + 1;
            source.sendSuccess(() -> Component.translatable(
                    "cobbledollarscommandshops.command.stats.top.entry.item",
                    rank,
                    entry.itemId(),
                    entry.boughtCount(),
                    entry.soldCount(),
                    formatMoney(entry.totalVolume())
            ), false);
        }
        return stats.size();
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

    private static CompletableFuture<Suggestions> suggestAuditWindows(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(AuditTimeWindow.suggestionValues(), builder);
    }

    private static CompletableFuture<Suggestions> suggestAuditTopTargets(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(AuditTopTarget.suggestionValues(), builder);
    }

    private static CompletableFuture<Suggestions> suggestAuditPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                context.getSource().getServer().getPlayerList().getPlayers().stream().map(player -> player.getGameProfile().getName()),
                builder
        );
    }

    private static CompletableFuture<Suggestions> suggestAuditItems(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                BuiltInRegistries.ITEM.keySet().stream().map(ResourceLocation::toString),
                builder
        );
    }

    private static ServerPlayer resolveImplicitTarget(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return player;
        }
        throw TARGET_REQUIRED.create();
    }

    private static AuditStatsSnapshot loadAuditStats(AuditTimeWindow window) throws CommandSyntaxException {
        try {
            return AuditStatsService.readSnapshot(window);
        } catch (IOException exception) {
            throw SHOP_ERROR.create(Component.translatable("cobbledollarscommandshops.command.stats.read_failure", exception.getMessage()));
        }
    }

    private static AuditTimeWindow parseAuditWindow(String value) throws CommandSyntaxException {
        AuditTimeWindow window = AuditTimeWindow.fromArgument(value);
        if (window == null) {
            throw SHOP_ERROR.create(Component.translatable("cobbledollarscommandshops.command.stats.window.invalid", value));
        }
        return window;
    }

    private static AuditTopTarget parseAuditTopTarget(String value) throws CommandSyntaxException {
        AuditTopTarget target = AuditTopTarget.fromArgument(value);
        if (target == null) {
            throw SHOP_ERROR.create(Component.translatable("cobbledollarscommandshops.command.stats.top.target.invalid", value));
        }
        return target;
    }

    private static Component windowLabel(AuditTimeWindow window) {
        return Component.translatable(window.translationKey());
    }

    private static String formatMoney(BigInteger amount) {
        return amount.toString();
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
