package fr.cobbledollars.commandshops.audit;

import com.google.gson.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Filesystem-only reader, called by the audit worker, never by the server tick thread. */
public final class AuditQueryService {
    private static final Gson GSON = new Gson();
    private static final long CACHE_MILLIS = 10_000;
    private final Map<String, Snapshot> snapshots = lru(4);
    private final Map<String, CachedPage> journalPages = lru(8);
    private record Snapshot(AuditStatsSnapshot stats, Instant loadedAt, int invalidLines, int unreadableFiles) { }
    private record CachedPage(JsonObject result, long expiresAt) { }
    private record Entry(Instant timestamp, String source, long line, JsonObject event) {
        String id() { return timestamp + "\t" + source + "\t" + line; }
    }
    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(Entry::timestamp)
            .thenComparing(Entry::source).thenComparingLong(Entry::line);

    private static <K,V> Map<K,V> lru(int limit) {
        return new LinkedHashMap<>(limit, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K,V> entry) { return size() > limit; }
        };
    }

    public JsonObject query(AuditQuery query, boolean refresh) throws IOException {
        AuditLogService.ReadStatus status = AuditLogService.prepareRead();
        Instant now = Instant.now();
        if (refresh) { snapshots.clear(); journalPages.clear(); }
        if ("journal".equals(query.view())) {
            CachedPage cached = journalPages.get(query.json());
            if (cached != null && cached.expiresAt() > now.toEpochMilli()) return withStatus(cached.result().deepCopy(), status);
        }
        Snapshot snapshot = snapshots.get(query.filterKey());
        boolean fresh = snapshot != null && now.toEpochMilli() - snapshot.loadedAt().toEpochMilli() < CACHE_MILLIS;
        PriorityQueue<Entry> newest = new PriorityQueue<>(ENTRY_ORDER);
        long journalTotal = 0;
        if (!fresh || "journal".equals(query.view())) {
            Instant anchor = fresh ? snapshot.loadedAt() : now;
            AuditStatsService.Accumulator accumulator = new AuditStatsService.Accumulator(query.timeWindow());
            int invalid = 0, unreadable = 0, readable = 0;
            Entry before = cursor(query.cursor());
            List<Path> files = AuditLogService.listReadableLogFiles();
            for (Path file : files) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Audit request cancelled.");
                try (BufferedReader reader = open(file)) {
                    readable++;
                    String line; long lineNumber = 0;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Audit request cancelled.");
                        if (line.isBlank()) continue;
                        try {
                            if (line.length() > 65_536) throw new IllegalArgumentException("Oversized audit record.");
                            JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                            Instant timestamp = Instant.parse(string(event, "timestamp"));
                            AuditEventType type = AuditEventType.fromEventName(string(event, "type"));
                            if (type == null) continue;
                            AuditStatsService.validateEvent(type, event);
                            if (timestamp.isAfter(anchor) || !query.timeWindow().includes(timestamp, anchor) || !matches(query, event)) continue;
                            accumulator.accept(type, event);
                            if (accumulator.entityCount() > 100_000)
                                throw new IOException("Too many distinct audit entries. Select a shorter period or add a filter.");
                            if ("journal".equals(query.view()) && contains(event.toString(), query.search())) {
                                journalTotal++;
                                Entry entry = new Entry(timestamp, file.getFileName().toString(), lineNumber, event);
                                if (before == null || ENTRY_ORDER.compare(entry, before) < 0) {
                                    newest.add(entry);
                                    if (newest.size() > AuditQuery.PAGE_SIZE + 1) newest.poll();
                                }
                            }
                        } catch (RuntimeException badRecord) { invalid++; }
                    }
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (IOException error) {
                    // A resource limit must fail explicitly rather than returning incomplete totals.
                    if (error.getMessage() != null && error.getMessage().startsWith("Too many distinct")) throw error;
                    unreadable++;
                }
            }
            if (!files.isEmpty() && readable == 0) throw new IOException("None of the audit files could be read.");
            snapshot = new Snapshot(accumulator.freeze(), anchor, invalid, unreadable);
            if (accumulator.entityCount() <= 20_000) snapshots.put(query.filterKey(), snapshot);
        }
        JsonObject result = new JsonObject();
        result.addProperty("view", query.view());
        result.addProperty("window", query.window());
        result.addProperty("loadedAt", snapshot.loadedAt().toEpochMilli());
        result.addProperty("invalidLines", snapshot.invalidLines());
        result.addProperty("unreadableFiles", snapshot.unreadableFiles());
        result.addProperty("pageSize", AuditQuery.PAGE_SIZE);
        result.add("summary", summary(snapshot.stats()));
        JsonArray page = new JsonArray();
        if ("journal".equals(query.view())) {
            List<Entry> entries = newest.stream().sorted(ENTRY_ORDER.reversed()).toList();
            int count = Math.min(AuditQuery.PAGE_SIZE, entries.size());
            for (Entry entry : entries.subList(0, count)) {
                JsonObject row = entry.event().deepCopy();
                row.addProperty("id", entry.id());
                page.add(row);
            }
            boolean next = entries.size() > AuditQuery.PAGE_SIZE;
            result.addProperty("total", journalTotal);
            result.addProperty("page", query.page());
            result.addProperty("hasNext", next);
            result.addProperty("nextCursor", next ? entries.get(count - 1).id() : "");
        } else {
            List<JsonObject> rows = rows(query.view(), snapshot.stats());
            rows.removeIf(row -> !contains(string(row,"name") + " " + string(row,"id"), query.search()));
            Comparator<JsonObject> order = switch (query.sort()) {
                case "name" -> Comparator.comparing(row -> string(row,"name"), String.CASE_INSENSITIVE_ORDER);
                case "count" -> Comparator.<JsonObject, BigInteger>comparing(row -> number(row,"operationCount")).reversed();
                default -> Comparator.<JsonObject, BigInteger>comparing(row -> number(row,"volume")).reversed();
            };
            rows.sort(order.thenComparing(row -> string(row,"id")));
            int lastPage = Math.max(0, (rows.size() - 1) / AuditQuery.PAGE_SIZE);
            int currentPage = Math.min(query.page(), lastPage);
            int start = currentPage * AuditQuery.PAGE_SIZE;
            rows.subList(start, Math.min(start + AuditQuery.PAGE_SIZE, rows.size())).forEach(page::add);
            result.addProperty("total", rows.size());
            result.addProperty("page", currentPage);
            result.addProperty("hasNext", currentPage < lastPage);
            result.addProperty("nextCursor", "");
        }
        result.add("rows", page);
        withStatus(result, status);
        if ("journal".equals(query.view())) journalPages.put(query.json(), new CachedPage(result.deepCopy(), now.toEpochMilli() + CACHE_MILLIS));
        return result;
    }

    private static JsonObject withStatus(JsonObject result, AuditLogService.ReadStatus status) {
        result.addProperty("auditEnabled", status.enabled());
        JsonArray enabled = new JsonArray(); status.enabledEvents().forEach(enabled::add);
        result.add("enabledEvents", enabled);
        return result;
    }

    private static BufferedReader open(Path file) throws IOException {
        if (!file.getFileName().toString().endsWith(".zip")) return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8);
        try {
            if (zip.getNextEntry() == null) throw new IOException("Empty audit archive.");
            return new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
        } catch (IOException error) { zip.close(); throw error; }
    }

    private static Entry cursor(String text) {
        if (text.isBlank()) return null;
        String[] parts = text.split("\t", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Invalid audit cursor.");
        return new Entry(Instant.parse(parts[0]), parts[1], Long.parseLong(parts[2]), null);
    }

    private static boolean matches(AuditQuery q, JsonObject event) {
        if (!q.event().equals("all") && !q.event().equals(string(event,"type"))) return false;
        if (!q.shop().isBlank() && !q.shop().equalsIgnoreCase(string(event,"shop_id"))
                && !arrayContains(event,"shop_ids",q.shop())) return false;
        if (!q.offer().isBlank() && !q.offer().equalsIgnoreCase(string(event,"offer_id"))) return false;
        if (!q.player().isBlank() && !List.of("player_uuid","player_name","recipient_uuid","recipient_name","actor_uuid","actor_name").stream()
                .anyMatch(key -> q.player().equalsIgnoreCase(string(event,key)))) return false;
        if (!q.item().isBlank()) {
            boolean found = q.item().equalsIgnoreCase(string(event,"item_id"));
            for (String field : List.of("item_lines", "bonus_lines")) {
                if (event.has(field)) for (JsonElement line : event.getAsJsonArray(field)) {
                    if (q.item().equalsIgnoreCase(string(line.getAsJsonObject(),"item"))) found = true;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean arrayContains(JsonObject event, String key, String expected) {
        JsonElement values = event.get(key);
        if (values == null || !values.isJsonArray()) return false;
        for (JsonElement value : values.getAsJsonArray()) {
            if (value.isJsonPrimitive() && expected.equalsIgnoreCase(value.getAsString())) return true;
        }
        return false;
    }

    private static List<JsonObject> rows(String view, AuditStatsSnapshot stats) {
        List<JsonObject> rows = new ArrayList<>();
        switch (view) {
            case "shops" -> stats.shopsById().values().forEach(value -> {
                JsonObject row = record(value, value.shopId(), value.shopId(), value.totalVolume());
                row.addProperty("operationCount", value.buySuccessCount() + value.sellSuccessCount()); rows.add(row);
            });
            case "players" -> stats.playersByUuid().values().forEach(value -> {
                JsonObject row = record(value, value.playerUuid(), value.playerName(), value.totalVolume());
                for (String key : List.of("transferSent", "transferReceived", "adminAdded", "adminRemoved", "battleRewards", "nativeSpent", "nativeEarned"))
                    row.addProperty(key, row.get(key).getAsString());
                row.addProperty("operationCount", value.operationCount());
                row.addProperty("moneyIn", value.moneyIn().toString());
                row.addProperty("moneyOut", value.moneyOut().toString());
                rows.add(row);
            });
            case "items" -> stats.itemsById().values().forEach(value -> {
                JsonObject row = record(value, value.itemId(), value.itemName(), value.totalVolume());
                row.addProperty("operationCount", value.boughtCount() + value.soldCount()); rows.add(row);
            });
            case "offers" -> stats.offersByKey().values().forEach(value -> {
                JsonObject row = record(value, value.shopId() + "/" + value.offerId(), value.offerId(), value.spent());
                row.addProperty("operationCount", value.buySuccessCount()); rows.add(row);
            });
            default -> { }
        }
        return rows;
    }

    private static JsonObject record(Object value, String id, String name, BigInteger volume) {
        JsonObject row = GSON.toJsonTree(value).getAsJsonObject();
        row.addProperty("id", id); row.addProperty("name", name); row.addProperty("volume", volume.toString());
        return row;
    }

    private static JsonObject summary(AuditStatsSnapshot stats) {
        JsonObject result = new JsonObject();
        result.addProperty("buySuccessCount", stats.buySuccessCount()); result.addProperty("buyFailureCount", stats.buyFailureCount());
        result.addProperty("sellSuccessCount", stats.sellSuccessCount()); result.addProperty("sellFailureCount", stats.sellFailureCount());
        result.addProperty("visibilityChangeCount", stats.visibilityChangeCount());
        result.addProperty("stockChangeCount", stats.stockChangeCount());
        result.addProperty("configChangeCount", stats.configChangeCount());
        result.addProperty("spent", stats.totalSpent().toString()); result.addProperty("earned", stats.totalEarned().toString());
        result.addProperty("netFlow", stats.netFlow().toString());
        result.addProperty("transferCount", stats.transferCount());
        result.addProperty("balanceAdjustmentCount", stats.balanceAdjustmentCount());
        result.addProperty("battleRewardCount", stats.battleRewardCount());
        result.addProperty("nativeBuySuccessCount", stats.nativeBuySuccessCount());
        result.addProperty("nativeSellSuccessCount", stats.nativeSellSuccessCount());
        result.addProperty("transferred", stats.transferred().toString());
        result.addProperty("adminAdded", stats.adminAdded().toString());
        result.addProperty("adminRemoved", stats.adminRemoved().toString());
        result.addProperty("battleRewards", stats.battleRewards().toString());
        result.addProperty("nativeSpent", stats.nativeSpent().toString());
        result.addProperty("nativeEarned", stats.nativeEarned().toString());
        return result;
    }

    private static String string(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
    private static BigInteger number(JsonObject object, String key) { String value = string(object,key); return value.isEmpty() ? BigInteger.ZERO : new BigInteger(value); }
    private static boolean contains(String text, String query) { return text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)); }
}
