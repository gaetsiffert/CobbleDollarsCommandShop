package fr.cobbledollars.commandshops.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

import fr.cobbledollars.commandshops.shop.ShopRegistry;

public final class BenchConfigSupport {
    public static final String HEAVY_BENCHMARK_SHOP_ID = "perf_megastore";

    private BenchConfigSupport() {
    }

    public static Path projectDir() {
        String configuredProjectDir = System.getProperty("commandshops.projectDir");
        if (configuredProjectDir == null || configuredProjectDir.isBlank()) {
            return Path.of("").toAbsolutePath().normalize();
        }
        return Path.of(configuredProjectDir).toAbsolutePath().normalize();
    }

    public static Path benchmarkSourceDirectory() {
        return projectDir().resolve("bench-configs").resolve("heavy_shop_bank").resolve(HEAVY_BENCHMARK_SHOP_ID);
    }

    public static Path stageHeavyBenchmarkShop() throws IOException {
        Path sourceDirectory = benchmarkSourceDirectory();
        if (!Files.isDirectory(sourceDirectory)) {
            throw new IOException("Heavy benchmark directory is missing: " + sourceDirectory);
        }

        Path targetDirectory = ShopRegistry.getShopDirectory().resolve(HEAVY_BENCHMARK_SHOP_ID);
        deleteRecursively(targetDirectory);
        copyRecursively(sourceDirectory, targetDirectory);
        return targetDirectory;
    }

    public static void cleanupHeavyBenchmarkShop() throws IOException {
        deleteRecursively(ShopRegistry.getShopDirectory().resolve(HEAVY_BENCHMARK_SHOP_ID));
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path sourcePath : paths.toList()) {
                Path relativePath = source.relativize(sourcePath);
                Path targetPath = target.resolve(relativePath);
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(path)) {
            for (Path currentPath : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(currentPath);
            }
        }
    }
}
