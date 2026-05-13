package fr.cobbledollars.commandshops;

import java.io.IOException;

import fr.cobbledollars.commandshops.command.CommandShopCommands;
import fr.cobbledollars.commandshops.feedback.ShopFeedbackService;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerPlayer;

@Mod(CobbleDollarsCommandShopsMod.MODID)
public class CobbleDollarsCommandShopsMod {
    public static final String MODID = "cobbledollarscommandshops";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CobbleDollarsCommandShopsMod(ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        try {
            ShopRegistry.initialize(event.getServer().registryAccess());
            ShopFeedbackService.initialize();
        } catch (IOException exception) {
            LOGGER.error("Failed to prepare NPC shop directory", exception);
        }
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        CommandShopCommands.register(event);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        CommandShopSessions.tick(event.getServer());
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
            CommandShopSessions.cleanupPlayer(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        CommandShopSessions.cleanupAll();
        ShopFeedbackService.clear();
        ShopRegistry.clear();
    }
}
