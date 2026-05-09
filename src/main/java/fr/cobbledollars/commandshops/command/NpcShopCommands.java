package fr.cobbledollars.commandshops.command;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopFiles;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
import net.minecraft.world.entity.Entity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class NpcShopCommands {
    private static final DynamicCommandExceptionType SHOP_ERROR = new DynamicCommandExceptionType(message -> Component.literal(String.valueOf(message)));
    private static final SimpleCommandExceptionType TARGET_REQUIRED = new SimpleCommandExceptionType(Component.literal("This source must specify a target player: /npcshop open <shop> <player>."));

    private NpcShopCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("npcshop")
                .requires(NpcShopCommands::canUseCommand)
                .then(Commands.literal("open")
                        .then(Commands.argument("shop", StringArgumentType.word())
                                .suggests(NpcShopCommands::suggestShopIds)
                                .executes(context -> openForImplicitTarget(context.getSource(), StringArgumentType.getString(context, "shop")))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(context -> openForTargets(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "shop"),
                                                EntityArgument.getPlayers(context, "targets"))))))
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
        Shop shop = loadShop(shopId);
        for (ServerPlayer target : targets) {
            try {
                CommandShopSessions.openShop(target, shop);
            } catch (IllegalStateException exception) {
                throw SHOP_ERROR.create(exception.getMessage());
            }
        }

        source.sendSuccess(() -> Component.literal("Opened shop '" + shopId + "' for " + targets.size() + " player(s)."), false);
        return targets.size();
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

    private static ServerPlayer resolveImplicitTarget(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return player;
        }
        throw TARGET_REQUIRED.create();
    }

    private static Shop loadShop(String shopId) throws CommandSyntaxException {
        try {
            return ShopFiles.loadShop(shopId);
        } catch (IOException | IllegalArgumentException exception) {
            throw SHOP_ERROR.create(exception.getMessage());
        }
    }
}
