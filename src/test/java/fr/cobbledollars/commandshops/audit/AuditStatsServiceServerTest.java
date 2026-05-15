package fr.cobbledollars.commandshops.audit;

import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(EphemeralTestServerProvider.class)
class AuditStatsServiceServerTest {
    @AfterEach
    void cleanup() throws Exception {
        AuditLogService.shutdown();
        Files.deleteIfExists(AuditFiles.getConfigFile());
        deleteRecursively(AuditLogService.getLogFile().getParent());
    }

    @Test
    void readSnapshotAggregatesAuditEventsAcrossSummaryAndTopViews(MinecraftServer server) throws Exception {
        Instant now = Instant.parse("2026-05-15T10:00:00Z");
        writeAuditLog(List.of(
                buySuccess(now.minus(2, ChronoUnit.HOURS), "general_store", "pokeball", "uuid-ash", "Ash", 120, 2, 16, "cobblemon:poke_ball", 120),
                buySuccess(now.minus(90, ChronoUnit.MINUTES), "general_store", "greatball", "uuid-misty", "Misty", 200, 1, 8, "cobblemon:great_ball", 200),
                sellSuccess(now.minus(45, ChronoUnit.MINUTES), "general_store", "uuid-ash", "Ash", 45, 3, "cobblemon:oran_berry", 15, 45),
                buyFailure(now.minus(30, ChronoUnit.MINUTES), "night_market", "moon_ball", "uuid-ash", "Ash"),
                sellFailure(now.minus(20, ChronoUnit.MINUTES), "general_store", "uuid-misty", "Misty"),
                visibilityChanged(now.minus(10, ChronoUnit.MINUTES), "night_market", false),
                "not-json"
        ));

        AuditStatsSnapshot snapshot = AuditStatsService.readSnapshot(AuditTimeWindow.ALL, now);

        assertEquals(2L, snapshot.buySuccessCount());
        assertEquals(1L, snapshot.buyFailureCount());
        assertEquals(1L, snapshot.sellSuccessCount());
        assertEquals(1L, snapshot.sellFailureCount());
        assertEquals(1L, snapshot.visibilityChangeCount());
        assertEquals(new BigInteger("320"), snapshot.totalSpent());
        assertEquals(new BigInteger("45"), snapshot.totalEarned());
        assertEquals(new BigInteger("275"), snapshot.netFlow());

        AuditStatsSnapshot.ShopStats generalStore = snapshot.shop("general_store");
        assertNotNull(generalStore);
        assertEquals(2L, generalStore.buySuccessCount());
        assertEquals(1L, generalStore.sellSuccessCount());
        assertEquals(1L, generalStore.sellFailureCount());
        assertEquals(24L, generalStore.boughtItemCount());
        assertEquals(3L, generalStore.soldItemCount());

        AuditStatsSnapshot.ShopStats nightMarket = snapshot.shop("night_market");
        assertNotNull(nightMarket);
        assertEquals(1L, nightMarket.buyFailureCount());
        assertEquals(1L, nightMarket.visibilityChangeCount());
        assertNull(snapshot.shop("missing_shop"));

        AuditStatsSnapshot.PlayerStats ash = snapshot.findPlayer("ash");
        assertNotNull(ash);
        assertEquals("uuid-ash", ash.playerUuid());
        assertEquals(1L, ash.buySuccessCount());
        assertEquals(1L, ash.buyFailureCount());
        assertEquals(1L, ash.sellSuccessCount());
        assertEquals(new BigInteger("120"), ash.spent());
        assertEquals(new BigInteger("45"), ash.earned());

        AuditStatsSnapshot.ItemStats pokeBall = snapshot.item("COBBLEMON:POKE_BALL");
        assertNotNull(pokeBall);
        assertEquals(16L, pokeBall.boughtCount());
        assertEquals(new BigInteger("120"), pokeBall.spent());

        AuditStatsSnapshot.ItemStats oranBerry = snapshot.item("cobblemon:oran_berry");
        assertNotNull(oranBerry);
        assertEquals(3L, oranBerry.soldCount());
        assertEquals(new BigInteger("45"), oranBerry.earned());

        List<AuditStatsSnapshot.ShopStats> topShops = snapshot.topShops(2);
        assertEquals(2, topShops.size());
        assertEquals("general_store", topShops.get(0).shopId());

        List<AuditStatsSnapshot.OfferStats> topOffers = snapshot.topOffers(2);
        assertEquals(2, topOffers.size());
        assertEquals("greatball", topOffers.get(0).offerId());
        assertEquals("pokeball", topOffers.get(1).offerId());

        List<AuditStatsSnapshot.ItemStats> topItems = snapshot.topItems(1);
        assertEquals(1, topItems.size());
        assertEquals("cobblemon:great_ball", topItems.get(0).itemId());
    }

