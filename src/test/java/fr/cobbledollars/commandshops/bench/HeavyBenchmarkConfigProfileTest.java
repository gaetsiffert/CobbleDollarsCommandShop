package fr.cobbledollars.commandshops.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeavyBenchmarkConfigProfileTest {
    private static final Path PROJECT_DIR = Path.of(System.getProperty("commandshops.projectDir", "."))
            .toAbsolutePath()
            .normalize();
    private static final Path SHOP_FILE = PROJECT_DIR.resolve(Path.of("bench-configs", "heavy_shop_bank", "perf_megastore", "shop.json"));
    private static final Path BANK_FILE = PROJECT_DIR.resolve(Path.of("bench-configs", "heavy_shop_bank", "perf_megastore", "bank.json"));

    @Test
    void shopBenchmarkConfigKeepsItsExpectedScale() throws IOException {
        JsonObject root = readJsonObject(SHOP_FILE);

        assertEquals(20, root.getAsJsonArray("categories").size(), "category count changed");
        assertEquals(408, countOffers(root), "offer count changed");
        assertEquals(384, countIncludes(root, "stack"), "exact-stack offer count changed");
        assertEquals(24, countIncludes(root, "tag"), "tag stress offer count changed");
        assertEquals(177, countOffersWithField(root, "stock"), "finite stock offer count changed");
        assertEquals(76, countOffersWithField(root, "purchase_bonuses"), "purchase bonus offer count changed");
        assertEquals(408, countUniqueShopOfferIds(root), "shop offer ids are no longer unique");
    }

    @Test
    void bankBenchmarkConfigKeepsItsExpectedScale() throws IOException {
        JsonObject root = readJsonObject(BANK_FILE);

        assertEquals(9, root.getAsJsonArray("categories").size(), "category count changed");
        assertEquals(271, countOffers(root), "offer count changed");
        assertEquals(1, countIncludes(root, "mod"), "catch-all mod rule count changed");
        assertEquals(24, countIncludes(root, "item"), "exact item override count changed");
        assertEquals(6, countIncludes(root, "tag"), "tag override count changed");
        assertEquals(240, countIncludes(root, "stack"), "exact-stack bank ticket count changed");
    }

    @Test
    void benchmarkFilesStayPresent() {
        assertTrue(Files.isRegularFile(SHOP_FILE), () -> "Missing benchmark shop file: " + SHOP_FILE);
        assertTrue(Files.isRegularFile(BANK_FILE), () -> "Missing benchmark bank file: " + BANK_FILE);
    }

    private static JsonObject readJsonObject(Path path) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path));
            return root.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Failed to parse benchmark config: " + path, exception);
        }
    }

    private static int countOffers(JsonObject root) {
        int count = 0;
        for (JsonElement categoryElement : root.getAsJsonArray("categories")) {
            JsonArray offers = categoryElement.getAsJsonObject().getAsJsonArray("offers");
            count += offers.size();
        }
        return count;
    }

    private static int countIncludes(JsonObject root, String fieldName) {
        int count = 0;
        for (JsonElement categoryElement : root.getAsJsonArray("categories")) {
            for (JsonElement offerElement : categoryElement.getAsJsonObject().getAsJsonArray("offers")) {
                JsonArray include = offerElement.getAsJsonObject()
                        .getAsJsonObject("match")
                        .getAsJsonArray("include");
                for (JsonElement includeElement : include) {
                    if (includeElement.getAsJsonObject().has(fieldName)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int countOffersWithField(JsonObject root, String fieldName) {
        int count = 0;
        for (JsonElement categoryElement : root.getAsJsonArray("categories")) {
            for (JsonElement offerElement : categoryElement.getAsJsonObject().getAsJsonArray("offers")) {
                if (offerElement.getAsJsonObject().has(fieldName)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countUniqueShopOfferIds(JsonObject root) {
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (JsonElement categoryElement : root.getAsJsonArray("categories")) {
            for (JsonElement offerElement : categoryElement.getAsJsonObject().getAsJsonArray("offers")) {
                ids.add(offerElement.getAsJsonObject().get("id").getAsString());
            }
        }
        return ids.size();
    }
}
