package fr.cobbledollars.commandshops.gametest;

import java.util.Objects;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.testframework.conf.FrameworkConfiguration;
import net.neoforged.testframework.impl.MutableTestFramework;

public final class CommandShopGameTestBootstrap {
    private static final MutableTestFramework FRAMEWORK = createFramework();

    private static boolean initialized;

    private CommandShopGameTestBootstrap() {
    }

    private static MutableTestFramework createFramework() {
        FrameworkConfiguration.Builder builder = FrameworkConfiguration.builder(
                ResourceLocation.fromNamespaceAndPath(CobbleDollarsCommandShopsMod.MODID, "gametests")
        );
        if (Boolean.getBoolean("commandshops.perfGametests")) {
            builder.enableTests(
                    "perf.open_heavy_shop_benchmark",
                    "perf.refresh_heavy_shop_benchmark",
                    "perf.sell_heavy_bank_benchmark"
            );
        }
        return builder.build().create();
    }

    public static synchronized void init(ModContainer modContainer) {
        if (initialized) {
            return;
        }
        initialized = true;

        IEventBus modBus = Objects.requireNonNull(modContainer.getEventBus(), "Mod event bus is required for GameTest bootstrap.");
        FRAMEWORK.init(modBus, modContainer);
        NeoForge.EVENT_BUS.addListener(CommandShopGameTestBootstrap::registerCommands);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cdshoptests");
        FRAMEWORK.registerCommands(root);
        event.getDispatcher().getRoot().addChild(root.build());
    }
}
