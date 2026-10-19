package fr.cobbledollars.commandshops.audit;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditConfigurationDiffTest {
    @Test
    void preservesExactNumbersMissingNullAndEscapedJsonPointers() {
        List<AuditConfigurationDiff.Change> changes = AuditConfigurationDiff.between(
                Map.of("shop.json", json("{\"price\":9007199254740992,\"a/b~c\":null,\"removed\":null}")),
                Map.of("shop.json", json("{\"price\":9007199254740993,\"a/b~c\":7,\"added\":null}")));
        assertEquals(4, changes.size());
        var price = change(changes, "/price");
        assertEquals("9007199254740992", price.before().toString());
        assertEquals("9007199254740993", price.after().toString());
        assertTrue(change(changes, "/a~1b~0c").before().isJsonNull());
        assertNull(change(changes, "/added").before());
        assertTrue(change(changes, "/added").after().isJsonNull());
        assertTrue(change(changes, "/removed").before().isJsonNull());
        assertNull(change(changes, "/removed").after());
    }

    @Test
    void distinguishesReorderingFileCreationAndDeletionWithoutTreatingObjectOrderAsAChange() {
        List<AuditConfigurationDiff.Change> changes = AuditConfigurationDiff.between(
                Map.of("unchanged", json("{\"a\":1,\"b\":2}"), "offers", json("[\"a\",\"b\"]"), "deleted", json("[1]")),
                Map.of("unchanged", json("{\"b\":2,\"a\":1}"), "offers", json("[\"b\",\"a\"]"), "created", json("{\"new\":true}")));
        assertEquals(4, changes.size());
        assertTrue(changes.stream().noneMatch(change -> change.file().equals("unchanged")));
        assertEquals(2, changes.stream().filter(change -> change.file().equals("offers")).count());
        assertEquals("added", changes.stream().filter(change -> change.file().equals("created")).findFirst().orElseThrow().kind());
        assertEquals("removed", changes.stream().filter(change -> change.file().equals("deleted")).findFirst().orElseThrow().kind());
    }

    private static JsonElement json(String text) { return JsonParser.parseString(text); }
    private static AuditConfigurationDiff.Change change(List<AuditConfigurationDiff.Change> changes, String path) {
        return changes.stream().filter(change -> change.path().equals(path)).findFirst().orElseThrow();
    }
}
