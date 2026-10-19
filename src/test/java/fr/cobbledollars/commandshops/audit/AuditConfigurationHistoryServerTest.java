package fr.cobbledollars.commandshops.audit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(EphemeralTestServerProvider.class)
class AuditConfigurationHistoryServerTest {
    private static final String ACTOR_UUID = "11111111-2222-3333-4444-555555555555";

    @AfterEach
    void cleanup() throws Exception {
        AuditLogService.shutdown();
        Files.deleteIfExists(AuditFiles.getConfigFile());
        Path directory = AuditLogService.getLogFile().getParent();
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void disablingConfigurationHistoryIsDurableBeforeDeactivationEvenWithBufferedLogging(MinecraftServer server) throws Exception {
        assertDeactivationIsRecorded(false);
    }

    @Test
    void disablingAllAuditIsDurableBeforeDeactivationEvenWithBufferedLogging(MinecraftServer server) throws Exception {
        assertDeactivationIsRecorded(true);
    }

    private void assertDeactivationIsRecorded(boolean disableAll) throws Exception {
        JsonObject enabled = json("{\"enabled\":true,\"flush_each_entry\":false,\"events\":{\"config_changed\":true}}").getAsJsonObject();
        initialize(enabled);
        JsonObject disabled = enabled.deepCopy();
        if (disableAll) disabled.addProperty("enabled", false);
        else disabled.getAsJsonObject("events").addProperty("config_changed", false);
        Map<String, JsonElement> before = Map.of("audit.json", enabled);
        Map<String, JsonElement> after = Map.of("audit.json", disabled);
        var change = AuditLogService.prepareConfigurationChange(before, after, "admin", "Administrator", ACTOR_UUID);

        // Read the file directly: prepareRead/shutdown must not be what flushes the pending audit entry.
        List<JsonObject> prepared = events();
        assertEquals(1, prepared.size());
        assertEquals("prepared", prepared.getFirst().get("phase").getAsString());
        assertEquals(disableAll ? "/enabled" : "/events/config_changed", prepared.getFirst().get("path").getAsString());
        assertTrue(prepared.getFirst().get("before").getAsBoolean());
        assertFalse(prepared.getFirst().get("after").getAsBoolean());
        assertEquals("Administrator", prepared.getFirst().get("actor_name").getAsString());
        assertEquals(ACTOR_UUID, prepared.getFirst().get("actor_uuid").getAsString());

        Files.writeString(AuditFiles.getConfigFile(), disabled.toString());
        AuditLogService.reload();
        change.complete("applied");
        List<JsonObject> completed = events();
        assertEquals(2, completed.size());
        assertEquals("applied", completed.getLast().get("phase").getAsString());
        assertEquals(prepared.getFirst().get("operation_id"), completed.getLast().get("operation_id"));

        var ignored = AuditLogService.prepareConfigurationChange(after,
                Map.of("audit.json", disabled, "global_bank.json", json("[]")), "admin", "Administrator", ACTOR_UUID);
        ignored.complete("applied");
        assertEquals(2, events().size());
    }

    @Test
    void reenablingRecordingIsRecordedAndNoOpSavesAreSilent(MinecraftServer server) throws Exception {
        JsonObject disabled = json("{\"enabled\":false,\"flush_each_entry\":false}").getAsJsonObject();
        initialize(disabled);
        Map<String, JsonElement> before = Map.of("audit.json", disabled);
        var noOp = AuditLogService.prepareConfigurationChange(before, before, "admin", "Operator", ACTOR_UUID);
        noOp.complete("applied");
        assertFalse(Files.exists(AuditLogService.getLogFile()));
        JsonObject enabled = disabled.deepCopy();
        enabled.addProperty("enabled", true);
        Map<String, JsonElement> after = Map.of("audit.json", enabled);
        var activation = AuditLogService.prepareConfigurationChange(before, after, "admin", "Operator", ACTOR_UUID);
        assertEquals("prepared", events().getFirst().get("phase").getAsString());
        Files.writeString(AuditFiles.getConfigFile(), enabled.toString());
        AuditLogService.reload();
        activation.complete("applied");
        assertEquals(2, events().size());
        var enabledNoOp = AuditLogService.prepareConfigurationChange(after, after, "admin", "Operator", ACTOR_UUID);
        enabledNoOp.complete("applied");
        assertEquals(2, events().size());
    }

    @Test
    void capturesExactValuesAndExplicitNullAndCorrelatesRollbackWithoutCallingItApplied(MinecraftServer server) throws Exception {
        JsonObject audit = json("{}").getAsJsonObject();
        initialize(audit);
        Map<String, JsonElement> before = Map.of("audit.json", audit,
                "shops/example/shop.json", json("{\"price\":9007199254740992,\"removed\":null,\"order\":[\"a\",\"b\"]}"));
        Map<String, JsonElement> after = Map.of("audit.json", audit,
                "shops/example/shop.json", json("{\"price\":9007199254740993,\"added\":null,\"order\":[\"b\",\"a\"]}"));
        var transaction = AuditLogService.prepareConfigurationChange(before, after, "reload", "Console", null);
        transaction.complete("rolled_back");
        transaction.complete("applied"); // Only the first terminal outcome is allowed.
        List<JsonObject> events = events();
        assertEquals(6, events.size());
        assertTrue(events.stream().noneMatch(event -> event.get("phase").getAsString().equals("applied")));
        assertEquals(1, events.stream().map(event -> event.get("operation_id").getAsString()).distinct().count());
        JsonObject price = events.stream().filter(event -> event.has("path") && event.get("path").getAsString().equals("/price")).findFirst().orElseThrow();
        assertEquals("9007199254740992", price.get("before").toString());
        assertEquals("9007199254740993", price.get("after").toString());
        JsonObject added = events.stream().filter(event -> event.has("path") && event.get("path").getAsString().equals("/added")).findFirst().orElseThrow();
        assertFalse(added.get("before_present").getAsBoolean());
        assertTrue(added.get("after_present").getAsBoolean());
        assertTrue(added.get("after").isJsonNull());
        assertEquals("example", price.get("shop_id").getAsString());
        assertEquals("reload", price.get("source").getAsString());
        assertEquals("Console", price.get("actor_name").getAsString());
        assertFalse(price.has("actor_uuid"));
        JsonObject outcome = events.getLast();
        assertEquals("rolled_back", outcome.get("phase").getAsString());
        assertEquals(5, outcome.get("change_count").getAsInt());
        assertEquals("example", outcome.getAsJsonArray("shop_ids").get(0).getAsString());
    }

    @Test
    void hugeValuesAndManyTouchedFilesKeepAllLinesWithinReaderBound(MinecraftServer server) throws Exception {
        JsonObject audit = json("{}").getAsJsonObject();
        initialize(audit);
        Map<String, JsonElement> before = new TreeMap<>();
        Map<String, JsonElement> after = new TreeMap<>();
        before.put("audit.json", audit);
        after.put("audit.json", audit);
        for (int index = 0; index < 1022; index++) {
            String file = "shops/" + "long_shop_name_".repeat(10) + index + "/shop.json";
            before.put(file, json("{\"price\":1}"));
            after.put(file, json("{\"price\":2}"));
        }
        before.put("feedback.json", json("{}"));
        JsonObject huge = new JsonObject();
        huge.addProperty("message", "very long description ".repeat(20_000));
        after.put("feedback.json", huge);
        var transaction = AuditLogService.prepareConfigurationChange(before, after, "admin", "Admin", ACTOR_UUID);
        transaction.complete("applied");
        for (String line : Files.readAllLines(AuditLogService.getLogFile())) {
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length < 64 * 1024);
        }
        List<JsonObject> events = events();
        JsonObject large = events.stream().filter(event -> event.has("after_truncated")).findFirst().orElseThrow();
        assertTrue(large.get("after_truncated").getAsBoolean());
        assertEquals(64, large.get("after_sha256").getAsString().length());
        assertTrue(large.get("after_bytes").getAsInt() > 400_000);
        JsonObject outcome = events.getLast();
        assertEquals("applied", outcome.get("phase").getAsString());
        assertTrue(outcome.get("files_truncated").getAsBoolean());
        assertEquals(1023, outcome.get("files_count").getAsInt());
        assertTrue(outcome.get("shop_ids_truncated").getAsBoolean());
        assertEquals(1022, outcome.get("shop_ids_count").getAsInt());
    }

    @Test
    void unavailableLogDestinationRejectsPreparationBeforeConfigurationCanBeDisabled(MinecraftServer server) throws Exception {
        JsonObject before = json("{\"enabled\":true,\"flush_each_entry\":false}").getAsJsonObject();
        initialize(before);
        Files.createDirectories(AuditLogService.getLogFile());
        JsonObject after = before.deepCopy();
        after.addProperty("enabled", false);
        assertThrows(IOException.class, () -> AuditLogService.prepareConfigurationChange(Map.of("audit.json", before),
                Map.of("audit.json", after), "admin", "Admin", ACTOR_UUID));
        assertTrue(AuditFiles.loadConfig().enabled());
        assertTrue(AuditLogService.prepareRead().enabled());
    }

    private static void initialize(JsonObject audit) throws IOException {
        Files.createDirectories(AuditFiles.getConfigFile().getParent());
        Files.writeString(AuditFiles.getConfigFile(), audit.toString());
        AuditLogService.initialize();
    }

    private static JsonElement json(String text) { return JsonParser.parseString(text); }

    private static List<JsonObject> events() throws IOException {
        return Files.readAllLines(AuditLogService.getLogFile()).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }
}
