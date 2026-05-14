package fr.cobbledollars.commandshops.shop;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import fr.cobbledollars.commandshops.perf.BenchConfigSupport;
import fr.cobbledollars.commandshops.perf.PerfHarness;
import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("perf")
@ExtendWith(EphemeralTestServerProvider.class)
class CommandShopPerformanceTest {
    @AfterEach
    void cleanup() throws IOException {
        ShopRegistry.clear();
        BenchConfigSupport.cleanupHeavyBenchmarkShop();
    }

    @Test
    void writes_junit_runtime_perf_report_for_heavy_benchmark_pack(MinecraftServer server) throws Exception {
        ShopRegistry.clear();
        ShopRegistry.initialize(server.registryAccess());
        BenchConfigSupport.stageHeavyBenchmarkShop();
        ShopRegistry.reload(server.registryAccess());

        PerfHarness.Measurement reloadMeasurement = PerfHarness.measure(
                "registry.reload." + BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID,
                3,
                10,
                () -> ShopRegistry.reload(server.registryAccess())
        );

        ShopDefinition shop = ShopRegistry.getShop(BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID);
        assertNotNull(shop, "The heavy benchmark shop was not loaded before runtime measurements.");
        BankDefinition bank = ShopRegistry.getBankDefinition(BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID);
        assertNotNull(bank, "The heavy benchmark bank was not loaded before runtime measurements.");

        Path benchmarkShopDirectory = ShopRegistry.getShopDirectory().resolve(BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID);
        Path benchmarkShopFile = ShopFiles.resolveShopFile(benchmarkShopDirectory);
        Path benchmarkBankFile = BankFiles.resolveLocalBankFile(benchmarkShopDirectory);
        assertTrue(Files.isRegularFile(benchmarkShopFile), "The heavy benchmark shop.json file was not staged.");
        assertTrue(Files.isRegularFile(benchmarkBankFile), "The heavy benchmark bank.json file was not staged.");

        PerfHarness.Measurement shopParseMeasurement = PerfHarness.measure(
                "shop.parse." + BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID,
                5,
                25,
                () -> ShopFiles.parseShopFile(BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID, benchmarkShopFile, server.registryAccess())
        );
        PerfHarness.Measurement bankParseMeasurement = PerfHarness.measure(
                "bank.parse." + BenchConfigSupport.HEAVY_BENCHMARK_SHOP_ID,
                5,
                25,
                () -> BankFiles.loadBankFile(benchmarkBankFile, server.registryAccess())
        );

        Path report = PerfHarness.writeReport(
                "junit-runtime-v1",
                "CommandShops JUnit Runtime Performance (v1)",
                List.of(reloadMeasurement, shopParseMeasurement, bankParseMeasurement)
        );

        assertTrue(Files.isRegularFile(report), "The JUnit perf markdown report was not created.");
        assertTrue(Files.isRegularFile(report.resolveSibling("junit-runtime-v1.json")),
                "The JUnit perf JSON report was not created.");
    }
}
