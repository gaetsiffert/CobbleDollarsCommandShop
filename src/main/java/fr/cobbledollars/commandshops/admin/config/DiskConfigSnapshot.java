package fr.cobbledollars.commandshops.admin.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Server-local audit baseline, independent of the editor's transfer and write restrictions.
 * Like the normal file loaders, this follows administrator-managed symbolic links and parses
 * from readers without imposing packet size, file count or JSON depth limits.
 */
final class DiskConfigSnapshot {
    private DiskConfigSnapshot() { }

    static Map<String, JsonElement> read(Path root) throws IOException {
        Map<String, JsonElement> files = new TreeMap<>();
        for (String file : new String[]{"audit.json", "commands.json", "feedback.json", "global_bank.json"}) {
            files.put(file, readJson(root.resolve(file)));
        }
        Path shops = root.resolve("shops");
        if (Files.exists(shops)) {
            try (var entries = Files.list(shops)) {
                for (Path shop : entries.sorted().toList()) {
                    if (!Files.isDirectory(shop)) continue;
                    String prefix = "shops/" + shop.getFileName() + "/";
                    Path shopFile = shop.resolve("shop.json");
                    if (!Files.isRegularFile(shopFile)) {
                        throw new IOException("Shop folder '" + shop + "' must contain a 'shop.json' file.");
                    }
                    files.put(prefix + "shop.json", readJson(shopFile));
                    Path bank = shop.resolve("bank.json");
                    if (Files.isRegularFile(bank)) files.put(prefix + "bank.json", readJson(bank));
                }
            }
        }
        return Map.copyOf(files);
    }

    private static JsonElement readJson(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON in " + file + ": " + exception.getMessage(), exception);
        }
    }
}
