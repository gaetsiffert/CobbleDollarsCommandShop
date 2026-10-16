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
import fr.cobbledollars.commandshops.admin.network.AdminNetwork;
import fr.cobbledollars.commandshops.audit.AuditLogService;
import fr.cobbledollars.commandshops.audit.AuditStatsService;
import fr.cobbledollars.commandshops.audit.AuditStatsSnapshot;
import fr.cobbledollars.commandshops.audit.AuditTimeWindow;
import fr.cobbledollars.commandshops.audit.AuditTopTarget;
import fr.cobbledollars.commandshops.feedback.FeedbackFiles;
import fr.cobbledollars.commandshops.feedback.ShopFeedbackService;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.cobbledollars.commandshops.shop.ShopVisibilityData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
                .then(Commands.literal("admin")
                        .requires(CommandShopCommands::canUseCommand)
                        .executes(context -> openAdmin(context.getSource())))
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

    private static int openAdmin(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!AdminNetwork.open(player)) {
            source.sendFailure(Component.translatableWithFallback("cobbledollarscommandshops.command.admin_client_required",
                    "The administrator interface requires an up-to-date CobbleDollars Command Shops installation on your client."));
            return 0;
        }
        return 1;
    }

    private static int openForImplicitTarget(CommandSourceStack source, String shopId) throws CommandSyntaxException {
        ServerPlayer player = resolveImplicitTarget(source);
        return openForTargets(source, shopId, List.of(player));
    }

    private static int openForTargets(CommandSourceStack source, String shopId, Collection<ServerPlayer> targets) throws CommandSyntaxException {
        ShopDefinition shop = loadShop(shopId);
        ShopVisibilityData.VisibilityStatus visibilityStatus = ShopVisibilityData.get(source.getServer()).status(shop.id());
        if (!visibilityStatus.enabled()) {
            Component denialMessage = visibilityStatus.denialMessage(shop.id());
            for (ServerPlayer target : targets) {
                ShopFeedbackService.onShopDenied(target, denialMessage);
            }
            return 0;
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
