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
import fr.cobbledollars.commandshops.audit.AuditFiles;
import fr.cobbledollars.commandshops.admin.network.AdminNetwork;
import fr.cobbledollars.commandshops.admin.config.AdminConfigService;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;


public final class CommandShopCommands {
    private static final DynamicCommandExceptionType SHOP_ERROR = new DynamicCommandExceptionType(
            message -> message instanceof Component component ? component : Component.literal(String.valueOf(message))
    );
    private static final SimpleCommandExceptionType TARGET_REQUIRED = new SimpleCommandExceptionType(
            Component.translatable("cobbledollarscommandshops.command.target_required")
    );
    private static final SimpleCommandExceptionType PLAYER_OPEN_DISABLED = new SimpleCommandExceptionType(
            Component.translatableWithFallback("cobbledollarscommandshops.command.player_open_disabled",
                    "Opening shops directly is disabled for non-administrators on this server.")
    );

    private CommandShopCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cdshops")
                .then(Commands.literal("admin")
                        .requires(CommandShopCommands::canUseCommand)
                        .executes(context -> openAdmin(context.getSource())))
                .then(Commands.literal("open")
                        .requires(CommandShopCommands::canOpenShop)
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
                        .executes(context -> showDirectory(context.getSource()))));
    }

    private static boolean canUseCommand(CommandSourceStack source) {
        return source.hasPermission(2);
    }

    private static boolean canOpenShop(CommandSourceStack source) {
        return canUseCommand(source) || CommandSettings.allowPlayerOpen();
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
        // Recheck at execution time: a previously parsed command may outlive a settings change.
        if (!canOpenShop(source)) throw PLAYER_OPEN_DISABLED.create();
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
            ServerPlayer actor = source.getPlayer();
            ShopRegistry.ReloadSummary summary = AdminConfigService.reload(source.getServer(), source.getTextName(),
                    actor == null ? null : actor.getUUID().toString());
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

    private static CompletableFuture<Suggestions> suggestShopIds(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(ShopRegistry.listShopIds(), builder);
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
