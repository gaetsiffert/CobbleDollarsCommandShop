package fr.cobbledollars.commandshops.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(EphemeralTestServerProvider.class)
class TransactionAuditLoggerServerTest {
    @AfterEach
    void cleanup() throws Exception {
        TransactionAuditLogger.shutdown();
        deleteRecursively(logFile().getParent());
    }

    @Test
    void visibilityEventsAreAppendedAsJsonLines(MinecraftServer server) throws Exception {
        TransactionAuditLogger.initialize();
        TransactionAuditLogger.logVisibilityChanged("general_store", true, null, "tester", "uuid-1");
        TransactionAuditLogger.logVisibilityChanged("general_store", false, "Maintenance", "tester", "uuid-2");
        TransactionAuditLogger.shutdown();

        List<String> lines = Files.readAllLines(logFile());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"visibility_changed\""));
        assertTrue(lines.get(0).contains("\"shop_id\":\"general_store\""));
        assertTrue(lines.get(0).contains("\"enabled\":true"));
        assertTrue(lines.get(1).contains("\"enabled\":false"));
        assertTrue(lines.get(1).contains("\"message\":\"Maintenance\""));
    }

    private static Path logFile() throws ReflectiveOperationException {
        Field field = TransactionAuditLogger.class.getDeclaredField("LOG_FILE");
        field.setAccessible(true);
        return (Path) field.get(null);
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
