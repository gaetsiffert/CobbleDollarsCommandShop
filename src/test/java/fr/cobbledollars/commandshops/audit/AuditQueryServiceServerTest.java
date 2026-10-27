package fr.cobbledollars.commandshops.audit;

import com.google.gson.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(EphemeralTestServerProvider.class)
class AuditQueryServiceServerTest {
    @AfterEach void cleanup() throws Exception {
        AuditLogService.shutdown();
        for (Path file : AuditLogService.listReadableLogFiles()) Files.deleteIfExists(file);
        Files.deleteIfExists(AuditFiles.getConfigFile());
    }

    @Test void readsArchivesFiltersEntitiesAndPreservesExactMoney(MinecraftServer server) throws Exception {
        String huge = "123456789012345678901234567890";
        archive("audit-old.jsonl.zip", List.of(buy(1,"deleted_shop","offline-uuid","Offline",huge)));
        write(List.of(buy(2,"other_shop","other-uuid","Other","9")));
        AuditQueryService reader = new AuditQueryService();
        JsonObject summary = reader.query(query("summary","","","","",0,""),false).getAsJsonObject("summary");
        assertEquals(2,summary.get("buySuccessCount").getAsInt());
        assertEquals(new BigInteger(huge).add(BigInteger.valueOf(9)),summary.get("spent").getAsBigInteger());
        JsonObject filtered = reader.query(query("players","deleted_shop","offline-uuid","minecraft:apple","",0,""),false);
        assertEquals(1,filtered.get("total").getAsInt());
        JsonObject row = filtered.getAsJsonArray("rows").get(0).getAsJsonObject();
        assertEquals("Offline",row.get("name").getAsString());
        assertEquals(huge,row.get("spent").getAsString());
        assertEquals(1,reader.query(query("items","deleted_shop","","minecraft:apple","",0,""),false).get("total").getAsInt());
        assertEquals(1,reader.query(query("offers","deleted_shop","","","",0,""),false).get("total").getAsInt());
    }

    @Test void journalCursorVisitsEveryRecordExactlyOnceAcrossFiles(MinecraftServer server) throws Exception {
        List<String> older = new ArrayList<>(), newer = new ArrayList<>();
        for (int i=0;i<67;i++) (i%2==0 ? older : newer).add(buy(i,"shop","id","Name",Integer.toString(i+1)));
        archive("audit-page.jsonl.zip",older); write(newer);
        AuditQueryService reader = new AuditQueryService();
        Set<String> ids = new HashSet<>(); Instant previous = Instant.MAX;
        String cursor = ""; int pages=0;
        while (true) {
            JsonObject result=reader.query(query("journal","","","","",pages,cursor),false);
            assertEquals(67,result.get("total").getAsInt());
            assertTrue(result.getAsJsonArray("rows").size()<=25);
            for (JsonElement element:result.getAsJsonArray("rows")) {
                JsonObject row=element.getAsJsonObject();
                assertTrue(ids.add(row.get("id").getAsString()),"Duplicate page record");
                Instant timestamp=Instant.parse(row.get("timestamp").getAsString());
                assertFalse(timestamp.isAfter(previous)); previous=timestamp;
            }
            pages++;
            if (!result.get("hasNext").getAsBoolean()) break;
            cursor=result.get("nextCursor").getAsString();
        }
        assertEquals(67,ids.size()); assertEquals(3,pages);
    }

    @Test void cachedStatisticsRefreshExplicitlyAndReportCorruption(MinecraftServer server) throws Exception {
        write(List.of(buy(1,"shop","id","Name","10"),"not-json",
                "{\"timestamp\":\"2020-01-01T00:00:00Z\",\"type\":\"buy_success\",\"shop_id\":\"broken\"}"));
        Files.writeString(AuditLogService.getLogFile().resolveSibling("audit-bad.jsonl.zip"),"not a zip");
        AuditQueryService reader=new AuditQueryService(); AuditQuery query=query("summary","","","","",0,"");
        JsonObject first=reader.query(query,false);
        assertEquals(1,first.getAsJsonObject("summary").get("buySuccessCount").getAsInt());
        assertEquals(2,first.get("invalidLines").getAsInt()); assertEquals(1,first.get("unreadableFiles").getAsInt());
        Files.writeString(AuditLogService.getLogFile(),buy(2,"shop","id","Name","20")+"\n",StandardOpenOption.APPEND);
        assertEquals(1,reader.query(query,false).getAsJsonObject("summary").get("buySuccessCount").getAsInt());
        assertEquals(2,reader.query(query,true).getAsJsonObject("summary").get("buySuccessCount").getAsInt());
    }

