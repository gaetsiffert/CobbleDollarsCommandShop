package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionSetTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");
    private static final ResourceLocation ROOT = ResourceLocation.parse("minecraft:story/root");
    private static final ResourceLocation DIAMOND = ResourceLocation.parse("minecraft:story/mine_diamond");

    @Test
    void firstFailureFollowsRuleOrderRatherThanFamilyOrder() throws IOException {
        ConditionSet conditions = read("""
                [
                  {"scores_all":[{"objective":"badges","min":4}],"deny_message":"Earn four badges."},
                  {"player_tags_all":["vip"],"deny_message":"Become a VIP."}
                ]
                """);
        ConditionSet.ConditionContext noAccess = context(Set.of(), 6_000, Map.of(), Map.of());
        assertEquals("scores_all", conditions.firstFailure(noAccess).type());
        assertEquals("Earn four badges.", conditions.firstFailure(noAccess).denyMessage());

        ConditionSet.ConditionContext badgesOnly = context(Set.of(), 6_000, Map.of(), Map.of("badges", 4));
        assertEquals("Become a VIP.", conditions.firstFailure(badgesOnly).denyMessage());
        assertFalse(conditions.test(badgesOnly));
        ConditionSet.ConditionContext allowed = context(Set.of("vip"), 6_000, Map.of(), Map.of("badges", 4));
        assertNull(conditions.firstFailure(allowed));
        assertTrue(conditions.test(allowed));
    }

    @Test
    void separateRulesOfSameFamilyHaveSeparateMessagesAndAreBothRequired() throws IOException {
        ConditionSet conditions = read("""
                [
                  {"player_tags_all":["vip"],"deny_message":"VIP required."},
                  {"player_tags_all":["member"],"deny_message":"Membership required."}
                ]
                """);
        assertEquals("Membership required.", conditions.firstFailure(context(Set.of("vip"), 0, Map.of(), Map.of())).denyMessage());
        assertTrue(conditions.test(context(Set.of("vip", "member"), 0, Map.of(), Map.of())));
        assertEquals(List.of("vip", "member"), conditions.playerTagsAll());
    }

    @Test
    void anyAlternativesStayGroupedAndMultipleAnyRulesAreAllRequired() throws IOException {
        ConditionSet conditions = read("""
                [
                  {"player_tags_any":["vip","staff"],"deny_message":"VIP or staff required."},
                  {"player_tags_any":["member","guest"],"deny_message":"Member or guest required."},
                  {"advancements_any":["minecraft:story/root","minecraft:story/mine_diamond"]},
                  {"dimensions_any":["minecraft:overworld","minecraft:the_nether"]}
                ]
                """);
        assertEquals("Member or guest required.", conditions.firstFailure(context(Set.of("staff"), 0, Map.of(), Map.of())).denyMessage());
        assertTrue(conditions.test(context(Set.of("staff", "guest"), 0, Map.of(DIAMOND, true), Map.of())));
        ConditionSet.ConditionContext nether = new ConditionSet.ConditionContext(
                Set.of("staff", "guest"), NETHER, 0, Map.of(ROOT, true), Map.of());
        assertTrue(conditions.test(nether));
    }

    @Test
    void allAndNoneAndScoreBoundsKeepTheirExistingMeaning() throws IOException {
        ConditionSet conditions = read("""
                [
                  {"player_tags_none":["banned","suspended"]},
                  {"advancements_all":["minecraft:story/root","minecraft:story/mine_diamond"]},
                  {"scores_all":[{"objective":"level","min":4,"max":8},{"objective":"stage","equals":3}]}
                ]
                """);
        assertEquals("player_tags_none", conditions.firstFailure(context(Set.of("suspended"), 0, Map.of(), Map.of())).type());
        assertEquals("advancements_all", conditions.firstFailure(context(Set.of(), 0, Map.of(ROOT, true), Map.of())).type());
        assertEquals("scores_all", conditions.firstFailure(context(Set.of(), 0, Map.of(ROOT, true, DIAMOND, true), Map.of("level", 9, "stage", 3))).type());
        assertTrue(conditions.test(context(Set.of(), 0, Map.of(ROOT, true, DIAMOND, true), Map.of("level", 4, "stage", 3))));
    }

    @Test
    void timeAnyGroupsRemainSeparateAndScheduleEveryGroupBoundary() throws IOException {
        ConditionSet conditions = read("""
                [
                  {"time_ranges_any":[{"start_tick":1000,"end_tick":6000},{"start_tick":13000,"end_tick":2000}],"deny_message":"First window."},
                  {"time_ranges_any":[{"start_tick":1500,"end_tick":4000}],"deny_message":"Second window."}
                ]
                """);
        assertEquals("Second window.", conditions.firstFailure(context(Set.of(), 1_000, Map.of(), Map.of())).denyMessage());
        assertEquals("First window.", conditions.firstFailure(context(Set.of(), 8_000, Map.of(), Map.of())).denyMessage());
        assertTrue(conditions.test(context(Set.of(), 1_750, Map.of(), Map.of())));
        assertEquals(500, conditions.nextTimeBoundaryDelayTicks(1_000));
        assertEquals(250, conditions.nextTimeBoundaryDelayTicks(1_750));
        assertEquals(3, conditions.timeRangesAny().size());
        assertTrue(conditions.hasTimeConditions());
        assertFalse(conditions.hasDimensionConditions());
        assertFalse(conditions.hasPlayerStateConditions());
    }

    @Test
    void dependencyFlagsCoverEveryRuleAndEmptyListsAreAllowed() throws IOException {
        ConditionSet mixed = read("""
                [
                  {"dimensions_any":["minecraft:overworld"]},
                  {"time_ranges_any":[{"start_tick":1000,"end_tick":2000}]},
                  {"scores_all":[{"objective":"badges","equals":4}]}
                ]
                """);
        assertTrue(mixed.hasPlayerStateConditions());
        assertTrue(mixed.hasDimensionConditions());
        assertTrue(mixed.hasTimeConditions());
        assertFalse(mixed.isEmpty());
        ConditionSet empty = read("[]");
        assertTrue(empty.isEmpty());
        assertTrue(empty.test(context(Set.of(), 0, Map.of(), Map.of())));
        assertEquals(Long.MAX_VALUE, empty.nextTimeBoundaryDelayTicks(0));
        assertEquals(JsonParser.parseString("[]"), write(empty).get("conditions"));
    }

    @Test
    void legacyObjectKeepsFixedOrderAndRoundTripsAsObject() throws IOException {
        String legacy = """
                {"scores_all":[{"objective":"badges","min":4}],"player_tags_all":["vip"],"player_tags_any":["member","staff"]}
                """;
        ConditionSet conditions = read(legacy);
        assertEquals("player_tags_all", conditions.firstFailure(context(Set.of(), 0, Map.of(), Map.of())).type());
        assertNull(conditions.firstFailure(context(Set.of("vip", "staff"), 0, Map.of(), Map.of("badges", 4))));
        assertEquals(JsonParser.parseString(legacy), write(conditions).get("conditions"));
        assertTrue(write(ConditionSet.NONE).isEmpty());
    }

    @Test
    void orderedRulesRoundTripWithoutCollapsingRepeatedGroups() throws IOException {
        String ordered = """
                [
                  {"dimensions_any":["minecraft:overworld"],"deny_message":"Stay in the overworld."},
                  {"player_tags_any":["vip","staff"]},
                  {"player_tags_any":["member","guest"],"deny_message":"Membership required."},
                  {"scores_all":[{"objective":"badges","min":4,"max":8}]}
                ]
                """;
        JsonObject written = write(read(ordered));
        assertEquals(JsonParser.parseString(ordered), written.get("conditions"));
        assertEquals(written, write(ConditionSet.readOptional(written, "round trip")));
    }

    @Test
    void rejectsAmbiguousEmptyUnknownAndMalformedRules() {
        List<String> invalid = List.of(
                "[null]", "[\"vip\"]", "[{}]", "[{\"deny_message\":\"Blocked\"}]",
                "[{\"player_tags_all\":[\"vip\"],\"scores_all\":[{\"objective\":\"x\",\"min\":1}]}]",
                "[{\"player_tags_all\":[\"vip\"],\"unknown\":true}]",
                "[{\"player_tags_all\":[]}]", "[{\"player_tags_all\":null}]", "[{\"player_tags_all\":\"vip\"}]",
                "[{\"player_tags_all\":[1]}]", "[{\"player_tags_all\":[\"\"]}]",
                "[{\"player_tags_all\":[\"vip\"],\"deny_message\":123}]",
                "[{\"time_ranges_any\":[{\"start_tick\":0,\"end_tick\":100,\"unexpected\":true}]}]",
                "[{\"time_ranges_any\":[{\"start_tick\":0,\"end_tick\":0}]}]",
                "[{\"scores_all\":[{\"objective\":\"x\",\"min\":1,\"unexpected\":true}]}]",
                "[{\"scores_all\":[{\"objective\":\"x\"}]}]",
                "[{\"dimensions_any\":[\"invalid location\"]}]"
        );
        for (String json : invalid) {
            assertThrows(IOException.class, () -> read(json), json);
        }
    }

    @Test
    void failureMessageUsesCustomThenLegacyThenTranslatedFallback() {
        ConditionSet.Failure custom = new ConditionSet.Failure("scores_all", "Earn four badges.");
        assertEquals("Earn four badges.", custom.message("Legacy message").getString());
        ConditionSet.Failure fallback = new ConditionSet.Failure("scores_all", " ");
        assertEquals("Legacy message", fallback.message("Legacy message").getString());
        TranslatableContents translated = assertInstanceOf(TranslatableContents.class, fallback.message("").getContents());
        assertEquals("cobbledollarscommandshops.feedback.condition_denied.scores_all", translated.getKey());
        assertEquals("You do not meet a required scoreboard condition.", translated.getFallback());
    }

    private static ConditionSet read(String json) throws IOException {
        JsonObject root = new JsonObject();
        root.add("conditions", JsonParser.parseString(json));
        return ConditionSet.readOptional(root, "test");
    }

    private static JsonObject write(ConditionSet conditions) {
        JsonObject root = new JsonObject();
        ConditionSet.writeOptional(root, conditions);
        return root;
    }

    private static ConditionSet.ConditionContext context(Set<String> tags, long time,
                                                        Map<ResourceLocation, Boolean> advancements,
                                                        Map<String, Integer> scores) {
        return new ConditionSet.ConditionContext(tags, OVERWORLD, time, advancements, scores);
    }
}
