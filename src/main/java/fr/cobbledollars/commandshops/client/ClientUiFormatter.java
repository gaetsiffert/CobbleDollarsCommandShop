package fr.cobbledollars.commandshops.client;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import fr.cobbledollars.commandshops.network.payload.ShopUiStatePayload;
import net.minecraft.network.chat.Component;

public final class ClientUiFormatter {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss z", Locale.ROOT);
    private static final BigInteger THOUSAND = BigInteger.valueOf(1_000L);
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000L);
    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger TRILLION = BigInteger.valueOf(1_000_000_000_000L);

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

    public static Component formatMoney(BigInteger amount) {
        return Component.literal(amount.toString());
    }

    public static String formatCompactMoney(BigInteger amount) {
        if (amount == null) {
            return "-";
        }
        BigInteger absolute = amount.abs();
        if (absolute.compareTo(THOUSAND) < 0) {
            return amount.toString();
        }
        if (absolute.compareTo(MILLION) < 0) {
            return formatCompact(amount, THOUSAND, "k");
        }
        if (absolute.compareTo(BILLION) < 0) {
            return formatCompact(amount, MILLION, "m");
        }
        if (absolute.compareTo(TRILLION) < 0) {
            return formatCompact(amount, BILLION, "b");
        }
        return formatCompact(amount, TRILLION, "t");
    }

    public static String formatAbsoluteRestockTime(ShopUiStatePayload.OfferState offerState) {
        ZoneId zoneId = resolveZoneId(offerState.restockZoneId());
        return TIME_FORMAT.format(Instant.ofEpochMilli(offerState.nextRestockAtMillis()).atZone(zoneId));
    }

    public static Component formatDuration(long deltaMillis) {
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

    private static String formatCompact(BigInteger amount, BigInteger unit, String suffix) {
        String sign = amount.signum() < 0 ? "-" : "";
        BigInteger absolute = amount.abs();
        BigInteger whole = absolute.divide(unit);
        BigInteger remainder = absolute.remainder(unit);
        if (whole.compareTo(BigInteger.TEN) < 0 && remainder.signum() > 0) {
            int decimal = remainder.multiply(BigInteger.TEN).divide(unit).intValue();
            if (decimal > 0) {
                return sign + whole + "." + decimal + suffix;
            }
        }
        return sign + whole + suffix;
    }
}