    @Test void flushesBufferedWriterAndReportsDisabledRecordingWithoutHidingHistory(MinecraftServer server) throws Exception {
        Files.createDirectories(AuditFiles.getConfigFile().getParent());
        Files.writeString(AuditFiles.getConfigFile(),"{\"enabled\":true,\"flush_each_entry\":false}");
        AuditLogService.initialize();
        AuditLogService.logVisibilityChanged("shop",false,"Maintenance","Admin","admin-id");
        AuditQueryService reader=new AuditQueryService();
        JsonObject result=reader.query(query("journal","shop","admin-id","","",0,""),false);
        assertEquals(1,result.getAsJsonArray("rows").size());
        assertEquals("Maintenance",result.getAsJsonArray("rows").get(0).getAsJsonObject().get("reason_message").getAsString());
        Files.writeString(AuditFiles.getConfigFile(),"{\"enabled\":false}"); AuditLogService.reload();
        JsonObject disabled=reader.query(query("journal","shop","admin-id","","",0,""),false);
        assertFalse(disabled.get("auditEnabled").getAsBoolean()); assertEquals(1,disabled.getAsJsonArray("rows").size());
    }

    @Test void periodsSearchAndListPaginationApplyToRecordedData(MinecraftServer server) throws Exception {
        List<String> lines=new ArrayList<>();
        for (int i=0;i<30;i++) lines.add(buy(i,"shop_"+i,"id_"+i,"Player"+i,"10"));
        JsonObject old=JsonParser.parseString(buy(40,"old","old-id","Old","99")).getAsJsonObject();
        old.addProperty("timestamp",Instant.now().minusSeconds(10*86400).toString()); lines.add(old.toString()); write(lines);
        AuditQueryService reader=new AuditQueryService();
        AuditQuery filtered=new AuditQuery("shops","24h","","","","","","all","name",1,"");
        JsonObject page=reader.query(filtered,false);
        assertEquals(30,page.get("total").getAsInt()); assertEquals(5,page.getAsJsonArray("rows").size());
        JsonObject found=reader.query(new AuditQuery("players","all","Player29","","","","","all","volume",0,""),false);
        assertEquals(1,found.get("total").getAsInt());
        assertEquals("id_29",found.getAsJsonArray("rows").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test void administrativeHistoryIsBrowsableWithoutAddingMoneyOrItems(MinecraftServer server) throws Exception {
        JsonObject stock = administrativeEvent("stock_changed", "stock-operation", "Admin");
        stock.addProperty("action", "set_stock"); stock.addProperty("shop_id", "deleted_shop");
        stock.addProperty("player_uuid", "offline-id"); stock.addProperty("player_name", "Offline");
        stock.addProperty("offer_id", "apples"); stock.addProperty("stock_key", "apples#variant");
        stock.addProperty("item_id", "minecraft:apple"); stock.addProperty("stock_before", 2); stock.addProperty("stock_after", 7);
        JsonObject prepared = administrativeEvent("config_changed", "configuration-operation", "Admin");
        prepared.addProperty("phase", "prepared"); prepared.addProperty("shop_id", "deleted_shop");
        prepared.addProperty("file", "shops/deleted_shop/shop.json"); prepared.addProperty("path", "/categories/0/offers/0/price");
        prepared.addProperty("before_present", true); prepared.addProperty("after_present", true);
        prepared.add("before", JsonParser.parseString("123456789012345678901234567890"));
        prepared.add("after", JsonParser.parseString("123456789012345678901234567891"));
        JsonObject applied = administrativeEvent("config_changed", "configuration-operation", "Admin");
        applied.addProperty("phase", "applied"); applied.addProperty("change_count", 1);
        applied.add("files", JsonParser.parseString("[\"shops/deleted_shop/shop.json\"]"));
        applied.add("shop_ids", JsonParser.parseString("[\"deleted_shop\"]"));
        JsonObject rolledBack = administrativeEvent("config_changed", "failed-operation", "Admin");
        rolledBack.addProperty("phase", "rolled_back");
        write(List.of(stock.toString(), prepared.toString(), applied.toString(), rolledBack.toString()));
        AuditQueryService reader = new AuditQueryService();
        JsonObject all = reader.query(query("journal", "", "", "", "", 0, ""), false);
        assertEquals(4, all.get("total").getAsInt()); assertEquals(0, all.get("invalidLines").getAsInt());
        JsonObject summary = all.getAsJsonObject("summary");
        assertEquals(1, summary.get("stockChangeCount").getAsInt());
        assertEquals(1, summary.get("configChangeCount").getAsInt());
        assertEquals("0", summary.get("spent").getAsString()); assertEquals("0", summary.get("earned").getAsString());
        JsonObject shop = reader.query(query("journal", "deleted_shop", "", "", "", 0, ""), false);
        assertEquals(3, shop.get("total").getAsInt());
        assertEquals(1, shop.getAsJsonObject("summary").get("configChangeCount").getAsInt());
        assertEquals(1, reader.query(query("journal", "", "offline-id", "minecraft:apple", "", 0, ""), false).get("total").getAsInt());
        assertEquals(4, reader.query(query("journal", "", "actor-id", "", "", 0, ""), false).get("total").getAsInt());
        assertEquals(1, reader.query(query("journal", "", "", "", "123456789012345678901234567891", 0, ""), false).get("total").getAsInt());
        var onlyConfig = new AuditQuery("journal", "all", "", "", "", "", "", "config_changed", "recent", 0, "");
        assertEquals(3, reader.query(onlyConfig, false).get("total").getAsInt());
        assertEquals(0, reader.query(query("players", "", "", "", "", 0, ""), false).get("total").getAsInt(),
                "Administrative changes must not invent player purchases or balances");
    }

    @Test void economyEventsExtendPlayerActivityWithoutChangingCommercialTotals(MinecraftServer server) throws Exception {
        String huge = "123456789012345678901234567890";
        JsonObject transfer = economy("player_transfer", "alice-id", "Alice", huge, "0");
        transfer.addProperty("recipient_uuid", "bob-id"); transfer.addProperty("recipient_name", "Bob");
        transfer.addProperty("recipient_balance_before", "0"); transfer.addProperty("recipient_balance_after", huge);
        archive("audit-economy-old.jsonl.zip", List.of(transfer.toString(), buy(1,"shop","alice-id","Alice","10")));
        JsonObject give = adjustment("alice-id", "Alice", "0", "9", "give", "9");
        JsonObject remove = adjustment("bob-id", "Bob", "5", "3", "remove", "2");
        JsonObject battle = economy("battle_reward", "alice-id", "Alice", "9", "16");
        JsonObject nativeBuy = economy("native_buy_success", "alice-id", "Alice", "16", "12");
        JsonObject nativeSell = economy("native_sell_success", "alice-id", "Alice", "12", "15");
        for (JsonObject nativeEvent : List.of(nativeBuy, nativeSell))
            nativeEvent.add("item_lines", JsonParser.parseString("[{\"item\":\"minecraft:carrot\",\"count\":2}]"));
        write(List.of(give.toString(), remove.toString(), battle.toString(), nativeBuy.toString(), nativeSell.toString()));

        AuditQueryService reader = new AuditQueryService();
        JsonObject result = reader.query(query("players","","","","",0,""), false);
        assertEquals(0, result.get("invalidLines").getAsInt());
        JsonObject summary = result.getAsJsonObject("summary");
        assertEquals(1, summary.get("transferCount").getAsInt());
        assertEquals(huge, summary.get("transferred").getAsString(), "A transfer is counted once globally");
        assertEquals(2, summary.get("balanceAdjustmentCount").getAsInt());
        assertEquals("9", summary.get("adminAdded").getAsString());
        assertEquals("2", summary.get("adminRemoved").getAsString());
        assertEquals(1, summary.get("battleRewardCount").getAsInt()); assertEquals("7", summary.get("battleRewards").getAsString());
        assertEquals(1, summary.get("nativeBuySuccessCount").getAsInt()); assertEquals("4", summary.get("nativeSpent").getAsString());
        assertEquals(1, summary.get("nativeSellSuccessCount").getAsInt()); assertEquals("3", summary.get("nativeEarned").getAsString());
        assertEquals("10", summary.get("spent").getAsString()); assertEquals("0", summary.get("earned").getAsString());
        assertEquals("10", summary.get("netFlow").getAsString(), "Transfers cannot create or remove commercial currency");

        JsonObject alice = result.getAsJsonArray("rows").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(row -> "alice-id".equals(row.get("id").getAsString())).findFirst().orElseThrow();
        assertEquals(huge, alice.get("transferSent").getAsString()); assertEquals("0", alice.get("transferReceived").getAsString());
        assertEquals(6, alice.get("operationCount").getAsInt());
        assertEquals("19", alice.get("moneyIn").getAsString());
        assertEquals(new BigInteger(huge).add(BigInteger.valueOf(14)), alice.get("moneyOut").getAsBigInteger());
        assertEquals(new BigInteger(huge).add(BigInteger.valueOf(33)), alice.get("volume").getAsBigInteger());
        assertEquals(2, reader.query(query("journal","","bob-id","","",0,""), false).get("total").getAsInt());
        assertEquals(2, reader.query(query("journal","","Bob","","",0,""), false).get("total").getAsInt());
        assertEquals(2, reader.query(query("journal","","Console","","",0,""), false).get("total").getAsInt());
        assertEquals(1, reader.query(new AuditQuery("journal","all","","","alice-id","","","player_transfer","recent",0,""),false)
                .get("total").getAsInt());
        assertEquals(1, reader.query(query("shops","","","","",0,""),false).get("total").getAsInt());
        assertEquals(1, reader.query(query("offers","","","","",0,""),false).get("total").getAsInt());
        assertEquals(1, reader.query(query("items","","","","",0,""),false).get("total").getAsInt());
        assertEquals(2, reader.query(query("journal","","","minecraft:carrot","",0,""),false).get("total").getAsInt());
        assertEquals(0, reader.query(query("items","","","minecraft:carrot","",0,""),false).get("total").getAsInt(),
                "Native item details do not invent addon item statistics");

        AuditStatsSnapshot snapshot = AuditStatsService.readSnapshot(AuditTimeWindow.ALL);
        assertTrue(snapshot.hasData()); assertEquals(new BigInteger(huge), snapshot.transferred());
        assertEquals(new BigInteger(huge), snapshot.findPlayer("Bob").transferReceived());
        assertEquals(2, snapshot.findPlayer("Bob").operationCount());
    }

    @Test void malformedItemNamesCannotPartiallyAffectAnyTotals(MinecraftServer server) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(buy(1,"valid","valid-player","Valid","10"));
        for (String type : List.of("buy_success", "sell_success")) {
            JsonObject corrupt = JsonParser.parseString(buy(2,"corrupt","corrupt-player","Corrupt","999")).getAsJsonObject();
            corrupt.addProperty("type", type);
            JsonObject badLine = corrupt.getAsJsonArray("item_lines").get(0).getAsJsonObject().deepCopy();
            badLine.add("name", new JsonObject());
            corrupt.getAsJsonArray("item_lines").add(badLine);
            lines.add(corrupt.toString());
        }
        write(lines);
        AuditQueryService reader = new AuditQueryService();
        JsonObject query = reader.query(query("journal","","","","",0,""), false);
        assertEquals(2, query.get("invalidLines").getAsInt()); assertEquals(1, query.get("total").getAsInt());
        assertEquals("10", query.getAsJsonObject("summary").get("spent").getAsString());
        assertEquals("0", query.getAsJsonObject("summary").get("earned").getAsString());
        AuditStatsSnapshot snapshot = AuditStatsService.readSnapshot(AuditTimeWindow.ALL);
        assertEquals(1, snapshot.buySuccessCount()); assertEquals(0, snapshot.sellSuccessCount());
        assertEquals(BigInteger.TEN, snapshot.totalSpent()); assertEquals(BigInteger.ZERO, snapshot.totalEarned());
        assertNull(snapshot.shop("corrupt")); assertNull(snapshot.findPlayer("corrupt-player"));
        assertEquals(1, snapshot.offersByKey().size());
        assertEquals(2, snapshot.item("minecraft:apple").boughtCount());
        assertEquals(0, snapshot.item("minecraft:apple").soldCount());
    }

    @Test void inconsistentEconomyEventsAreRejectedBeforeCreatingPlayers(MinecraftServer server) throws Exception {
        JsonObject invalid = economy("player_transfer", "sender", "Sender", "10", "5");
        invalid.addProperty("recipient_uuid", "recipient"); invalid.addProperty("recipient_name", "Recipient");
        invalid.addProperty("recipient_balance_before", "0"); invalid.addProperty("recipient_balance_after", "4");
        write(List.of(invalid.toString()));
        JsonObject result = new AuditQueryService().query(query("players","","","","",0,""), false);
        assertEquals(1, result.get("invalidLines").getAsInt()); assertEquals(0, result.get("total").getAsInt());
        assertEquals("0", result.getAsJsonObject("summary").get("transferred").getAsString());
        assertFalse(AuditStatsService.readSnapshot(AuditTimeWindow.ALL).hasData());
    }

    private static JsonObject economy(String type, String player, String name, String before, String after) {
        JsonObject event = new JsonObject();
        event.addProperty("schema_version", 1); event.addProperty("type", type);
        event.addProperty("timestamp", Instant.now().minusSeconds(10).toString());
        event.addProperty("operation_id", UUID.randomUUID().toString());
        event.addProperty("player_uuid", player); event.addProperty("player_name", name);
        event.addProperty("balance_before", before); event.addProperty("balance_after", after);
        BigInteger delta = new BigInteger(after).subtract(new BigInteger(before));
        event.addProperty("currency_delta", delta.toString()); event.addProperty("currency_amount", delta.abs().toString());
        return event;
    }

    private static JsonObject adjustment(String player, String name, String before, String after, String action, String requested) {
        JsonObject event = economy("balance_adjusted", player, name, before, after);
        event.addProperty("actor_name", "Console"); event.add("actor_uuid", JsonNull.INSTANCE);
        event.addProperty("action", action); event.addProperty("requested_amount", requested);
        return event;
    }

    private static JsonObject administrativeEvent(String type, String operation, String actor) {
        JsonObject row = new JsonObject();
        row.addProperty("timestamp", Instant.now().minusSeconds(10).toString()); row.addProperty("type", type);
        row.addProperty("operation_id", operation); row.addProperty("actor_uuid", "actor-id"); row.addProperty("actor_name", actor);
        row.addProperty("source", "admin");
        return row;
    }

    private static AuditQuery query(String view,String shop,String player,String item,String search,int page,String cursor) {
        return new AuditQuery(view,"all",search,shop,player,item,"","all","volume",page,cursor);
    }
    private static String buy(int index,String shop,String id,String name,String money) {
        JsonObject row=new JsonObject();
        row.addProperty("timestamp",Instant.now().minusSeconds(1000-index).toString()); row.addProperty("type","buy_success");
        row.addProperty("shop_id",shop); row.addProperty("offer_id","offer"); row.addProperty("player_uuid",id); row.addProperty("player_name",name);
        row.addProperty("currency_amount",money); row.addProperty("bundle_count",1); row.addProperty("item_count_total",2);
        JsonObject item=new JsonObject(); item.addProperty("item","minecraft:apple"); item.addProperty("count",2); item.addProperty("line_total",money);
        JsonArray items=new JsonArray(); items.add(item); row.add("item_lines",items); return row.toString();
    }
    private static void write(List<String> lines) throws Exception {
        Files.createDirectories(AuditLogService.getLogFile().getParent());
        Files.writeString(AuditLogService.getLogFile(),String.join("\n",lines)+"\n");
    }
    private static void archive(String name,List<String> lines) throws Exception {
        Files.createDirectories(AuditLogService.getLogFile().getParent());
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(AuditLogService.getLogFile().resolveSibling(name)))) {
            zip.putNextEntry(new ZipEntry("audit.jsonl")); zip.write((String.join("\n",lines)+"\n").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
    }
}