    @Test
    void readSnapshotFiltersEventsByRequestedWindow(MinecraftServer server) throws Exception {
        Instant now = Instant.parse("2026-05-15T10:00:00Z");
        writeAuditLog(List.of(
                buySuccess(now.minus(9, ChronoUnit.DAYS), "general_store", "old_offer", "uuid-old", "OldTimer", 80, 1, 4, "minecraft:apple", 80),
                buySuccess(now.minus(2, ChronoUnit.DAYS), "general_store", "recent_offer", "uuid-new", "Recent", 50, 1, 2, "minecraft:bread", 50)
        ));

        AuditStatsSnapshot allTime = AuditStatsService.readSnapshot(AuditTimeWindow.ALL, now);
        AuditStatsSnapshot lastSevenDays = AuditStatsService.readSnapshot(AuditTimeWindow.LAST_7_DAYS, now);

        assertEquals(2L, allTime.buySuccessCount());
        assertEquals(new BigInteger("130"), allTime.totalSpent());
        assertEquals(1L, lastSevenDays.buySuccessCount());
        assertEquals(new BigInteger("50"), lastSevenDays.totalSpent());
        assertNull(lastSevenDays.item("minecraft:apple"));
        assertNotNull(lastSevenDays.item("minecraft:bread"));
    }

    @Test
    void readSnapshotIncludesCompressedArchivesAndActiveLog(MinecraftServer server) throws Exception {
        Instant now = Instant.parse("2026-05-15T10:00:00Z");
        writeCompressedArchive(
                "audit-2026-05-14_09-00-00.jsonl.zip",
                List.of(
                        buySuccess(now.minus(2, ChronoUnit.DAYS), "general_store", "archive_offer", "uuid-archive", "Archive", 90, 1, 3, "minecraft:apple", 90),
                        "not-json"
                )
        );
        writeAuditLog(List.of(
                buySuccess(now.minus(1, ChronoUnit.HOURS), "general_store", "active_offer", "uuid-active", "Active", 30, 1, 1, "minecraft:bread", 30)
        ));

        AuditStatsSnapshot snapshot = AuditStatsService.readSnapshot(AuditTimeWindow.ALL, now);

        assertEquals(2L, snapshot.buySuccessCount());
        assertEquals(new BigInteger("120"), snapshot.totalSpent());
        assertNotNull(snapshot.item("minecraft:apple"));
        assertNotNull(snapshot.item("minecraft:bread"));
    }

    private static void writeAuditLog(List<String> lines) throws IOException {
        Path logFile = AuditLogService.getLogFile();
        Files.createDirectories(logFile.getParent());
        Files.write(logFile, lines);
    }

    private static void writeCompressedArchive(String fileName, List<String> lines) throws IOException {
        Path archiveFile = AuditLogService.getLogFile().getParent().resolve(fileName);
        Files.createDirectories(archiveFile.getParent());
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(archiveFile), StandardCharsets.UTF_8)) {
            outputStream.putNextEntry(new ZipEntry(fileName.replace(".zip", "")));
            for (String line : lines) {
                outputStream.write(line.getBytes(StandardCharsets.UTF_8));
                outputStream.write('\n');
            }
            outputStream.closeEntry();
        }
    }

    private static String buySuccess(
            Instant timestamp,
            String shopId,
            String offerId,
            String playerUuid,
            String playerName,
            int currencyAmount,
            int bundleCount,
            int itemCountTotal,
            String itemId,
            int lineTotal
    ) {
        return """
                {"schema_version":1,"type":"buy_success","timestamp":"%s","shop_id":"%s","offer_id":"%s","player_uuid":"%s","player_name":"%s","currency_amount":"%s","bundle_count":%s,"item_count_total":%s,"item_lines":[{"item":"%s","count":%s,"line_total":"%s"}],"bonus_lines":[]}
                """.formatted(timestamp, shopId, offerId, playerUuid, playerName, currencyAmount, bundleCount, itemCountTotal, itemId, itemCountTotal, lineTotal);
    }

    private static String sellSuccess(
            Instant timestamp,
            String shopId,
            String playerUuid,
            String playerName,
            int currencyAmount,
            int itemCountTotal,
            String itemId,
            int unitPrice,
            int lineTotal
    ) {
        return """
                {"schema_version":1,"type":"sell_success","timestamp":"%s","shop_id":"%s","player_uuid":"%s","player_name":"%s","currency_amount":"%s","item_count_total":%s,"item_lines":[{"item":"%s","count":%s,"unit_price":"%s","line_total":"%s"}]}
                """.formatted(timestamp, shopId, playerUuid, playerName, currencyAmount, itemCountTotal, itemId, itemCountTotal, unitPrice, lineTotal);
    }

    private static String buyFailure(Instant timestamp, String shopId, String offerId, String playerUuid, String playerName) {
        return """
                {"schema_version":1,"type":"buy_failure","timestamp":"%s","shop_id":"%s","offer_id":"%s","player_uuid":"%s","player_name":"%s","reason":"not_enough_money"}
                """.formatted(timestamp, shopId, offerId, playerUuid, playerName);
    }

    private static String sellFailure(Instant timestamp, String shopId, String playerUuid, String playerName) {
        return """
                {"schema_version":1,"type":"sell_failure","timestamp":"%s","shop_id":"%s","player_uuid":"%s","player_name":"%s","reason":"nothing_matches"}
                """.formatted(timestamp, shopId, playerUuid, playerName);
    }

    private static String visibilityChanged(Instant timestamp, String shopId, boolean enabled) {
        return """
                {"schema_version":1,"type":"visibility_changed","timestamp":"%s","shop_id":"%s","enabled":%s}
                """.formatted(timestamp, shopId, enabled);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(currentPath -> {
                try {
                    Files.deleteIfExists(currentPath);
                } catch (IOException exception) {
                    throw new RuntimeException("Failed to delete test path: " + currentPath, exception);
                }
            });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }
}
