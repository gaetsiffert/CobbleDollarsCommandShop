package fr.cobbledollars.commandshops.gametest;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import fr.cobbledollars.commandshops.perf.BenchConfigSupport;
import fr.cobbledollars.commandshops.perf.PerfHarness;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import fr.harmex.cobbledollars.common.world.inventory.BankMenu;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

@ForEachTest(idPrefix = "perf.", side = Dist.DEDICATED_SERVER)
public final class CommandShopPerformanceGameTests {
    private CommandShopPerformanceGameTests() {
    }

    @TestHolder(
            value = "open_heavy_shop_benchmark",
            title = "Open heavy shop benchmark",
            description = "Measures repeated openShop calls against the heavy benchmark pack.",
            enabledByDefault = false
    )
    @GameTest(batch = "perf.open_heavy_shop", timeoutTicks = 600, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void open_heavy_shop_benchmark(ExtendedGameTestHelper helper) {
        if (!isPerfRun()) {
            helper.succeed();
            return;
        }
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            ShopDefinition shop = prepareHeavyBenchmark(helper);

            PerfHarness.Measurement measurement = PerfHarness.measure(
                    "sessions.openShop." + BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID,
                    3,
                    12,
                    () -> {
                        resetPlayerSession(player);
                        CommandShopSessions.openShop(player, shop);
                        requireShopMenu(player);
                        resetPlayerSession(player);
                    }
            );

            Path report = PerfHarness.writeReport(
                    "gametest-v2-open-heavy-shop",
                    "CommandShops GameTest Performance (v2) - openShop",
                    List.of(measurement)
            );
            if (!Files.isRegularFile(report)) {
                throw new IllegalStateException("The openShop perf markdown report was not created.");
            }
            if (!Files.isRegularFile(report.resolveSibling("gametest-v2-open-heavy-shop.json"))) {
                throw new IllegalStateException("The openShop perf JSON report was not created.");
            }
        } catch (Exception exception) {
            helper.fail("Open heavy shop perf GameTest failed: " + exception.getMessage());
            return;
        } finally {
            cleanupHeavyBenchmark(player);
        }

        helper.succeed();
    }

    @TestHolder(
            value = "refresh_heavy_shop_benchmark",
            title = "Refresh heavy shop benchmark",
            description = "Measures repeated refreshPlayerSession calls against an active heavy shop session.",
            enabledByDefault = false
    )
    @GameTest(batch = "perf.refresh_heavy_shop", timeoutTicks = 600, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void refresh_heavy_shop_benchmark(ExtendedGameTestHelper helper) {
        if (!isPerfRun()) {
            helper.succeed();
            return;
        }
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            ShopDefinition shop = prepareHeavyBenchmark(helper);
            CommandShopSessions.openShop(player, shop);
            requireShopMenu(player);

            PerfHarness.Measurement measurement = PerfHarness.measure(
                    "sessions.refreshPlayerSession." + BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID,
                    3,
                    20,
                    () -> {
                        CommandShopSessions.refreshPlayerSession(player);
                        requireShopMenu(player);
                    }
            );

            Path report = PerfHarness.writeReport(
                    "gametest-v2-refresh-heavy-shop",
                    "CommandShops GameTest Performance (v2) - refreshPlayerSession",
                    List.of(measurement)
            );
            if (!Files.isRegularFile(report)) {
                throw new IllegalStateException("The refresh perf markdown report was not created.");
            }
            if (!Files.isRegularFile(report.resolveSibling("gametest-v2-refresh-heavy-shop.json"))) {
                throw new IllegalStateException("The refresh perf JSON report was not created.");
            }

            List<PerfHarness.Measurement> breakdownMeasurements =
                    captureRefreshBreakdownMeasurements(player, BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID, 3, 20);
            Path breakdownReport = PerfHarness.writeReport(
                    "gametest-v2-refresh-heavy-shop-breakdown",
                    "CommandShops GameTest Performance (v2) - refreshPlayerSession breakdown",
                    breakdownMeasurements
            );
            if (!Files.isRegularFile(breakdownReport)) {
                throw new IllegalStateException("The refresh breakdown markdown report was not created.");
            }
            if (!Files.isRegularFile(breakdownReport.resolveSibling("gametest-v2-refresh-heavy-shop-breakdown.json"))) {
                throw new IllegalStateException("The refresh breakdown JSON report was not created.");
            }
        } catch (Exception exception) {
            helper.fail("Refresh heavy shop perf GameTest failed: " + exception.getMessage());
            return;
        } finally {
            cleanupHeavyBenchmark(player);
        }

        helper.succeed();
    }

