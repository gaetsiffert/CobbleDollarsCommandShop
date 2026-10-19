package fr.cobbledollars.commandshops.admin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.admin.AdminMessages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Restricts editor access to this mod's configuration and commits validated edits together. */
final class AdminConfigStore {
    static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    static final int MAX_FILES = 1024;
    private static final Set<String> REQUIRED_FILES = Set.of("audit.json", "commands.json", "feedback.json", "global_bank.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path root;

    AdminConfigStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    synchronized String snapshot() throws IOException {
        return encodeSnapshot(readState());
    }

    synchronized String save(String snapshotJson, Validator validator, Activation activation) throws IOException {
        return save(snapshotJson, validator, activation, new ChangeObserver() { });
    }

    synchronized String save(String snapshotJson, Validator validator, Activation activation, ChangeObserver observer) throws IOException {
        JsonObject submitted = parseObject(snapshotJson);
        JsonElement revision = submitted.get("revision");
        if (revision == null || !revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isString()) {
            throw AdminMessages.io("store.revision_missing", "The configuration revision is missing. Reload the editor.");
        }
        JsonElement filesElement = submitted.get("files");
        if (filesElement == null || !filesElement.isJsonObject()) {
            throw AdminMessages.io("store.files_required", "The configuration snapshot must contain a files object.");
        }
        State before = readState();
        requireCurrentRevision(revision.getAsString(), before.revision());
        TreeMap<String, JsonElement> proposed = readSubmittedFiles(filesElement.getAsJsonObject());
        validator.validate(Map.copyOf(proposed));

        TreeMap<String, byte[]> desiredBytes = new TreeMap<>();
        for (var entry : proposed.entrySet()) {
            // Keep untouched files byte-for-byte, including their existing formatting.
            byte[] previous = before.bytes().get(entry.getKey());
            // Gson's numeric equality rounds large values to double; prices use arbitrary-precision integers.
            desiredBytes.put(entry.getKey(), previous != null && entry.getValue().toString().equals(before.files().get(entry.getKey()).toString())
                    ? previous : (GSON.toJson(entry.getValue()) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        }
        validateByteLimits(desiredBytes);
        Set<String> removedShops = shopIds(before.bytes().keySet());
        removedShops.removeAll(shopIds(desiredBytes.keySet()));
        for (String shopId : removedShops) {
            requireRemovableShopDirectory(shopId);
        }

        Files.createDirectories(root);
        assertSafe(root);
        Path staging = Files.createTempDirectory(root, ".admin-config-");
        Map<String, Path> stagedFiles = new LinkedHashMap<>();
        boolean mutationStarted = false;
        boolean prepared = false;
        try {
            int index = 0;
            for (var entry : desiredBytes.entrySet()) {
                if (!Arrays.equals(entry.getValue(), before.bytes().get(entry.getKey()))) {
                    Path staged = staging.resolve(index++ + ".pending");
                    Files.write(staged, entry.getValue());
                    stagedFiles.put(entry.getKey(), staged);
                }
            }
            requireCurrentRevision(before.revision(), readState().revision());
            observer.prepare(Map.copyOf(before.files()), Map.copyOf(proposed));
            prepared = true;
            requireCurrentRevision(before.revision(), readState().revision());
            mutationStarted = true;
            for (var entry : stagedFiles.entrySet()) {
                Path destination = resolve(entry.getKey());
                Files.createDirectories(destination.getParent());
                assertSafe(destination);
                replace(entry.getValue(), destination);
            }
            for (String path : before.bytes().keySet()) {
                if (!desiredBytes.containsKey(path)) {
                    Files.delete(resolve(path));
                }
            }
            for (String shopId : removedShops) {
                Files.delete(resolve("shops/" + shopId + "/shop.json").getParent());
            }
            activation.activate();
            String updated = encodeSnapshot(readState());
            observer.complete("applied");
            return updated;
        } catch (Exception exception) {
            IOException failure = exception instanceof IOException io ? io : new IOException(exception.getMessage(), exception);
            if (mutationStarted) {
                try {
                    restore(before.bytes(), desiredBytes.keySet(), staging);
                    activation.activate();
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    IOException restoreFailure = AdminMessages.io("store.restore_failed",
                            "Saving failed and restoring the previous configuration failed: %s; %s",
                            failure.getMessage(), rollbackFailure.getMessage());
                    restoreFailure.initCause(failure);
                    if (prepared) observer.complete("rollback_failed");
                    throw restoreFailure;
                }
            }
            if (prepared) observer.complete(mutationStarted ? "rolled_back" : "failed");
            throw failure;
        } finally {
            cleanupStaging(staging);
        }
    }

    private State readState() throws IOException {
        assertSafe(root);
        TreeMap<String, byte[]> bytes = new TreeMap<>();
        for (String file : REQUIRED_FILES) {
            readIfPresent(file, bytes);
        }
        Path shops = root.resolve("shops");
        assertSafe(shops);
        if (Files.exists(shops, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(shops, LinkOption.NOFOLLOW_LINKS)) {
                throw AdminMessages.io("store.shops_directory_required", "Expected the shops path to be a directory.");
            }
            try (var children = Files.list(shops)) {
                for (Path child : children.sorted().toList()) {
                    assertSafe(child);
                    if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    String id = child.getFileName().toString();
                    if (!id.matches("[A-Za-z0-9_-]+")) {
                        throw AdminMessages.io("store.invalid_shop_folder", "Invalid shop folder name: %s", id);
                    }
                    readIfPresent("shops/" + id + "/shop.json", bytes);
                    readIfPresent("shops/" + id + "/bank.json", bytes);
                    if (!bytes.containsKey("shops/" + id + "/shop.json")) {
                        throw AdminMessages.io("store.shop_file_required", "Shop folder '%s' must contain shop.json.", id);
                    }
                }
            }
        }
        validateByteLimits(bytes);
        TreeMap<String, JsonElement> files = new TreeMap<>();
        for (var entry : bytes.entrySet()) {
            try {
                JsonElement json = JsonParser.parseString(new String(entry.getValue(), StandardCharsets.UTF_8));
                validateJsonDepth(json);
                files.put(entry.getKey(), json);
            } catch (RuntimeException exception) {
                throw new IOException("Invalid JSON in " + entry.getKey() + ": " + exception.getMessage(), exception);
            }
        }
        return new State(bytes, files, revision(bytes));
    }

    private void readIfPresent(String relative, Map<String, byte[]> target) throws IOException {
        Path file = resolve(relative);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw AdminMessages.io("store.regular_file_required", "Configuration path must be a regular file: %s", relative);
        }
        long fileSize = Files.size(file);
        if (fileSize > MAX_FILE_BYTES) {
            throw AdminMessages.io("store.file_too_large", "Configuration file exceeds the 2 MiB limit: %s", relative);
        }
        if (target.size() >= MAX_FILES) {
            throw AdminMessages.io("store.too_many_files", "Too many configuration files (maximum %s).", MAX_FILES);
        }
        long currentSize = target.values().stream().mapToLong(content -> content.length).sum();
        if (currentSize + fileSize > MAX_SNAPSHOT_BYTES) {
            throw AdminMessages.io("store.snapshot_too_large", "Configuration snapshot exceeds the 8 MiB limit.");
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw AdminMessages.io("store.file_too_large", "Configuration file exceeds the 2 MiB limit: %s", relative);
            }
            target.put(relative, bytes);
        }
    }

    private TreeMap<String, JsonElement> readSubmittedFiles(JsonObject files) throws IOException {
        if (files.size() > MAX_FILES) {
            throw AdminMessages.io("store.too_many_files", "Too many configuration files (maximum %s).", MAX_FILES);
        }
        TreeMap<String, JsonElement> proposed = new TreeMap<>();
        for (var entry : files.entrySet()) {
            resolve(entry.getKey());
            if (entry.getValue() == null || entry.getValue().isJsonNull()) {
                throw AdminMessages.io("store.null_file", "Configuration file cannot be null: %s", entry.getKey());
            }
            proposed.put(entry.getKey(), entry.getValue().deepCopy());
        }
        if (!proposed.keySet().containsAll(REQUIRED_FILES)) {
            throw AdminMessages.io("store.required_files", "audit.json, commands.json, feedback.json and global_bank.json are required.");
        }
        for (String path : proposed.keySet()) {
            if (path.startsWith("shops/") && path.endsWith("/bank.json")
                    && !proposed.containsKey(path.substring(0, path.length() - "bank.json".length()) + "shop.json")) {
                throw AdminMessages.io("store.orphan_bank", "A local bank requires a shop.json in the same folder: %s", path);
            }
        }
        return proposed;
    }

    private void requireRemovableShopDirectory(String shopId) throws IOException {
        Path folder = resolve("shops/" + shopId + "/shop.json").getParent();
        try (var children = Files.list(folder)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if ((!name.equals("shop.json") && !name.equals("bank.json"))
                        || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    throw AdminMessages.io("store.shop_not_empty", "Cannot delete shop '%s': its folder contains other files.", shopId);
                }
            }
        }
    }

