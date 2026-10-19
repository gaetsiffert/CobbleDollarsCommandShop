package fr.cobbledollars.commandshops.audit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Exact JSON comparison: a missing member differs from null and large integers never pass through double. */
final class AuditConfigurationDiff {
    private AuditConfigurationDiff() { }

    static List<Change> between(Map<String, JsonElement> before, Map<String, JsonElement> after) {
        List<Change> changes = new ArrayList<>();
        TreeSet<String> files = new TreeSet<>(before.keySet());
        files.addAll(after.keySet());
        for (String file : files) compare(file, "", before.get(file), after.get(file), changes);
        return List.copyOf(changes);
    }

    private static void compare(String file, String path, JsonElement before, JsonElement after, List<Change> changes) {
        if (before != null && after != null && before.isJsonObject() && after.isJsonObject()) {
            JsonObject left = before.getAsJsonObject();
            JsonObject right = after.getAsJsonObject();
            TreeSet<String> members = new TreeSet<>(left.keySet());
            members.addAll(right.keySet());
            for (String member : members) {
                compare(file, path + "/" + member.replace("~", "~0").replace("/", "~1"), left.get(member), right.get(member), changes);
            }
        } else if (before != null && after != null && before.isJsonArray() && after.isJsonArray()) {
            int leftSize = before.getAsJsonArray().size();
            int rightSize = after.getAsJsonArray().size();
            for (int index = 0; index < Math.max(leftSize, rightSize); index++) {
                compare(file, path + "/" + index, index < leftSize ? before.getAsJsonArray().get(index) : null,
                        index < rightSize ? after.getAsJsonArray().get(index) : null, changes);
            }
        } else if (before == null || after == null || !before.toString().equals(after.toString())) {
            if (before != null || after != null) changes.add(new Change(file, path, before, after));
        }
    }

    record Change(String file, String path, JsonElement before, JsonElement after) {
        String kind() { return before == null ? "added" : after == null ? "removed" : "changed"; }
    }
}
