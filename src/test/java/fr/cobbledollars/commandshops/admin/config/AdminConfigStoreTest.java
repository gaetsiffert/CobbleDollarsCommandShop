package fr.cobbledollars.commandshops.admin.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.admin.AdminMessage;
import fr.cobbledollars.commandshops.admin.AdminMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AdminConfigStoreTest {
    @TempDir
    Path root;
    AdminConfigStore store;

    @BeforeEach
    void initialize() throws IOException {
        store = new AdminConfigStore(root);
        write("audit.json", "{\"enabled\":true}");
        write("commands.json", "{\"allow_player_open\":true}");
        write("feedback.json", "{}");
        write("global_bank.json", "[]");
        write("shops/original/shop.json", "{\"categories\":[]}");
        write("shops/original/bank.json", "{\"offers\":[]}");
    }

    @Test
    void snapshotContainsOnlySupportedConfigFilesAndDetectsExternalEdits() throws IOException {
        write("secret.json", "{\"secret\":true}");
        write("shops/original/readme.txt", "keep me");
        JsonObject initial = snapshot();
        assertEquals(6, initial.getAsJsonObject("files").size());
        assertFalse(initial.getAsJsonObject("files").has("secret.json"));
        assertTrue(initial.getAsJsonObject("files").get("global_bank.json").isJsonArray());

        write("audit.json", "{\"enabled\":false}");
        assertFalse(initial.get("revision").equals(snapshot().get("revision")));
    }

    @Test
    void saveCreatesShopsRemovesLocalBanksAndAppliesOneValidatedSnapshot() throws IOException {
        JsonObject edit = snapshot();
        JsonObject files = edit.getAsJsonObject("files");
        files.remove("shops/original/bank.json");
        files.add("shops/new_shop/shop.json", JsonParser.parseString("{\"categories\":[]}"));
        files.add("shops/new_shop/bank.json", JsonParser.parseString("[]"));
        AtomicInteger validation = new AtomicInteger();
        AtomicInteger activation = new AtomicInteger();

        String saved = store.save(edit.toString(), proposed -> {
            assertFalse(Files.exists(root.resolve("shops/new_shop/shop.json")));
            assertTrue(Files.exists(root.resolve("shops/original/bank.json")));
            assertEquals(7, proposed.size());
            validation.incrementAndGet();
        }, () -> {
            assertTrue(Files.exists(root.resolve("shops/new_shop/bank.json")));
            assertFalse(Files.exists(root.resolve("shops/original/bank.json")));
            activation.incrementAndGet();
        });

        assertEquals(1, validation.get());
        assertEquals(1, activation.get());
        assertEquals("{\"enabled\":true}", Files.readString(root.resolve("audit.json")));
        assertEquals(JsonParser.parseString(saved), snapshot());
    }

    @Test
    void deletingShopRemovesItsDirectorySoRegistryCanReload() throws IOException {
        JsonObject edit = snapshot();
        edit.getAsJsonObject("files").remove("shops/original/shop.json");
        edit.getAsJsonObject("files").remove("shops/original/bank.json");

        store.save(edit.toString(), ignored -> { }, () -> { });

        assertFalse(Files.exists(root.resolve("shops/original")));
    }

    @Test
    void savesPricesThatDifferBeyondDoublePrecision() throws IOException {
        write("global_bank.json", "[{\"price\":9007199254740992}]");
        JsonObject edit = snapshot();
        edit.getAsJsonObject("files").getAsJsonArray("global_bank.json").get(0).getAsJsonObject()
                .add("price", JsonParser.parseString("9007199254740993"));

        store.save(edit.toString(), ignored -> { }, () -> { });

        JsonObject savedOffer = JsonParser.parseString(Files.readString(root.resolve("global_bank.json")))
                .getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("9007199254740993", savedOffer.get("price").getAsString());
    }

    @Test
    void preservesAndEditsExistingMixedCaseShopFolders() throws IOException {
        write("shops/LegacyShop/shop.json", "{\"categories\":[]}");
        write("shops/LegacyShop/bank.json", "[]");
        JsonObject edit = snapshot();
        assertTrue(edit.getAsJsonObject("files").has("shops/LegacyShop/bank.json"));
        edit.getAsJsonObject("files").getAsJsonObject("shops/LegacyShop/shop.json").addProperty("deny_message", "Reserved");

        store.save(edit.toString(), ignored -> { }, () -> { });

        assertEquals("Reserved", snapshot().getAsJsonObject("files")
                .getAsJsonObject("shops/LegacyShop/shop.json").get("deny_message").getAsString());
    }

    @Test
    void staleRevisionDoesNotOverwriteExternalChanges() throws IOException {
        JsonObject edit = snapshot();
        write("audit.json", "{\"enabled\":false}");

        IOException failure = assertThrows(IOException.class,
                () -> store.save(edit.toString(), ignored -> { }, () -> { }));

        assertLocalized(failure, "store.changed", List.of());
        assertTrue(failure.getMessage().contains("changed"));
        assertEquals("{\"enabled\":false}", Files.readString(root.resolve("audit.json")));
    }

    @Test
    void validationFailureLeavesAllFilesAndRevisionUnchanged() throws IOException {
        String initial = store.snapshot();
        JsonObject edit = JsonParser.parseString(initial).getAsJsonObject();
        edit.getAsJsonObject("files").add("audit.json", JsonParser.parseString("{\"enabled\":false}"));

        IOException failure = assertThrows(IOException.class, () -> store.save(edit.toString(), ignored -> {
            throw new IOException("Invalid price");
        }, () -> { throw new AssertionError("Activation must not run"); }));

        assertEquals("Invalid price", failure.getMessage());
        assertEquals(AdminMessage.literal("Invalid price"), AdminMessages.fromException(failure));
        assertEquals(initial, store.snapshot());
    }

    @Test
    void detailedParserErrorsKeepTheirEnglishDiagnosticAsALiteral() throws IOException {
        write("feedback.json", "{invalid");

        IOException failure = assertThrows(IOException.class, () -> store.snapshot());

        assertTrue(failure.getMessage().startsWith("Invalid JSON in feedback.json: "));
        assertEquals(AdminMessage.literal(failure.getMessage()), AdminMessages.fromException(failure));
    }

    @Test
    void failedActivationRestoresOriginalBytesDeletedShopsAndPreviousRuntime() throws IOException {
        String initial = store.snapshot();
        Map<String, String> original = Map.of(
                "audit.json", Files.readString(root.resolve("audit.json")),
                "shops/original/shop.json", Files.readString(root.resolve("shops/original/shop.json")),
                "shops/original/bank.json", Files.readString(root.resolve("shops/original/bank.json")));
        JsonObject edit = snapshot();
        JsonObject files = edit.getAsJsonObject("files");
        files.add("audit.json", JsonParser.parseString("{\"enabled\":false}"));
        files.remove("shops/original/shop.json");
        files.remove("shops/original/bank.json");
        files.add("shops/replacement/shop.json", JsonParser.parseString("{\"categories\":[]}"));
        AtomicInteger activations = new AtomicInteger();

        assertThrows(IOException.class, () -> store.save(edit.toString(), ignored -> { }, () -> {
            if (activations.incrementAndGet() == 1) {
                throw new IOException("Simulated runtime reload failure");
            }
            assertTrue(Files.exists(root.resolve("shops/original/shop.json")));
        }));

        assertEquals(2, activations.get());
        assertEquals(initial, store.snapshot());
        assertFalse(Files.exists(root.resolve("shops/replacement")));
        for (var entry : original.entrySet()) {
            assertEquals(entry.getValue(), Files.readString(root.resolve(entry.getKey())));
        }
        try (var children = Files.list(root)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith(".admin-config-")));
        }
    }

    @Test
    void durablePreparationHappensBeforeMutationAndOutcomeFollowsActivation() throws IOException {
        JsonObject edit = snapshot();
        edit.getAsJsonObject("files").getAsJsonObject("audit.json").addProperty("enabled", false);
        List<String> order = new ArrayList<>();
        store.save(edit.toString(), ignored -> order.add("validate"), () -> {
            assertFalse(JsonParser.parseString(Files.readString(root.resolve("audit.json"))).getAsJsonObject().get("enabled").getAsBoolean());
            order.add("activate");
        }, new AdminConfigStore.ChangeObserver() {
            @Override
            public void prepare(Map<String, com.google.gson.JsonElement> before, Map<String, com.google.gson.JsonElement> after) throws IOException {
                assertTrue(JsonParser.parseString(Files.readString(root.resolve("audit.json"))).getAsJsonObject().get("enabled").getAsBoolean());
                assertTrue(before.get("audit.json").getAsJsonObject().get("enabled").getAsBoolean());
                assertFalse(after.get("audit.json").getAsJsonObject().get("enabled").getAsBoolean());
                order.add("durable_prepare");
            }
            @Override public void complete(String outcome) { order.add(outcome); }
        });
        assertEquals(List.of("validate", "durable_prepare", "activate", "applied"), order);
    }

    @Test
    void preparationFailureRejectsEditAndConcurrentExternalEditDuringPreparationIsNotOverwritten() throws IOException {
        String initial = store.snapshot();
        JsonObject edit = snapshot();
        edit.getAsJsonObject("files").getAsJsonObject("audit.json").addProperty("enabled", false);
        assertThrows(IOException.class, () -> store.save(edit.toString(), ignored -> { }, () -> fail("Must not activate"),
                new AdminConfigStore.ChangeObserver() {
                    @Override public void prepare(Map<String, com.google.gson.JsonElement> before, Map<String, com.google.gson.JsonElement> after) throws IOException {
                        throw new IOException("Audit storage is unavailable");
                    }
                }));
        assertEquals(initial, store.snapshot());
        List<String> outcomes = new ArrayList<>();
        assertThrows(IOException.class, () -> store.save(edit.toString(), ignored -> { }, () -> fail("Must not activate"),
                new AdminConfigStore.ChangeObserver() {
                    @Override public void prepare(Map<String, com.google.gson.JsonElement> before, Map<String, com.google.gson.JsonElement> after) throws IOException {
                        Files.writeString(root.resolve("feedback.json"), "{\"external\":true}");
                    }
                    @Override public void complete(String outcome) { outcomes.add(outcome); }
                }));
        assertEquals(List.of("failed"), outcomes);
        assertEquals("{\"external\":true}", Files.readString(root.resolve("feedback.json")));
        assertEquals("{\"enabled\":true}", Files.readString(root.resolve("audit.json")));
    }

    @Test
    void failedActivationProducesOnlyRolledBackOutcomeAfterRestoringPreviousFiles() throws IOException {
        String initial = store.snapshot();
        JsonObject edit = snapshot();
        edit.getAsJsonObject("files").getAsJsonObject("audit.json").addProperty("enabled", false);
        AtomicInteger calls = new AtomicInteger();
        List<String> outcomes = new ArrayList<>();
        assertThrows(IOException.class, () -> store.save(edit.toString(), ignored -> { }, () -> {
            if (calls.incrementAndGet() == 1) throw new IOException("Activation failed");
        }, new AdminConfigStore.ChangeObserver() {
            @Override public void complete(String outcome) {
                outcomes.add(outcome);
                try { assertEquals(initial, store.snapshot()); } catch (IOException exception) { throw new AssertionError(exception); }
            }
        }));
        assertEquals(List.of("rolled_back"), outcomes);
    }

    @Test
    void rejectsUnmanagedPathsAndOrphanLocalBanks() throws IOException {
        String original = store.snapshot();
        for (String path : new String[]{"../outside.json", "shops/../shop.json", "shops/invalid.id/shop.json",
                "shops/original/other.json", "C:/outside.json", "shops\\original\\shop.json"}) {
            JsonObject edit = snapshot();
            edit.getAsJsonObject("files").add(path, new JsonObject());
            IOException failure = assertThrows(IOException.class, () -> store.save(edit.toString(), ignored -> { }, () -> { }));
            assertLocalized(failure, "store.path_not_allowed", List.of(path));
        }
        JsonObject orphan = snapshot();
        orphan.getAsJsonObject("files").add("shops/orphan/bank.json", JsonParser.parseString("[]"));
        assertThrows(IOException.class, () -> store.save(orphan.toString(), ignored -> { }, () -> { }));
        assertEquals(original, store.snapshot());
    }

    @Test
    void refusesShopDeletionWhenFolderContainsUnmanagedFiles() throws IOException {
        write("shops/original/notes.txt", "do not delete");
        JsonObject edit = snapshot();
        edit.getAsJsonObject("files").remove("shops/original/shop.json");
        edit.getAsJsonObject("files").remove("shops/original/bank.json");

        IOException failure = assertThrows(IOException.class, () -> store.save(edit.toString(), ignored -> { }, () -> { }));

        assertLocalized(failure, "store.shop_not_empty", List.of("original"));
        assertEquals("Cannot delete shop 'original': its folder contains other files.", failure.getMessage());

        assertEquals("do not delete", Files.readString(root.resolve("shops/original/notes.txt")));
        assertTrue(Files.exists(root.resolve("shops/original/shop.json")));
    }

    @Test
    void refusesRemovingRequiredGlobalFiles() throws IOException {
        JsonObject edit = snapshot();
        edit.getAsJsonObject("files").remove("global_bank.json");
        assertThrows(IOException.class, () -> store.save(edit.toString(), ignored -> { }, () -> { }));
        assertTrue(Files.exists(root.resolve("global_bank.json")));
    }

    @Test
    void rejectsOversizedFilesAndExcessiveJsonNestingWithoutChangingDisk() throws IOException {
        String original = store.snapshot();
        JsonObject tooLarge = snapshot();
        JsonObject feedback = tooLarge.getAsJsonObject("files").getAsJsonObject("feedback.json");
        feedback.addProperty("large", "x".repeat(AdminConfigStore.MAX_FILE_BYTES));
        IOException failure = assertThrows(IOException.class, () -> store.save(tooLarge.toString(), ignored -> { }, () -> { }));
        assertLocalized(failure, "store.file_too_large", List.of("feedback.json"));

        JsonObject tooDeep = snapshot();
        JsonObject node = tooDeep.getAsJsonObject("files").getAsJsonObject("feedback.json");
        for (int index = 0; index < 70; index++) {
            JsonObject child = new JsonObject();
            node.add("nested", child);
            node = child;
        }
        assertThrows(IOException.class, () -> store.save(tooDeep.toString(), ignored -> { }, () -> { }));
        assertEquals(original, store.snapshot());
    }

    private JsonObject snapshot() throws IOException {
        return JsonParser.parseString(store.snapshot()).getAsJsonObject();
    }

    private static void assertLocalized(IOException failure, String shortId, List<String> arguments) {
        AdminMessage message = AdminMessages.fromException(failure);
        assertEquals("cobbledollarscommandshops.admin.message." + shortId, message.key());
        assertEquals(arguments, message.arguments());
        assertEquals(failure.getMessage(), message.english());
    }

    private void write(String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
