package fr.cobbledollars.commandshops.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.StackCountMath;
import fr.cobbledollars.commandshops.feedback.BuyFailureReason;
import fr.cobbledollars.commandshops.feedback.SellFailureReason;
import fr.cobbledollars.commandshops.shop.ResolvedShopOffer;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class AuditLogService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int SCHEMA_VERSION = 1;
    private static final Path LOG_FILE = FMLPaths.GAMEDIR.get()
            .resolve("logs")
            .resolve(CobbleDollarsCommandShopsMod.MODID)
            .resolve("audit.jsonl");
    private static final Object LOCK = new Object();
    private static final String LINE_SEPARATOR = System.lineSeparator();

    private static Writer logWriter;
    private static boolean logDirectoryReady;
    private static AuditConfig config = AuditConfig.defaults();

    private AuditLogService() {
    }

    public static void initialize() throws IOException {
        synchronized (LOCK) {
            closeWriterQuietly();
            logDirectoryReady = false;
            AuditFiles.ensureDefaultConfigExists();
            config = AuditFiles.loadConfig();
        }
    }

    public static void reload() throws IOException {
        synchronized (LOCK) {
            closeWriterQuietly();
            logDirectoryReady = false;
            config = AuditFiles.loadConfig();
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            closeWriterQuietly();
            logDirectoryReady = false;
            config = AuditConfig.defaults();
        }
    }

    public static Path getLogFile() {
        return LOG_FILE;
    }

    public static void logBuySuccess(
            ServerPlayer player,
            ShopDefinition shop,
            ResolvedShopOffer offer,
            int bundleCount,
            BigInteger currencyAmount,
            List<ItemStack> bonusItems,
            Integer stockBefore,
            Integer stockAfter
    ) {
        if (!isEventEnabled(AuditEventType.BUY_SUCCESS)) {
            return;
        }

        ItemStack offerTemplate = offer.itemStack();
        long itemCountTotal = StackCountMath.multiplyToLong(offerTemplate.getCount(), bundleCount);
        JsonObject event = basePlayerEvent(AuditEventType.BUY_SUCCESS, player);
        event.addProperty("shop_id", shop.id());
        event.addProperty("offer_id", offer.sourceId());
        event.addProperty("offer_match_kind", offer.matchKind().name().toLowerCase(Locale.ROOT));
        event.addProperty("bundle_count", bundleCount);
        event.addProperty("item_count_total", itemCountTotal);
        event.addProperty("currency_flow", "sink");
        event.addProperty("currency_amount", currencyAmount.toString());
        event.add("item_lines", singleItemArray(offerTemplate, itemCountTotal, null, currencyAmount));
        event.add("bonus_lines", itemLinesFromStacks(bonusItems));
        addStockState(event, stockBefore, stockAfter);
        append(event);
    }

    public static void logBuyFailure(
            ServerPlayer player,
            ShopDefinition shop,
            ResolvedShopOffer offer,
            BuyFailureReason reason,
            Integer bundleCount,
            Integer stockBefore,
            BigInteger currencyAmount
    ) {
        if (!isEventEnabled(AuditEventType.BUY_FAILURE)) {
            return;
        }

        JsonObject event = basePlayerEvent(AuditEventType.BUY_FAILURE, player);
        event.addProperty("shop_id", shop.id());
        event.addProperty("reason", reason.name().toLowerCase(Locale.ROOT));
        if (offer != null) {
            event.addProperty("offer_id", offer.sourceId());
            event.addProperty("offer_match_kind", offer.matchKind().name().toLowerCase(Locale.ROOT));
        }
        if (bundleCount != null && bundleCount > 0) {
            event.addProperty("bundle_count", bundleCount);
            if (offer != null) {
                event.addProperty("item_count_total", StackCountMath.multiplyToLong(offer.itemStack().getCount(), bundleCount));
            }
        }
        if (currencyAmount != null) {
            event.addProperty("currency_flow", "sink");
            event.addProperty("currency_amount", currencyAmount.toString());
        }
        if (offer != null && bundleCount != null && bundleCount > 0) {
            event.add("item_lines", singleItemArray(offer.itemStack(), StackCountMath.multiplyToLong(offer.itemStack().getCount(), bundleCount), null, currencyAmount));
        }
        addStockState(event, stockBefore, null);
        append(event);
    }

    public static void logSellSuccess(
            ServerPlayer player,
            ShopDefinition shop,
            int soldItemCount,
            BigInteger currencyAmount,
            List<SoldItemLine> itemLines
    ) {
        if (!isEventEnabled(AuditEventType.SELL_SUCCESS)) {
            return;
        }

        JsonObject event = basePlayerEvent(AuditEventType.SELL_SUCCESS, player);
        event.addProperty("shop_id", shop.id());
        event.addProperty("item_count_total", soldItemCount);
        event.addProperty("currency_flow", "source");
        event.addProperty("currency_amount", currencyAmount.toString());
        JsonArray lines = new JsonArray();
        for (SoldItemLine itemLine : itemLines) {
            lines.add(itemLineToJson(itemLine.stack(), itemLine.stack().getCount(), itemLine.unitPrice(), itemLine.lineTotal()));
        }
        event.add("item_lines", lines);
        append(event);
    }

    public static void logSellFailure(ServerPlayer player, ShopDefinition shop, SellFailureReason reason) {
        if (!isEventEnabled(AuditEventType.SELL_FAILURE)) {
            return;
        }

        JsonObject event = basePlayerEvent(AuditEventType.SELL_FAILURE, player);
        event.addProperty("shop_id", shop.id());
        event.addProperty("reason", reason.name().toLowerCase(Locale.ROOT));
        append(event);
    }

    public static void logVisibilityChanged(String shopId, boolean enabled, String reasonMessage, String actorName, String actorUuid) {
        if (!isEventEnabled(AuditEventType.VISIBILITY_CHANGED)) {
            return;
        }

        JsonObject event = baseAdminEvent(AuditEventType.VISIBILITY_CHANGED);
        event.addProperty("shop_id", shopId);
        event.addProperty("action", enabled ? "enable" : "disable");
        event.addProperty("enabled", enabled);
        if (reasonMessage != null && !reasonMessage.isBlank()) {
            event.addProperty("reason_message", reasonMessage);
        }
        if (actorName != null) {
            event.addProperty("actor_name", actorName);
        }
        if (actorUuid != null) {
            event.addProperty("actor_uuid", actorUuid);
        }
        append(event);
    }

    private static boolean isEventEnabled(AuditEventType eventType) {
        synchronized (LOCK) {
            return config.isEnabled(eventType);
        }
    }

    private static JsonObject basePlayerEvent(AuditEventType eventType, ServerPlayer player) {
        JsonObject event = baseEvent(eventType);
        event.addProperty("player_uuid", player.getUUID().toString());
        event.addProperty("player_name", player.getGameProfile().getName());
        event.addProperty("dimension", player.level().dimension().location().toString());
        return event;
    }

    private static JsonObject baseAdminEvent(AuditEventType eventType) {
        return baseEvent(eventType);
    }

    private static JsonObject baseEvent(AuditEventType eventType) {
        JsonObject event = new JsonObject();
        event.addProperty("schema_version", SCHEMA_VERSION);
        event.addProperty("type", eventType.eventName());
        event.addProperty("timestamp", Instant.now().toString());
        return event;
    }

    private static JsonArray singleItemArray(ItemStack template, long count, BigInteger unitPrice, BigInteger lineTotal) {
        JsonArray lines = new JsonArray();
        lines.add(itemLineToJson(template, count, unitPrice, lineTotal));
        return lines;
    }

    private static JsonArray itemLinesFromStacks(List<ItemStack> stacks) {
        JsonArray lines = new JsonArray();
        for (ItemStack stack : stacks) {
            lines.add(itemLineToJson(stack, stack.getCount(), null, null));
        }
        return lines;
    }

    private static JsonObject itemLineToJson(ItemStack stack, long count, BigInteger unitPrice, BigInteger lineTotal) {
        JsonObject object = new JsonObject();
        object.addProperty("item", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
        object.addProperty("count", count);
        object.addProperty("name", stack.getHoverName().getString());
        DataComponentMap components = stack.getComponents();
        if (!components.isEmpty()) {
            object.addProperty("components_hash", Integer.toHexString(components.hashCode()));
        }
        if (unitPrice != null) {
            object.addProperty("unit_price", unitPrice.toString());
        }
        if (lineTotal != null) {
            object.addProperty("line_total", lineTotal.toString());
        }
        return object;
    }

    private static void addStockState(JsonObject event, Integer stockBefore, Integer stockAfter) {
        if (stockBefore != null && stockBefore >= 0) {
            event.addProperty("stock_before", stockBefore);
        }
        if (stockAfter != null && stockAfter >= 0) {
            event.addProperty("stock_after", stockAfter);
        }
    }

    private static void append(JsonObject event) {
        synchronized (LOCK) {
            try {
                Writer writer = getOrCreateWriter();
                GSON.toJson(event, writer);
                writer.write(LINE_SEPARATOR);
                if (config.flushEachEntry()) {
                    writer.flush();
                }
            } catch (IOException exception) {
                closeWriterQuietly();
                CobbleDollarsCommandShopsMod.LOGGER.error("Failed to append audit log entry", exception);
            }
        }
    }

    private static Writer getOrCreateWriter() throws IOException {
        if (logWriter != null) {
            return logWriter;
        }
        ensureLogDirectory();
        logWriter = Files.newBufferedWriter(LOG_FILE, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        return logWriter;
    }

    private static void ensureLogDirectory() throws IOException {
        if (logDirectoryReady) {
            return;
        }
        Files.createDirectories(LOG_FILE.getParent());
        logDirectoryReady = true;
    }

    private static void closeWriterQuietly() {
        if (logWriter == null) {
            return;
        }
        try {
            logWriter.flush();
            logWriter.close();
        } catch (IOException ignored) {
        } finally {
            logWriter = null;
        }
    }

    public record SoldItemLine(ItemStack stack, BigInteger unitPrice, BigInteger lineTotal) {
        public SoldItemLine {
            stack = stack.copy();
        }
    }
}
