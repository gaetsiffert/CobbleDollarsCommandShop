package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;

public final class ConditionSet {
    public static final ConditionSet NONE = new ConditionSet(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    private final List<String> playerTagsAll;
    private final List<String> playerTagsAny;
    private final List<String> playerTagsNone;
    private final List<ResourceLocation> advancementsAll;
    private final List<ResourceLocation> advancementsAny;
    private final List<ResourceLocation> dimensionsAny;
    private final List<ScoreCondition> scoresAll;

    public ConditionSet(
            List<String> playerTagsAll,
            List<String> playerTagsAny,
            List<String> playerTagsNone,
            List<ResourceLocation> advancementsAll,
            List<ResourceLocation> advancementsAny,
            List<ResourceLocation> dimensionsAny,
            List<ScoreCondition> scoresAll
    ) {
        this.playerTagsAll = List.copyOf(playerTagsAll);
        this.playerTagsAny = List.copyOf(playerTagsAny);
        this.playerTagsNone = List.copyOf(playerTagsNone);
        this.advancementsAll = List.copyOf(advancementsAll);
        this.advancementsAny = List.copyOf(advancementsAny);
        this.dimensionsAny = List.copyOf(dimensionsAny);
        this.scoresAll = List.copyOf(scoresAll);
    }

    public static ConditionSet readOptional(JsonObject object, String context) throws IOException {
        JsonElement element = object.get("conditions");
        if (element == null || element.isJsonNull()) {
            return NONE;
        }
        if (!element.isJsonObject()) {
            throw new IOException("Field 'conditions' in " + context + " must be an object.");
        }

        JsonObject conditionsObject = element.getAsJsonObject();
        List<String> playerTagsAll = readStringArray(conditionsObject, "player_tags_all", context);
        List<String> playerTagsAny = readStringArray(conditionsObject, "player_tags_any", context);
        List<String> playerTagsNone = readStringArray(conditionsObject, "player_tags_none", context);
        List<ResourceLocation> advancementsAll = readResourceLocations(conditionsObject, "advancements_all", context);
        List<ResourceLocation> advancementsAny = readResourceLocations(conditionsObject, "advancements_any", context);
        List<ResourceLocation> dimensionsAny = readResourceLocations(conditionsObject, "dimensions_any", context);
        List<ScoreCondition> scoresAll = readScoreConditions(conditionsObject, "scores_all", context);

        if (playerTagsAll.isEmpty()
                && playerTagsAny.isEmpty()
                && playerTagsNone.isEmpty()
                && advancementsAll.isEmpty()
                && advancementsAny.isEmpty()
                && dimensionsAny.isEmpty()
                && scoresAll.isEmpty()) {
            return NONE;
        }
        return new ConditionSet(playerTagsAll, playerTagsAny, playerTagsNone, advancementsAll, advancementsAny, dimensionsAny, scoresAll);
    }

    public boolean isEmpty() {
        return this == NONE || (playerTagsAll.isEmpty()
                && playerTagsAny.isEmpty()
                && playerTagsNone.isEmpty()
                && advancementsAll.isEmpty()
                && advancementsAny.isEmpty()
                && dimensionsAny.isEmpty()
                && scoresAll.isEmpty());
    }

    public boolean test(ServerPlayer player) {
        if (isEmpty()) {
            return true;
        }

        Set<String> playerTags = player.getTags();
        if (!playerTags.containsAll(playerTagsAll)) {
            return false;
        }
        if (!playerTagsAny.isEmpty() && playerTagsAny.stream().noneMatch(playerTags::contains)) {
            return false;
        }
        if (playerTagsNone.stream().anyMatch(playerTags::contains)) {
            return false;
        }

        for (ResourceLocation advancementId : advancementsAll) {
            if (!hasAdvancement(player, advancementId)) {
                return false;
            }
        }
        if (!advancementsAny.isEmpty() && advancementsAny.stream().noneMatch(advancementId -> hasAdvancement(player, advancementId))) {
            return false;
        }

        if (!dimensionsAny.isEmpty()) {
            ResourceLocation currentDimension = player.level().dimension().location();
            if (dimensionsAny.stream().noneMatch(currentDimension::equals)) {
                return false;
            }
        }

        for (ScoreCondition scoreCondition : scoresAll) {
            if (!scoreCondition.test(player)) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasAdvancement(ServerPlayer player, ResourceLocation advancementId) {
        AdvancementHolder advancement = player.getServer().getAdvancements().get(advancementId);
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static List<String> readStringArray(JsonObject object, String key, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IOException("Field '" + key + "' in " + context + " must be an array of strings.");
        }

        JsonArray array = element.getAsJsonArray();
        List<String> values = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement child = array.get(index);
            if (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isString()) {
                throw new IOException("Field '" + key + "' in " + context + " must contain only strings.");
            }
            String value = child.getAsString();
            if (value.isBlank()) {
                throw new IOException("Field '" + key + "' in " + context + " cannot contain empty strings.");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static List<ResourceLocation> readResourceLocations(JsonObject object, String key, String context) throws IOException {
        List<String> values = readStringArray(object, key, context);
        List<ResourceLocation> resourceLocations = new ArrayList<>(values.size());
        for (String value : values) {
            resourceLocations.add(ConfigParsing.readResourceLocation(value, key, context));
        }
        return List.copyOf(resourceLocations);
    }

    private static List<ScoreCondition> readScoreConditions(JsonObject object, String key, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IOException("Field '" + key + "' in " + context + " must be an array.");
        }

        JsonArray array = element.getAsJsonArray();
        List<ScoreCondition> scoreConditions = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement child = array.get(index);
            if (!child.isJsonObject()) {
                throw new IOException("Entry #" + index + " in '" + key + "' for " + context + " must be an object.");
            }
            JsonObject scoreObject = child.getAsJsonObject();
            String objective = ConfigParsing.readRequiredString(scoreObject, "objective", context + ", " + key + "[" + index + "]");
            Integer min = readOptionalInteger(scoreObject, "min", context + ", " + key + "[" + index + "]");
            Integer max = readOptionalInteger(scoreObject, "max", context + ", " + key + "[" + index + "]");
            Integer equals = readOptionalInteger(scoreObject, "equals", context + ", " + key + "[" + index + "]");
            if (min == null && max == null && equals == null) {
                throw new IOException("Score condition #" + index + " in " + context + " must define at least one of 'min', 'max', or 'equals'.");
            }
            scoreConditions.add(new ScoreCondition(objective, min, max, equals));
        }
        return List.copyOf(scoreConditions);
    }

    private static Integer readOptionalInteger(JsonObject object, String key, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive()) {
            throw new IOException("Field '" + key + "' in " + context + " must be an integer.");
        }
        try {
            return Integer.valueOf(element.getAsString());
        } catch (NumberFormatException exception) {
            throw new IOException("Field '" + key + "' in " + context + " is not a valid integer.", exception);
        }
    }

    public record ScoreCondition(String objective, Integer min, Integer max, Integer equals) {
        public boolean test(ServerPlayer player) {
            Scoreboard scoreboard = player.getScoreboard();
            Objective scoreboardObjective = scoreboard.getObjective(objective);
            if (scoreboardObjective == null) {
                return false;
            }

            ReadOnlyScoreInfo scoreInfo = scoreboard.getPlayerScoreInfo(player, scoreboardObjective);
            if (scoreInfo == null) {
                return false;
            }

            int value = scoreInfo.value();
            if (equals != null && value != equals.intValue()) {
                return false;
            }
            if (min != null && value < min.intValue()) {
                return false;
            }
            if (max != null && value > max.intValue()) {
                return false;
            }
            return true;
        }
    }
}
