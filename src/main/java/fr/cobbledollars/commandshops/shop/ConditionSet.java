package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public static final ConditionSet NONE = new ConditionSet(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    private final List<String> playerTagsAll;
    private final List<String> playerTagsAny;
    private final List<String> playerTagsNone;
    private final List<ResourceLocation> advancementsAll;
    private final List<ResourceLocation> advancementsAny;
    private final List<ResourceLocation> dimensionsAny;
    private final List<TimeRangeCondition> timeRangesAny;
    private final List<TimeWindowMath.TimeWindow> timeWindows;
    private final List<ScoreCondition> scoresAll;
    private final boolean empty;
    private final boolean hasPlayerStateConditions;
    private final boolean hasDimensionConditions;
    private final boolean hasTimeConditions;

    public ConditionSet(
            List<String> playerTagsAll,
            List<String> playerTagsAny,
            List<String> playerTagsNone,
            List<ResourceLocation> advancementsAll,
            List<ResourceLocation> advancementsAny,
            List<ResourceLocation> dimensionsAny,
            List<TimeRangeCondition> timeRangesAny,
            List<ScoreCondition> scoresAll
    ) {
        this.playerTagsAll = List.copyOf(playerTagsAll);
        this.playerTagsAny = List.copyOf(playerTagsAny);
        this.playerTagsNone = List.copyOf(playerTagsNone);
        this.advancementsAll = List.copyOf(advancementsAll);
        this.advancementsAny = List.copyOf(advancementsAny);
        this.dimensionsAny = List.copyOf(dimensionsAny);
        this.timeRangesAny = List.copyOf(timeRangesAny);
        this.timeWindows = toTimeWindows(this.timeRangesAny);
        this.scoresAll = List.copyOf(scoresAll);
        this.hasPlayerStateConditions = !this.playerTagsAll.isEmpty()
                || !this.playerTagsAny.isEmpty()
                || !this.playerTagsNone.isEmpty()
                || !this.advancementsAll.isEmpty()
                || !this.advancementsAny.isEmpty()
                || !this.scoresAll.isEmpty();
        this.hasDimensionConditions = !this.dimensionsAny.isEmpty();
        this.hasTimeConditions = !this.timeRangesAny.isEmpty();
        this.empty = !hasPlayerStateConditions && !hasDimensionConditions && !hasTimeConditions;
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
        List<TimeRangeCondition> timeRangesAny = readTimeRangeConditions(conditionsObject, "time_ranges_any", context);
        List<ScoreCondition> scoresAll = readScoreConditions(conditionsObject, "scores_all", context);

        if (playerTagsAll.isEmpty()
                && playerTagsAny.isEmpty()
                && playerTagsNone.isEmpty()
                && advancementsAll.isEmpty()
                && advancementsAny.isEmpty()
                && dimensionsAny.isEmpty()
                && timeRangesAny.isEmpty()
                && scoresAll.isEmpty()) {
            return NONE;
        }
        return new ConditionSet(playerTagsAll, playerTagsAny, playerTagsNone, advancementsAll, advancementsAny, dimensionsAny, timeRangesAny, scoresAll);
    }

    public boolean isEmpty() {
        return empty;
    }

    public boolean hasPlayerStateConditions() {
        return hasPlayerStateConditions;
    }

    public boolean hasDimensionConditions() {
        return hasDimensionConditions;
    }

    public boolean hasTimeConditions() {
        return hasTimeConditions;
    }

    public List<String> playerTagsAll() {
        return playerTagsAll;
    }

    public List<String> playerTagsAny() {
        return playerTagsAny;
    }

    public List<String> playerTagsNone() {
        return playerTagsNone;
    }

    public List<ResourceLocation> advancementsAll() {
        return advancementsAll;
    }

    public List<ResourceLocation> advancementsAny() {
        return advancementsAny;
    }

    public List<ResourceLocation> dimensionsAny() {
        return dimensionsAny;
    }

    public List<TimeRangeCondition> timeRangesAny() {
        return timeRangesAny;
    }

    public List<ScoreCondition> scoresAll() {
        return scoresAll;
    }

    public long nextTimeBoundaryDelayTicks(long timeOfDay) {
        return TimeWindowMath.nextBoundaryDelayTicks(timeOfDay, timeWindows);
    }

    public boolean test(ServerPlayer player) {
        return test(ConditionContext.capture(player));
    }

    public boolean test(ConditionContext context) {
        if (isEmpty()) {
            return true;
        }

        Set<String> playerTags = context.playerTags();
        if (!playerTags.containsAll(playerTagsAll)) {
            return false;
        }
        if (!playerTagsAny.isEmpty()) {
            boolean matched = false;
            for (String requiredTag : playerTagsAny) {
                if (playerTags.contains(requiredTag)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        for (String forbiddenTag : playerTagsNone) {
            if (playerTags.contains(forbiddenTag)) {
                return false;
            }
        }

        for (ResourceLocation advancementId : advancementsAll) {
            if (!context.hasAdvancement(advancementId)) {
                return false;
            }
        }
        if (!advancementsAny.isEmpty()) {
            boolean matched = false;
            for (ResourceLocation advancementId : advancementsAny) {
                if (context.hasAdvancement(advancementId)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }

        if (!dimensionsAny.isEmpty()) {
            ResourceLocation currentDimension = context.dimensionId();
            boolean matched = false;
            for (ResourceLocation dimensionId : dimensionsAny) {
                if (currentDimension.equals(dimensionId)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }

        if (!timeRangesAny.isEmpty()) {
            long timeOfDay = context.timeOfDay();
            boolean matched = false;
            for (TimeRangeCondition range : timeRangesAny) {
                if (range.test(timeOfDay)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }

        for (ScoreCondition scoreCondition : scoresAll) {
            if (!scoreCondition.test(context)) {
                return false;
            }
        }

        return true;
    }

    private static List<TimeWindowMath.TimeWindow> toTimeWindows(List<TimeRangeCondition> timeRanges) {
        if (timeRanges.isEmpty()) {
            return List.of();
        }

        ArrayList<TimeWindowMath.TimeWindow> windows = new ArrayList<>(timeRanges.size());
        for (TimeRangeCondition timeRange : timeRanges) {
            windows.add(new TimeWindowMath.TimeWindow(timeRange.startTick(), timeRange.endTick()));
        }
        return List.copyOf(windows);
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

    private static List<TimeRangeCondition> readTimeRangeConditions(JsonObject object, String key, String context) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IOException("Field '" + key + "' in " + context + " must be an array.");
        }

        JsonArray array = element.getAsJsonArray();
        List<TimeRangeCondition> timeRanges = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement child = array.get(index);
            if (!child.isJsonObject()) {
                throw new IOException("Entry #" + index + " in '" + key + "' for " + context + " must be an object.");
            }
            JsonObject rangeObject = child.getAsJsonObject();
            String rangeContext = context + ", " + key + "[" + index + "]";
            int startTick = ConfigParsing.readInt(rangeObject, "start_tick", -1, rangeContext);
            int endTick = ConfigParsing.readInt(rangeObject, "end_tick", -1, rangeContext);
            if (startTick < 0 || startTick > 23999) {
                throw new IOException("Field 'start_tick' in " + rangeContext + " must be between 0 and 23999.");
            }
            if (endTick < 0 || endTick > 23999) {
                throw new IOException("Field 'end_tick' in " + rangeContext + " must be between 0 and 23999.");
            }
            if (startTick == endTick) {
                throw new IOException("Fields 'start_tick' and 'end_tick' in " + rangeContext + " must be different.");
            }
            timeRanges.add(new TimeRangeCondition(startTick, endTick));
        }
        return List.copyOf(timeRanges);
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

    public static void writeOptional(JsonObject target, ConditionSet conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }

        JsonObject object = new JsonObject();
        writeStringArray(object, "player_tags_all", conditions.playerTagsAll());
        writeStringArray(object, "player_tags_any", conditions.playerTagsAny());
        writeStringArray(object, "player_tags_none", conditions.playerTagsNone());
        writeResourceLocationArray(object, "advancements_all", conditions.advancementsAll());
        writeResourceLocationArray(object, "advancements_any", conditions.advancementsAny());
        writeResourceLocationArray(object, "dimensions_any", conditions.dimensionsAny());
        writeTimeRanges(object, "time_ranges_any", conditions.timeRangesAny());
        writeScoreConditions(object, "scores_all", conditions.scoresAll());
        if (object.size() > 0) {
            target.add("conditions", object);
        }
    }

    private static void writeStringArray(JsonObject target, String key, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        target.add(key, array);
    }

    private static void writeResourceLocationArray(JsonObject target, String key, List<ResourceLocation> values) {
        if (values.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (ResourceLocation value : values) {
            array.add(value.toString());
        }
        target.add(key, array);
    }

    private static void writeTimeRanges(JsonObject target, String key, List<TimeRangeCondition> ranges) {
        if (ranges.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (TimeRangeCondition range : ranges) {
            JsonObject rangeObject = new JsonObject();
            rangeObject.addProperty("start_tick", range.startTick());
            rangeObject.addProperty("end_tick", range.endTick());
            array.add(rangeObject);
        }
        target.add(key, array);
    }

    private static void writeScoreConditions(JsonObject target, String key, List<ScoreCondition> scoreConditions) {
        if (scoreConditions.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (ScoreCondition scoreCondition : scoreConditions) {
            JsonObject scoreObject = new JsonObject();
            scoreObject.addProperty("objective", scoreCondition.objective());
            if (scoreCondition.min() != null) {
                scoreObject.addProperty("min", scoreCondition.min());
            }
            if (scoreCondition.max() != null) {
                scoreObject.addProperty("max", scoreCondition.max());
            }
            if (scoreCondition.equals() != null) {
                scoreObject.addProperty("equals", scoreCondition.equals());
            }
            array.add(scoreObject);
        }
        target.add(key, array);
    }

    public record ScoreCondition(String objective, Integer min, Integer max, Integer equals) {
        public boolean test(ServerPlayer player) {
            return test(ConditionContext.capture(player));
        }

        public boolean test(ConditionContext context) {
            Integer value = context.score(objective);
            if (value == null) {
                return false;
            }
            if (equals != null && value.intValue() != equals.intValue()) {
                return false;
            }
            if (min != null && value.intValue() < min.intValue()) {
                return false;
            }
            if (max != null && value.intValue() > max.intValue()) {
                return false;
            }
            return true;
        }
    }

    public record TimeRangeCondition(int startTick, int endTick) {
        public boolean test(long timeOfDay) {
            return TimeWindowMath.contains(timeOfDay, startTick, endTick);
        }
    }

    public static final class ConditionContext {
        private final ServerPlayer player;
        private final Set<String> playerTags;
        private final ResourceLocation dimensionId;
        private final long timeOfDay;
        private final Map<ResourceLocation, Boolean> advancementsById = new HashMap<>();
        private final Map<String, Integer> scoresByObjective = new HashMap<>();

        private ConditionContext(ServerPlayer player) {
            this.player = player;
            this.playerTags = player.getTags();
            this.dimensionId = player.level().dimension().location();
            this.timeOfDay = Math.floorMod(player.level().getDayTime(), 24000L);
        }

        public static ConditionContext capture(ServerPlayer player) {
            return new ConditionContext(player);
        }

        private Set<String> playerTags() {
            return playerTags;
        }

        private ResourceLocation dimensionId() {
            return dimensionId;
        }

        private long timeOfDay() {
            return timeOfDay;
        }

        private boolean hasAdvancement(ResourceLocation advancementId) {
            Boolean cached = advancementsById.get(advancementId);
            if (cached != null) {
                return cached.booleanValue();
            }

            AdvancementHolder advancement = player.getServer().getAdvancements().get(advancementId);
            boolean completed = advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
            advancementsById.put(advancementId, completed);
            return completed;
        }

        private Integer score(String objectiveName) {
            if (scoresByObjective.containsKey(objectiveName)) {
                return scoresByObjective.get(objectiveName);
            }

            Scoreboard scoreboard = player.getScoreboard();
            Objective scoreboardObjective = scoreboard.getObjective(objectiveName);
            Integer value = null;
            if (scoreboardObjective != null) {
                ReadOnlyScoreInfo scoreInfo = scoreboard.getPlayerScoreInfo(player, scoreboardObjective);
                if (scoreInfo != null) {
                    value = scoreInfo.value();
                }
            }
            scoresByObjective.put(objectiveName, value);
            return value;
        }
    }
}