    private void restore(Map<String, byte[]> original, Set<String> desired, Path staging) throws IOException {
        IOException failure = null;
        Set<String> allPaths = new TreeSet<>(desired);
        allPaths.addAll(original.keySet());
        int index = 0;
        for (String path : allPaths) {
            try {
                Path destination = resolve(path);
                byte[] bytes = original.get(path);
                if (bytes == null) {
                    Files.deleteIfExists(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Path recoveryFile = staging.resolve(index++ + ".restore");
                    Files.write(recoveryFile, bytes);
                    replace(recoveryFile, destination);
                }
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        Set<String> addedShops = shopIds(desired);
        addedShops.removeAll(shopIds(original.keySet()));
        for (String id : addedShops) {
            try {
                Files.deleteIfExists(resolve("shops/" + id + "/shop.json").getParent());
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private Path resolve(String relative) throws IOException {
        if (!REQUIRED_FILES.contains(relative) && !relative.matches("shops/[A-Za-z0-9_-]+/(shop|bank)\\.json")) {
            throw AdminMessages.io("store.path_not_allowed", "This configuration path is not allowed: %s", relative);
        }
        Path resolved = root.resolve(relative).normalize();
        assertSafe(resolved);
        return resolved;
    }

    private void assertSafe(Path path) throws IOException {
        if (!path.startsWith(root)) {
            throw AdminMessages.io("store.path_outside_root", "Configuration path is outside the allowed folder.");
        }
        Path cursor = root;
        if (Files.isSymbolicLink(cursor)) {
            throw AdminMessages.io("store.symbolic_links", "Symbolic links are not allowed in the configuration editor.");
        }
        for (Path segment : root.relativize(path)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw AdminMessages.io("store.symbolic_links", "Symbolic links are not allowed in the configuration editor.");
            }
        }
    }

    private static JsonObject parseObject(String json) throws IOException {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
            throw AdminMessages.io("store.snapshot_too_large", "Configuration snapshot exceeds the 8 MiB limit.");
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            validateJsonDepth(parsed);
            if (!parsed.isJsonObject()) {
                throw AdminMessages.io("store.snapshot_object_required", "Configuration snapshot must be an object.");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON: " + exception.getMessage(), exception);
        }
    }

    private static void validateJsonDepth(JsonElement json) throws IOException {
        record Node(JsonElement value, int depth) { }
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.push(new Node(json, 0));
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (node.depth() > 64) {
                throw AdminMessages.io("store.json_too_deep", "Configuration JSON exceeds the maximum nesting depth of 64.");
            }
            if (node.value().isJsonObject()) {
                for (var child : node.value().getAsJsonObject().entrySet()) {
                    pending.push(new Node(child.getValue(), node.depth() + 1));
                }
            } else if (node.value().isJsonArray()) {
                for (JsonElement child : node.value().getAsJsonArray()) {
                    pending.push(new Node(child, node.depth() + 1));
                }
            }
        }
    }

    private static void requireCurrentRevision(String submitted, String current) throws IOException {
        if (!submitted.equals(current)) {
            throw AdminMessages.io("store.changed", "Configuration changed since the editor was opened. Reload before saving.");
        }
    }

    private static void validateByteLimits(Map<String, byte[]> files) throws IOException {
        if (files.size() > MAX_FILES) {
            throw AdminMessages.io("store.too_many_files", "Too many configuration files (maximum %s).", MAX_FILES);
        }
        long total = 0;
        for (var entry : files.entrySet()) {
            if (entry.getValue().length > MAX_FILE_BYTES) {
                throw AdminMessages.io("store.file_too_large", "Configuration file exceeds the 2 MiB limit: %s", entry.getKey());
            }
            total += entry.getValue().length;
        }
        if (total > MAX_SNAPSHOT_BYTES) {
            throw AdminMessages.io("store.snapshot_too_large", "Configuration snapshot exceeds the 8 MiB limit.");
        }
    }

    private static String encodeSnapshot(State state) throws IOException {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("revision", state.revision());
        JsonObject files = new JsonObject();
        state.files().forEach(files::add);
        snapshot.add("files", files);
        String result = GSON.toJson(snapshot);
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
            throw AdminMessages.io("store.snapshot_too_large", "Configuration snapshot exceeds the 8 MiB limit.");
        }
        return result;
    }

    private static String revision(Map<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.forEach((path, content) -> {
                digest.update(path.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Integer.toString(content.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) 0);
                digest.update(content);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static Set<String> shopIds(Set<String> paths) {
        Set<String> result = new TreeSet<>();
        for (String path : paths) {
            if (path.startsWith("shops/")) {
                result.add(path.split("/")[1]);
            }
        }
        return result;
    }

    private static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanupStaging(Path staging) {
        try {
            List<Path> children;
            try (var stream = Files.list(staging)) {
                children = stream.toList();
            }
            for (Path child : children) {
                Files.deleteIfExists(child);
            }
            Files.deleteIfExists(staging);
        } catch (IOException ignored) {
            // Staging files are never loaded as configuration; a cleanup failure must not undo a commit.
        }
    }

    interface ChangeObserver {
        default void prepare(Map<String, JsonElement> before, Map<String, JsonElement> after) throws IOException { }
        // Outcome recording must never roll back a successfully activated edit.
        default void complete(String outcome) { }
    }

    @FunctionalInterface
    interface Validator {
        void validate(Map<String, JsonElement> files) throws IOException;
    }

    @FunctionalInterface
    interface Activation {
        void activate() throws IOException;
    }

    private record State(TreeMap<String, byte[]> bytes, TreeMap<String, JsonElement> files, String revision) {
    }
}