    @TestHolder(
            value = "sell_heavy_bank_benchmark",
            title = "Sell heavy bank benchmark",
            description = "Measures repeated custom bank sell calls against the heavy benchmark bank runtime.",
            enabledByDefault = false
    )
    @GameTest(batch = "perf.sell_heavy_bank", timeoutTicks = 600, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void sell_heavy_bank_benchmark(ExtendedGameTestHelper helper) {
        if (!isPerfRun()) {
            helper.succeed();
            return;
        }
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            ShopDefinition shop = prepareHeavyBenchmark(helper);
            CommandShopSessions.openShop(player, shop);
            requireShopMenu(player);

            UUID merchantUuid = ((ShopMenu) player.containerMenu).getCobbleMerchant().getMerchantUUID();
            if (!CommandShopSessions.openCustomBank(player, merchantUuid)) {
                throw new IllegalStateException("openCustomBank returned false for the heavy benchmark shop.");
            }
            BankMenu bankMenu = requireBankMenu(player);

            PerfHarness.Measurement measurement = PerfHarness.measure(
                    "sessions.handleCustomSell." + BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID,
                    3,
                    20,
                    () -> {
                        PlayerExtensionKt.setCobbleDollars(player, BigInteger.ZERO);
                        bankMenu.getBankContainer().clearContent();
                        bankMenu.getBankContainer().setItem(0, new ItemStack(Items.DIAMOND, 64));
                        if (!CommandShopSessions.handleCustomSell(helper.getLevel().getServer(), player)) {
                            throw new IllegalStateException("handleCustomSell returned false for an active heavy benchmark bank session.");
                        }
                        if (!bankMenu.getBankContainer().getItem(0).isEmpty()) {
                            throw new IllegalStateException("The accepted benchmark sell stack was not consumed.");
                        }
                        if (PlayerExtensionKt.getCobbleDollars(player).compareTo(BigInteger.ZERO) <= 0) {
                            throw new IllegalStateException("The benchmark sell did not credit the player.");
                        }
                    }
            );

            Path report = PerfHarness.writeReport(
                    "gametest-v2-sell-heavy-bank",
                    "CommandShops GameTest Performance (v2) - handleCustomSell",
                    List.of(measurement)
            );
            if (!Files.isRegularFile(report)) {
                throw new IllegalStateException("The sell perf markdown report was not created.");
            }
            if (!Files.isRegularFile(report.resolveSibling("gametest-v2-sell-heavy-bank.json"))) {
                throw new IllegalStateException("The sell perf JSON report was not created.");
            }
        } catch (Exception exception) {
            helper.fail("Sell heavy bank perf GameTest failed: " + exception.getMessage());
            return;
        } finally {
            cleanupHeavyBenchmark(player);
        }

        helper.succeed();
    }

    private static ShopDefinition prepareHeavyBenchmark(ExtendedGameTestHelper helper) throws Exception {
        BenchConfigSupport.cleanupHeavyBenchmarkShop();
        BenchConfigSupport.stageHeavyBenchmarkShop();
        ShopRegistry.reload(helper.getLevel().registryAccess());
        ShopDefinition shop = ShopRegistry.getShop(BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID);
        if (shop == null) {
            throw new IllegalStateException("The heavy benchmark shop was not loaded after staging.");
        }
        return shop;
    }

    private static ShopMenu requireShopMenu(GameTestPlayer player) {
        if (!(player.containerMenu instanceof ShopMenu shopMenu)) {
            throw new IllegalStateException("Expected an active ShopMenu for the benchmark player.");
        }
        return shopMenu;
    }

    private static BankMenu requireBankMenu(GameTestPlayer player) {
        if (!(player.containerMenu instanceof BankMenu bankMenu)) {
            throw new IllegalStateException("Expected an active BankMenu for the benchmark player.");
        }
        return bankMenu;
    }

    private static void resetPlayerSession(GameTestPlayer player) {
        player.closeContainer();
        CommandShopSessions.cleanupPlayer(player.getUUID());
    }

    private static void cleanupHeavyBenchmark(GameTestPlayer player) {
        try {
            resetPlayerSession(player);
            BenchConfigSupport.cleanupHeavyBenchmarkShop();
            ShopRegistry.reload(player.level().registryAccess());
        } catch (Exception ignored) {
        }
    }

