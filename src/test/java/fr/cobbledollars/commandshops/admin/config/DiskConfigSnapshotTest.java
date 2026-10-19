package fr.cobbledollars.commandshops.admin.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DiskConfigSnapshotTest {
    @TempDir Path directory;
    private Path root;

    @BeforeEach
    void initialize() throws IOException {
        root = directory.resolve("config");
        write("audit.json", "{\"enabled\":false}");
        write("commands.json", "{\"allow_player_open\":false}");
        write("feedback.json", "{}");
        write("global_bank.json", "[]");
    }

    @Test
    void largeValidFilesRemainReadableWithoutRelaxingEditorLimits() throws IOException {
        String json = "{\"categories\":[]}";
        write("shops/large/shop.json", json + " ".repeat(AdminConfigStore.MAX_FILE_BYTES));

        assertEquals(JsonParser.parseString(json), DiskConfigSnapshot.read(root).get("shops/large/shop.json"));
        assertThrows(IOException.class, () -> new AdminConfigStore(root).snapshot());
    }

    @Test
    void cumulativeSizeDoesNotLimitServerLocalSnapshots() throws IOException {
        String json = "{\"categories\":[]}";
        for (int index = 0; index < 5; index++) {
            write("shops/large_" + index + "/shop.json", json + " ".repeat(1_800_000));
        }

        assertEquals(9, DiskConfigSnapshot.read(root).size());
        assertThrows(IOException.class, () -> new AdminConfigStore(root).snapshot());
    }

    @Test
    void shopCountDoesNotLimitServerLocalSnapshots() throws IOException {
        for (int index = 0; index < AdminConfigStore.MAX_FILES; index++) {
            write("shops/shop_" + index + "/shop.json", "{\"categories\":[]}");
        }
        write("shops/README.txt", "A regular file is ignored by the normal loader.");

        assertEquals(AdminConfigStore.MAX_FILES + 4, DiskConfigSnapshot.read(root).size());
        assertThrows(IOException.class, () -> new AdminConfigStore(root).snapshot());
    }

    @Test
    void unusedDeepJsonRemainsReadableWithoutRelaxingEditorDepthLimit() throws IOException {
        String json = "{\"categories\":[],\"unused\":" + "[".repeat(80) + "0" + "]".repeat(80) + "}";
        write("shops/deep/shop.json", json);

        assertEquals(JsonParser.parseString(json), DiskConfigSnapshot.read(root).get("shops/deep/shop.json"));
        assertThrows(IOException.class, () -> new AdminConfigStore(root).snapshot());
    }

    @Test
    void administratorManagedLinkedShopsRemainReadableButCannotBeEdited() throws IOException {
        Path external = directory.resolve("managed-shop");
        Files.createDirectories(external);
        Files.writeString(external.resolve("shop.json"), "{\"categories\":[]}");
        Files.writeString(external.resolve("bank.json"), "[]");
        Files.createDirectories(root.resolve("shops"));
        try {
            Files.createSymbolicLink(root.resolve("shops/linked"), external);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Creating symbolic links is unavailable: " + unavailable.getMessage());
        }

        Map<String, JsonElement> snapshot = DiskConfigSnapshot.read(root);
        assertEquals(JsonParser.parseString("{\"categories\":[]}"), snapshot.get("shops/linked/shop.json"));
        assertEquals(JsonParser.parseString("[]"), snapshot.get("shops/linked/bank.json"));
        assertThrows(IOException.class, () -> new AdminConfigStore(root).snapshot());
    }

    private void write(String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
