package fr.cobbledollars.commandshops.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import fr.cobbledollars.commandshops.network.payload.ShopUiStatePayload;

public final class ClientUiFormatter {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss z");

    private ClientUiFormatter() {
    }

    public static String formatSelectedOfferStatus(ShopUiStatePayload.OfferState offerState, long nowMillis) {
        if (offerState == null) {
            return null;
        }
        if (offerState.hasNextRestock()) {
            return "Next restock: +" + offerState.nextRestockAmount() + " at " + formatRestockMoment(offerState, nowMillis) + ".";
        }
        if (offerState.stock() == 0) {
            return "Selected offer is out of stock.";
        }
        return null;
    }

    private static String formatRestockMoment(ShopUiStatePayload.OfferState offerState, long nowMillis) {
        ZoneId zoneId = resolveZoneId(offerState.restockZoneId());
        long nextRestockAtMillis = offerState.nextRestockAtMillis();
        return TIME_FORMAT.format(Instant.ofEpochMilli(nextRestockAtMillis).atZone(zoneId))
                + " (in " + formatDuration(Math.max(0L, nextRestockAtMillis - nowMillis)) + ")";
    }

    private static ZoneId resolveZoneId(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(zoneId);
        } catch (RuntimeException ignored) {
            return ZoneId.systemDefault();
        }
    }

    private static String formatDuration(long deltaMillis) {
        long totalSeconds = Math.max(0L, deltaMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0L) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