    private static boolean isPerfRun() {
        return Boolean.getBoolean("commandshops.perfGametests");
    }

    private static List<PerfHarness.Measurement> captureRefreshBreakdownMeasurements(
            GameTestPlayer player,
            String shopId,
            int warmupIterations,
            int measuredIterations
    ) {
        for (int index = 0; index < warmupIterations; index++) {
            CommandShopSessions.measureRefreshPlayerSession(player);
            requireShopMenu(player);
        }

        long[] totalNanos = new long[measuredIterations];
        long[] resolveSessionShopNanos = new long[measuredIterations];
        long[] createRuntimeDataNanos = new long[measuredIterations];
        long[] createRuntimeDataContextNanos = new long[measuredIterations];
        long[] createRuntimeDataCandidateSelectionNanos = new long[measuredIterations];
        long[] createRuntimeDataMaterializationNanos = new long[measuredIterations];
        long[] refreshSessionShopNanos = new long[measuredIterations];
        long[] syncClientShopUiStateNanos = new long[measuredIterations];
        long[] syncClientShopUiStateBuildNanos = new long[measuredIterations];
        long[] syncClientShopUiStateSendNanos = new long[measuredIterations];
        long[] updateSessionRefreshStateNanos = new long[measuredIterations];

        for (int index = 0; index < measuredIterations; index++) {
            CommandShopSessions.RefreshBreakdown breakdown = CommandShopSessions.measureRefreshPlayerSession(player);
            requireShopMenu(player);
            totalNanos[index] = breakdown.totalNanos();
            resolveSessionShopNanos[index] = breakdown.resolveSessionShopNanos();
            createRuntimeDataNanos[index] = breakdown.createRuntimeDataNanos();
            createRuntimeDataContextNanos[index] = breakdown.createRuntimeDataContextNanos();
            createRuntimeDataCandidateSelectionNanos[index] = breakdown.createRuntimeDataCandidateSelectionNanos();
            createRuntimeDataMaterializationNanos[index] = breakdown.createRuntimeDataMaterializationNanos();
            refreshSessionShopNanos[index] = breakdown.refreshSessionShopNanos();
            syncClientShopUiStateNanos[index] = breakdown.syncClientShopUiStateNanos();
            syncClientShopUiStateBuildNanos[index] = breakdown.syncClientShopUiStateBuildNanos();
            syncClientShopUiStateSendNanos[index] = breakdown.syncClientShopUiStateSendNanos();
            updateSessionRefreshStateNanos[index] = breakdown.updateSessionRefreshStateNanos();
        }

        return List.of(
                PerfHarness.summarizeSamples("sessions.refreshPlayerSession." + shopId + ".total", warmupIterations, totalNanos),
                PerfHarness.summarizeSamples("sessions.refreshPlayerSession." + shopId + ".resolveSessionShop", warmupIterations, resolveSessionShopNanos),
                PerfHarness.summarizeSamples("sessions.refreshPlayerSession." + shopId + ".createRuntimeData", warmupIterations, createRuntimeDataNanos),
                PerfHarness.summarizeSamples("sessions.refreshPlayerSession." + shopId + ".createRuntimeData.context", warmupIterations, createRuntimeDataContextNanos),
                PerfHarness.summarizeSamples("sessions.refreshPlayerSession." + shopId + ".createRuntimeData.candidateSelection", warmupIterations, createRuntimeDataCandidateSelectionNanos),
                PerfHarness.summarizeSamples("sessions.refreshPlayerSession." + shopId + ".createRuntimeData.materialization", warmupIterations, createRuntimeDataMaterializationNanos),
                PerfHarness.summarizeSamples("sessions.refreshPlayerSession." + shopId + ".refreshSessionShop", warmupIterations, refreshSessionShopNanos),
                PerfHarness.summarizeSamples("sessions.refreshPlayerSession." + shopId + ".syncClientShopUiState", warmupIterations, syncClientShopUiStateNanos),
                PerfHarness.summarizeSamples("sessions.refreshPlayerSession." + shopId + ".syncClientShopUiState.build", warmupIterations, syncClientShopUiStateBuildNanos),
                PerfHarness.summarizeSamples("sessions.refreshPlayerSession." + shopId + ".syncClientShopUiState.send", warmupIterations, syncClientShopUiStateSendNanos),
                PerfHarness.summarizeSamples("sessions.refreshPlayerSession." + shopId + ".updateSessionRefreshState", warmupIterations, updateSessionRefreshStateNanos)
        );
    }
}
