package fr.cobbledollars.commandshops.audit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EphemeralTestServerProvider.class)
class AuditLogServiceServerTest {
    @AfterEach
    void cleanup() throws Exception {
        AuditLogService.shutdown();
        Files.deleteIfExists(AuditFiles.getConfigFile());
        deleteRecursively(AuditLogService.getLogFile().getParent());
    }

    @Test
    void visibilityEventsUseTheNormalizedAuditSchema(MinecraftServer server) throws Exception {
        AuditLogService.initialize();
        AuditLogService.logVisibilityChanged("general_store", true, null, "tester", "uuid-1");
        AuditLogService.logVisibilityChanged("general_store", false, "Maintenance", "tester", "uuid-2");
        AuditLogService.shutdown();

        List<String> lines = Files.readAllLines(AuditLogService.getLogFile());
        assertEquals(2, lines.size());

        JsonObject first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals(1, first.get("schema_version").getAsInt());
        assertEquals("visibility_changed", first.get("type").getAsString());
        assertEquals("general_store", first.get("shop_id").getAsString());
        assertTrue(first.get("enabled").getAsBoolean());
        assertFalse(first.has("action"));
        assertEquals("tester", first.get("actor_name").getAsString());
        assertEquals("uuid-1", first.get("actor_uuid").getAsString());

        JsonObject second = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertFalse(second.get("enabled").getAsBoolean());
        assertFalse(second.has("action"));
        assertEquals("Maintenance", second.get("reason_message").getAsString());
    }

    @Test
    void disabledEventsDoNotCreateAuditEntries(MinecraftServer server) throws Exception {
        Files.createDirectories(AuditFiles.getConfigFile().getParent());
        Files.writeString(AuditFiles.getConfigFile(), """
                {
                  "enabled": true,
                  "flush_each_entry": true,
                  "events": {
                    "visibility_changed": false
                  }
                }
                """);

        AuditLogService.initialize();
        AuditLogService.logVisibilityChanged("general_store", true, null, "tester", "uuid-1");
        AuditLogService.shutdown();

        assertFalse(Files.exists(AuditLogService.getLogFile()));
    }

    @Test
    void initializeRotatesExistingAuditLogIntoCompressedArchive(MinecraftServer server) throws Exception {
        Path logFile = AuditLogService.getLogFile();
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, """
                {"schema_version":1,"type":"visibility_changed","timestamp":"2026-05-15T10:00:00Z","shop_id":"general_store","enabled":true}
                """);

        AuditLogService.initialize();

        assertFalse(Files.exists(logFile));
        List<Path> files = AuditLogService.listReadableLogFiles();
        assertEquals(1, files.size());
        Path archivedFile = files.get(0);
        assertTrue(archivedFile.getFileName().toString().startsWith("audit-"));
        assertTrue(archivedFile.getFileName().toString().endsWith(".jsonl.zip"));

        try (ZipInputStream inputStream = new ZipInputStream(Files.newInputStream(archivedFile), StandardCharsets.UTF_8)) {
            assertEquals(archivedFile.getFileName().toString().replace(".zip", ""), inputStream.getNextEntry().getName());
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("\"shop_id\":\"general_store\""));
        }
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
