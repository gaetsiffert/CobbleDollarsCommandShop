package fr.cobbledollars.commandshops.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import fr.cobbledollars.commandshops.network.payload.ShopUiStatePayload;
import net.minecraft.network.chat.Component;

public final class ClientUiFormatter {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss z", Locale.ROOT);

    private ClientUiFormatter() {
    }

    public static Component formatSelectedOfferStatus(ShopUiStatePayload.OfferState offerState, long nowMillis) {
        if (offerState == null) {
            return null;
        }
        if (offerState.hasNextRestock()) {
            return Component.translatable(
                    "cobbledollarscommandshops.ui.selected_offer.next_restock",
                    offerState.nextRestockAmount(),
                    formatAbsoluteRestockTime(offerState),
                    formatDuration(Math.max(0L, offerState.nextRestockAtMillis() - nowMillis))
            );
        }
        if (offerState.stock() == 0) {
            return Component.translatable("cobbledollarscommandshops.ui.selected_offer.out_of_stock");
        }
        return null;
    }

    private static String formatAbsoluteRestockTime(ShopUiStatePayload.OfferState offerState) {
        ZoneId zoneId = resolveZoneId(offerState.restockZoneId());
        return TIME_FORMAT.format(Instant.ofEpochMilli(offerState.nextRestockAtMillis()).atZone(zoneId));
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

    private static Component formatDuration(long deltaMillis) {
        long totalSeconds = Math.max(0L, deltaMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) {
            return Component.translatable("cobbledollarscommandshops.time.duration.hms", hours, minutes, seconds);
        }
        if (minutes > 0L) {
            return Component.translatable("cobbledollarscommandshops.time.duration.ms", minutes, seconds);
        }
        return Component.translatable("cobbledollarscommandshops.time.duration.s", seconds);
    }
}
