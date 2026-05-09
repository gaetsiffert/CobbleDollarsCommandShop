package fr.cobbledollars.commandshops.command;

import java.io.IOException;
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
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.PlayerShopStockData;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopFiles;
import fr.cobbledollars.commandshops.shop.ShopOfferDefinition;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class CommandShopCommands {
    private static final DynamicCommandExceptionType SHOP_ERROR = new DynamicCommandExceptionType(message -> Component.literal(String.valueOf(message)));
    private static final SimpleCommandExceptionType TARGET_REQUIRED = new SimpleCommandExceptionType(Component.literal("This source must specify a target player: /cdshops open <shop> <player>."));

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
        for (ServerPlayer target : targets) {
            try {
                CommandShopSessions.openShop(target, shop);
            } catch (IllegalStateException exception) {
                throw SHOP_ERROR.create(exception.getMessage());
            }
        }

        source.sendSuccess(() -> Component.literal("Opened shop '" + shop.id() + "' for " + targets.size() + " player(s)."), false);
        return targets.size();
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

        source.sendSuccess(() -> Component.literal("Restocked all finite offers from shop '" + shop.id() + "' for " + targets.size() + " player(s)."), true);
        return targets.size();
    }

    private static int restockOffer(CommandSourceStack source, String shopId, String offerId, Collection<ServerPlayer> targets) throws CommandSyntaxException {
        ShopDefinition shop = loadShop(shopId);
        ShopOfferDefinition offer = shop.getOfferById(offerId);
        if (offer == null) {
            throw SHOP_ERROR.create("Offer '" + offerId + "' was not found in shop '" + shop.id() + "'.");
        }

        if (!offer.hasFiniteStock()) {
            throw SHOP_ERROR.create("Offer '" + offer.id() + "' in shop '" + shop.id() + "' has unlimited stock.");
        }

        long nowMillis = System.currentTimeMillis();
        for (ServerPlayer target : targets) {
            if (target.getServer() == null) {
                continue;
            }
            PlayerShopStockData.get(target.getServer()).restockOffer(target.getUUID(), shop, offer, nowMillis);
            CommandShopSessions.refreshPlayerSession(target);
        }

        source.sendSuccess(() -> Component.literal("Restocked offer '" + offer.id() + "' from shop '" + shop.id() + "' for " + targets.size() + " player(s)."), true);
        return targets.size();
    }

    private static int showStock(CommandSourceStack source, String shopId, ServerPlayer target) throws CommandSyntaxException {
        ShopDefinition shop = loadShop(shopId);
        if (target.getServer() == null) {
            throw SHOP_ERROR.create("Target player is not attached to a server.");
        }
        PlayerShopStockData stockData = PlayerShopStockData.get(target.getServer());
        long nowMillis = System.currentTimeMillis();

        StringBuilder builder = new StringBuilder();
        builder.append("Stocks for ").append(target.getGameProfile().getName()).append(" in shop '").append(shop.id()).append("': ");

        boolean appended = false;
        for (ShopOfferDefinition offer : shop.offers()) {
            if (!offer.hasFiniteStock()) {
                continue;
            }

            if (appended) {
                builder.append(", ");
            }
            builder.append(offer.id()).append("=").append(stockData.resolveStock(target.getUUID(), shop, offer, nowMillis));
            appended = true;
        }

        if (!appended) {
            builder.append("no finite offers");
        }

        source.sendSuccess(() -> Component.literal(builder.toString()), false);
        return 1;
    }

    private static int listShops(CommandSourceStack source) {
        try {
            ShopFiles.ensureExampleShopExists();
            List<String> shopIds = ShopFiles.listShopIds();
            if (shopIds.isEmpty()) {
                source.sendFailure(Component.literal("No shop files found in " + ShopFiles.getShopDirectory()));
                return 0;
            }

            source.sendSuccess(() -> Component.literal("Available shops: " + String.join(", ", shopIds)), false);
            return shopIds.size();
        } catch (IOException exception) {
            source.sendFailure(Component.literal("Failed to read shop directory: " + exception.getMessage()));
            return 0;
        }
    }

    private static int showDirectory(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Shop directory: " + ShopFiles.getShopDirectory()), false);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestShopIds(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        try {
            return SharedSuggestionProvider.suggest(ShopFiles.listShopIds(), builder);
        } catch (IOException exception) {
            return Suggestions.empty();
        }
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
        try {
            return ShopFiles.loadShop(shopId);
        } catch (IOException | IllegalArgumentException exception) {
            throw SHOP_ERROR.create(exception.getMessage());
        }
    }
}
