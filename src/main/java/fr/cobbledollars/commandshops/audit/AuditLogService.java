package fr.cobbledollars.commandshops.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class AuditLogService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final int SCHEMA_VERSION = 1;
    private static final String ACTIVE_LOG_FILE_NAME = "audit.jsonl";
    private static final String ARCHIVE_LOG_PREFIX = "audit-";
    private static final String PLAIN_LOG_SUFFIX = ".jsonl";
    private static final String COMPRESSED_LOG_SUFFIX = ".jsonl.zip";
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault());
    private static final Path LOG_FILE = FMLPaths.GAMEDIR.get()
            .resolve("logs")
            .resolve(CobbleDollarsCommandShopsMod.MODID)
            .resolve(ACTIVE_LOG_FILE_NAME);
    private static final Object LOCK = new Object();
    private static final String LINE_SEPARATOR = System.lineSeparator();

    private static Writer logWriter;
    private static FileChannel logChannel;
    private static final int MAX_EVENT_BYTES = 60 * 1024;
    private static final int MAX_VALUE_BYTES = 8 * 1024;
    private static boolean logDirectoryReady;
    private static AuditConfig config = AuditConfig.defaults();

    private AuditLogService() {
    }

    public static void initialize() throws IOException {
        synchronized (LOCK) {
            closeWriterQuietly();
            logDirectoryReady = false;
            AuditFiles.ensureDefaultConfigExists();
            rotateActiveLogIfPresent();
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

    /** Flush only the active writer under its lock; parsing happens outside this lock. */
    public static ReadStatus prepareRead() throws IOException {
        synchronized (LOCK) {
            if (logWriter != null) logWriter.flush();
            return new ReadStatus(config.enabled(), config.enabledEvents().stream().map(AuditEventType::eventName).toList());
        }
    }

    public record ReadStatus(boolean enabled, List<String> enabledEvents) {
        public ReadStatus { enabledEvents = List.copyOf(enabledEvents); }
    }

    public static List<Path> listReadableLogFiles() throws IOException {
        Path logDirectory = LOG_FILE.getParent();
        if (!Files.exists(logDirectory)) {
            return List.of();
        }

        ArrayList<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(logDirectory)) {
            stream.filter(Files::isRegularFile)
                    .filter(AuditLogService::isArchivedAuditLogFile)
                    .sorted((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()))
                    .forEach(files::add);
        }
        if (Files.exists(LOG_FILE)) {
            files.add(LOG_FILE);
        }
        return List.copyOf(files);
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
        }
        if (bundleCount != null && bundleCount > 0) {
            event.addProperty("bundle_count", bundleCount);
        }
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
        for (AggregatedSoldItemLine itemLine : aggregateSoldItemLines(itemLines)) {
            lines.add(itemLineToJson(itemLine.stack(), itemLine.count(), itemLine.unitPrice(), itemLine.lineTotal()));
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

    /** A transfer is one movement with two participants, never two independent sources/sinks. */
    public static void logPlayerTransfer(UUID operationId, AuditBalanceChange sender, AuditBalanceChange recipient) {
        if (!isEventEnabled(AuditEventType.PLAYER_TRANSFER)
                || sender.player().uuid().equals(recipient.player().uuid())
                || sender.delta().signum() >= 0 || !sender.delta().negate().equals(recipient.delta())) return;
        JsonObject event = baseBalanceEvent(AuditEventType.PLAYER_TRANSFER, operationId, sender);
        event.addProperty("currency_flow", "transfer");
        event.addProperty("recipient_uuid", recipient.player().uuid().toString());
        event.addProperty("recipient_name", recipient.player().name());
        event.addProperty("recipient_balance_before", recipient.before().toString());
        event.addProperty("recipient_balance_after", recipient.after().toString());
        append(event);
    }

    public static void logBalanceAdjusted(UUID operationId, String actorName, String actorUuid,
                                          BalanceAdjustmentAction action, BigInteger requestedAmount,
                                          AuditBalanceChange change) {
        if (!isEventEnabled(AuditEventType.BALANCE_ADJUSTED) || change.delta().signum() == 0) return;
        JsonObject event = baseBalanceEvent(AuditEventType.BALANCE_ADJUSTED, operationId, change);
        event.addProperty("actor_name", actorName);
        event.addProperty("actor_uuid", actorUuid);
        event.addProperty("action", action.eventName());
        event.addProperty("requested_amount", requestedAmount.toString());
        append(event);
    }

    public static void logBattleReward(UUID operationId, AuditBalanceChange change) {
        if (!isEventEnabled(AuditEventType.BATTLE_REWARD) || change.delta().signum() <= 0) return;
        append(baseBalanceEvent(AuditEventType.BATTLE_REWARD, operationId, change));
    }

    public static void logNativeBuySuccess(UUID operationId, AuditBalanceChange change,
                                           List<ItemStack> items, String merchantUuid) {
        if (!isEventEnabled(AuditEventType.NATIVE_BUY_SUCCESS) || change.delta().signum() >= 0) return;
        appendNativeMovement(AuditEventType.NATIVE_BUY_SUCCESS, operationId, change, items, merchantUuid);
    }

    public static void logNativeSellSuccess(UUID operationId, AuditBalanceChange change,
                                            List<ItemStack> items, String merchantUuid) {
        if (!isEventEnabled(AuditEventType.NATIVE_SELL_SUCCESS) || change.delta().signum() <= 0) return;
        appendNativeMovement(AuditEventType.NATIVE_SELL_SUCCESS, operationId, change, items, merchantUuid);
    }

    private static void appendNativeMovement(AuditEventType type, UUID operationId, AuditBalanceChange change,
                                              List<ItemStack> items, String merchantUuid) {
        JsonObject event = baseBalanceEvent(type, operationId, change);
        event.add("item_lines", itemLinesFromStacks(items));
        if (merchantUuid != null) event.addProperty("merchant_uuid", merchantUuid);
        append(event);
    }

    private static JsonObject baseBalanceEvent(AuditEventType type, UUID operationId, AuditBalanceChange change) {
        JsonObject event = baseEvent(type);
        event.addProperty("operation_id", operationId.toString());
        event.addProperty("player_uuid", change.player().uuid().toString());
        event.addProperty("player_name", change.player().name());
        event.addProperty("balance_before", change.before().toString());
        event.addProperty("balance_after", change.after().toString());
        event.addProperty("currency_delta", change.delta().toString());
        event.addProperty("currency_amount", change.delta().abs().toString());
        event.addProperty("currency_flow", change.delta().signum() > 0 ? "source" : "sink");
        return event;
    }

    public static void logVisibilityChanged(String shopId, boolean enabled, String reasonMessage, String actorName, String actorUuid) {
        if (!isEventEnabled(AuditEventType.VISIBILITY_CHANGED)) {
            return;
        }

        JsonObject event = baseAdminEvent(AuditEventType.VISIBILITY_CHANGED);
        event.addProperty("shop_id", shopId);
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

    /** Only explicit administrator actions call this method; purchases and timed restocks do not. */
    public static void logManualStockChanged(ServerPlayer actor, UUID operationId, String action, String shopId,
                                            UUID playerUuid, String playerName, ResolvedShopOffer offer,
                                            int stockBefore, int stockAfter) {
        if (stockBefore == stockAfter || !isEventEnabled(AuditEventType.STOCK_CHANGED)) return;
        JsonObject event = baseAdminEvent(AuditEventType.STOCK_CHANGED);
        addActor(event, actor.getGameProfile().getName(), actor.getUUID().toString());
        event.addProperty("operation_id", operationId.toString());
        event.addProperty("action", action);
        event.addProperty("shop_id", shopId);
        event.addProperty("player_uuid", playerUuid.toString());
        event.addProperty("player_name", playerName);
        event.addProperty("offer_id", offer.sourceId());
        event.addProperty("stock_key", offer.stockKey());
        event.addProperty("item_id", BuiltInRegistries.ITEM.getKey(offer.itemStack().getItem()).toString());
        event.addProperty("stock_before", stockBefore);
        event.addProperty("stock_after", stockAfter);
        append(event);
    }

    public static boolean configurationChanged(Map<String, JsonElement> before, Map<String, JsonElement> after) {
        return !AuditConfigurationDiff.between(before, after).isEmpty();
    }

    /**
     * Persist proposed changes before a configuration edit can deactivate recording. A separate terminal
     * record states whether activation succeeded, so an interrupted or rolled-back edit is never called applied.
     * The old OR new policy permits the operation that re-enables recording to be recorded as well.
     */
    public static ConfigurationChange prepareConfigurationChange(Map<String, JsonElement> before,
            Map<String, JsonElement> after, String source, String actorName, String actorUuid) throws IOException {
        List<AuditConfigurationDiff.Change> changes = AuditConfigurationDiff.between(before, after);
        AuditConfig proposed = after.get("audit.json") != null && after.get("audit.json").isJsonObject()
                ? AuditFiles.parseConfig(after.get("audit.json").getAsJsonObject(), "proposed audit configuration") : config;
        synchronized (LOCK) {
            boolean enabled = config.isEnabled(AuditEventType.CONFIG_CHANGED) || proposed.isEnabled(AuditEventType.CONFIG_CHANGED);
            ConfigurationChange transaction = new ConfigurationChange(UUID.randomUUID(), source, actorName, actorUuid, changes, enabled);
            if (!enabled || changes.isEmpty()) return transaction;
            if (changes.size() > 20_000) {
                throw new IOException("This edit changes more than 20,000 configuration values. Save smaller batches.");
            }
            try {
                for (AuditConfigurationDiff.Change change : changes) {
                    JsonObject event = transaction.base("prepared");
                    event.addProperty("file", change.file());
                    addBoundedText(event, "path", change.path(), 4096);
                    if (change.file().startsWith("shops/")) event.addProperty("shop_id", change.file().split("/")[1]);
                    event.addProperty("change", change.kind());
                    addConfigurationValue(event, "before", change.before());
                    addConfigurationValue(event, "after", change.after());
                    appendChecked(event, false);
                }
                forceLog();
                return transaction;
            } catch (IOException exception) {
                transaction.complete("failed");
                closeWriterQuietly();
                throw new IOException("Configuration changes could not be durably recorded; configuration was not changed.", exception);
            }
        }
    }

    public static final class ConfigurationChange {
        private final UUID operationId;
        private final String source;
        private final String actorName;
        private final String actorUuid;
        private final List<AuditConfigurationDiff.Change> changes;
        private final boolean enabled;
        private boolean completed;

        private ConfigurationChange(UUID operationId, String source, String actorName, String actorUuid,
                                    List<AuditConfigurationDiff.Change> changes, boolean enabled) {
            this.operationId = operationId;
            this.source = source;
            this.actorName = actorName;
            this.actorUuid = actorUuid;
            this.changes = changes;
            this.enabled = enabled;
        }

        private JsonObject base(String phase) {
            JsonObject event = baseAdminEvent(AuditEventType.CONFIG_CHANGED);
            event.addProperty("operation_id", operationId.toString());
            event.addProperty("source", source);
            event.addProperty("phase", phase);
            addActor(event, actorName, actorUuid);
            return event;
        }

        /** Terminal recording is allowed by the captured policy, even after that policy was disabled. */
        public void complete(String phase) {
            synchronized (LOCK) {
                if (completed || !enabled || changes.isEmpty()) return;
                completed = true;
                JsonObject event = base(phase);
                event.addProperty("change_count", changes.size());
                TreeSet<String> fileNames = new TreeSet<>();
                TreeSet<String> shopIds = new TreeSet<>();
                for (AuditConfigurationDiff.Change change : changes) {
                    fileNames.add(change.file());
                    if (change.file().startsWith("shops/")) shopIds.add(change.file().split("/")[1]);
                }
                JsonArray files = new JsonArray();
                fileNames.forEach(files::add);
                addBoundedArray(event, "files", files);
                JsonArray shops = new JsonArray();
                shopIds.forEach(shops::add);
                addBoundedArray(event, "shop_ids", shops);
                try {
                    appendChecked(event, true);
                } catch (IOException exception) {
                    closeWriterQuietly();
                    CobbleDollarsCommandShopsMod.LOGGER.error("Failed to record configuration operation {} outcome {}", operationId, phase, exception);
                }
            }
        }
    }

    private static void addActor(JsonObject event, String actorName, String actorUuid) {
        if (actorName != null) event.addProperty("actor_name", actorName);
        if (actorUuid != null) event.addProperty("actor_uuid", actorUuid);
    }

    private static void addBoundedArray(JsonObject event, String key, JsonArray values) {
        String encoded = values.toString();
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_VALUE_BYTES) {
            event.add(key, values);
            return;
        }
        JsonArray preview = new JsonArray();
        int previewBytes = 2;
        for (JsonElement value : values) {
            int nextBytes = value.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (previewBytes + nextBytes > MAX_VALUE_BYTES) break;
            preview.add(value);
            previewBytes += nextBytes;
        }
        event.add(key, preview);
        event.addProperty(key + "_count", values.size());
        event.addProperty(key + "_truncated", true);
        event.addProperty(key + "_sha256", sha256(bytes));
    }

    private static void addConfigurationValue(JsonObject event, String key, JsonElement value) {
        event.addProperty(key + "_present", value != null);
        if (value == null) return;
        String encoded = value.toString();
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_VALUE_BYTES) {
            event.add(key, value.deepCopy());
        } else {
            event.addProperty(key + "_truncated", true);
            event.addProperty(key + "_bytes", bytes.length);
            event.addProperty(key + "_sha256", sha256(bytes));
            event.addProperty(key, preview(encoded, 2048));
        }
    }

    private static void addBoundedText(JsonObject event, String key, String value, int limit) {
        if (value.length() <= limit) {
            event.addProperty(key, value);
        } else {
            event.addProperty(key, preview(value, limit));
            event.addProperty(key + "_truncated", true);
            event.addProperty(key + "_sha256", sha256(value.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static String preview(String value, int characters) {
        int end = Math.min(characters, value.length());
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean isEventEnabled(AuditEventType eventType) {
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

    private static List<AggregatedSoldItemLine> aggregateSoldItemLines(List<SoldItemLine> itemLines) {
        LinkedHashMap<SoldItemAggregationKey, AggregatedSoldItemAccumulator> aggregated = new LinkedHashMap<>();
        for (SoldItemLine itemLine : itemLines) {
            ItemStack stack = itemLine.stack();
            String itemId = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            String componentsHash = null;
            DataComponentMap components = stack.getComponents();
            if (!components.isEmpty()) {
                componentsHash = Integer.toHexString(components.hashCode());
            }

            SoldItemAggregationKey key = new SoldItemAggregationKey(itemId, componentsHash, itemLine.unitPrice());
            AggregatedSoldItemAccumulator accumulator = aggregated.computeIfAbsent(
                    key,
                    ignored -> new AggregatedSoldItemAccumulator(stack.copy(), 0L, itemLine.unitPrice(), BigInteger.ZERO)
            );
            accumulator.count += stack.getCount();
            accumulator.lineTotal = accumulator.lineTotal.add(itemLine.lineTotal());
        }

        return aggregated.values().stream()
                .map(accumulator -> new AggregatedSoldItemLine(accumulator.stack, accumulator.count, accumulator.unitPrice, accumulator.lineTotal))
                .toList();
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
                appendChecked(event, false);
            } catch (IOException exception) {
                closeWriterQuietly();
                CobbleDollarsCommandShopsMod.LOGGER.error("Failed to append audit log entry", exception);
            }
        }
    }

    private static void appendChecked(JsonObject event, boolean force) throws IOException {
        String encoded = GSON.toJson(event);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) {
            throw new IOException("Audit event exceeds the bounded record limit.");
        }
        Writer writer = getOrCreateWriter();
        writer.write(encoded);
        writer.write(LINE_SEPARATOR);
        if (force) forceLog();
        else if (config.flushEachEntry()) writer.flush();
    }

    private static void forceLog() throws IOException {
        if (logWriter != null) logWriter.flush();
        if (logChannel != null) logChannel.force(true);
    }

    private static Writer getOrCreateWriter() throws IOException {
        if (logWriter != null) {
            return logWriter;
        }
        ensureLogDirectory();
        logChannel = FileChannel.open(LOG_FILE, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        logWriter = new BufferedWriter(Channels.newWriter(logChannel, StandardCharsets.UTF_8));
        return logWriter;
    }

    private static void ensureLogDirectory() throws IOException {
        if (logDirectoryReady) {
            return;
        }
        Files.createDirectories(LOG_FILE.getParent());
        logDirectoryReady = true;
    }

    private static void rotateActiveLogIfPresent() throws IOException {
        if (!Files.exists(LOG_FILE) || Files.size(LOG_FILE) <= 0L) {
            return;
        }

        ensureLogDirectory();
        Path archivedPlainFile = resolveArchivedPlainLogFile();
        Files.move(LOG_FILE, archivedPlainFile);

        try {
            compressArchivedLog(archivedPlainFile);
        } catch (IOException exception) {
            CobbleDollarsCommandShopsMod.LOGGER.warn("Failed to compress rotated audit log {}", archivedPlainFile, exception);
        }
    }

    private static Path resolveArchivedPlainLogFile() {
        String timestamp = ARCHIVE_TIMESTAMP_FORMATTER.format(Instant.now());
        Path logDirectory = LOG_FILE.getParent();
        Path candidate = logDirectory.resolve(ARCHIVE_LOG_PREFIX + timestamp + PLAIN_LOG_SUFFIX);
        int suffix = 1;
        while (Files.exists(candidate) || Files.exists(candidate.resolveSibling(candidate.getFileName().toString() + ".zip"))) {
            candidate = logDirectory.resolve(ARCHIVE_LOG_PREFIX + timestamp + "-" + suffix + PLAIN_LOG_SUFFIX);
            suffix++;
        }
        return candidate;
    }

    private static void compressArchivedLog(Path sourceFile) throws IOException {
        Path targetFile = sourceFile.resolveSibling(sourceFile.getFileName().toString() + ".zip");
        Path tempFile = targetFile.resolveSibling(targetFile.getFileName().toString() + ".tmp");

        try {
            try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(sourceFile));
                 OutputStream fileOutputStream = new BufferedOutputStream(Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
                 ZipOutputStream outputStream = new ZipOutputStream(fileOutputStream)) {
                outputStream.putNextEntry(new ZipEntry(sourceFile.getFileName().toString()));
                inputStream.transferTo(outputStream);
                outputStream.closeEntry();
            }

            Files.move(tempFile, targetFile);
            Files.deleteIfExists(sourceFile);
        } catch (IOException exception) {
            Files.deleteIfExists(tempFile);
            throw exception;
        }
    }

    private static boolean isArchivedAuditLogFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith(ARCHIVE_LOG_PREFIX)
                && (fileName.endsWith(PLAIN_LOG_SUFFIX) || fileName.endsWith(COMPRESSED_LOG_SUFFIX));
    }

    private static void closeWriterQuietly() {
        if (logWriter == null) {
            return;
        }
        Writer writer = logWriter;
        FileChannel channel = logChannel;
        logWriter = null;
        logChannel = null;
        try {
            writer.flush();
        } catch (IOException ignored) {
        }
        try {
            writer.close();
        } catch (IOException ignored) {
        } finally {
            if (channel != null) {
                try { channel.close(); } catch (IOException ignored) { }
            }
        }
    }

    public record SoldItemLine(ItemStack stack, BigInteger unitPrice, BigInteger lineTotal) {
        public SoldItemLine {
            stack = stack.copy();
        }
    }

    private record AggregatedSoldItemLine(ItemStack stack, long count, BigInteger unitPrice, BigInteger lineTotal) {
    }

    private record SoldItemAggregationKey(String itemId, String componentsHash, BigInteger unitPrice) {
    }

    private static final class AggregatedSoldItemAccumulator {
        private final ItemStack stack;
        private long count;
        private final BigInteger unitPrice;
        private BigInteger lineTotal;

        private AggregatedSoldItemAccumulator(ItemStack stack, long count, BigInteger unitPrice, BigInteger lineTotal) {
            this.stack = stack;
            this.count = count;
            this.unitPrice = unitPrice;
            this.lineTotal = lineTotal;
        }
    }
}
