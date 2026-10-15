package fr.cobbledollars.commandshops.gametest;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.MultipleTestTracker;
import net.minecraft.gametest.framework.RetryOptions;
import net.minecraft.gametest.framework.StructureGridSpawner;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// Development-only runner: CustomNPCs expects an ordinary DedicatedServer.
@EventBusSubscriber(modid = CobbleDollarsCommandShopsMod.MODID)
public final class DedicatedCompatibilityTestRunner {
    private static MultipleTestTracker tracker;
    private static int deadline;

    private DedicatedCompatibilityTestRunner() {
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("commandshops.dedicatedCompatibilityTests")) {
            return;
        }
        var level = event.getServer().overworld();
        var tests = GameTestRegistry.getAllTestFunctions().stream()
                .map(test -> new GameTestInfo(test, Rotation.NONE, level, RetryOptions.noRetries()))
                .toList();
        if (tests.isEmpty()) {
            throw new IllegalStateException("No command shop compatibility tests were registered.");
        }
        var runner = GameTestRunner.Builder.fromInfo(tests, level)
                .newStructureSpawner(new StructureGridSpawner(new BlockPos(0, 80, 0), 8, false))
                .build();
        tracker = new MultipleTestTracker(runner.getTestInfos());
        deadline = event.getServer().getTickCount() + 1200;
        CobbleDollarsCommandShopsMod.LOGGER.info("Starting {} compatibility tests on an ordinary dedicated server.", tests.size());
        runner.start();
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (tracker == null) {
            return;
        }
        if (tracker.isDone() || event.getServer().getTickCount() >= deadline) {
            CobbleDollarsCommandShopsMod.LOGGER.info("COMPATIBILITY_RESULT: {} / {} complete, {} failures.",
                    tracker.getDoneCount(), tracker.getTotalCount(), tracker.getFailedRequiredCount());
            tracker = null;
            event.getServer().halt(false);
        }
    }
}
