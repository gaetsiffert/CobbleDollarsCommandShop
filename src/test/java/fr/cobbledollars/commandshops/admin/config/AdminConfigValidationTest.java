package fr.cobbledollars.commandshops.admin.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminConfigValidationTest {
    private static final HolderLookup.Provider PROVIDER = HolderLookup.Provider.create(Stream.of(BuiltInRegistries.ITEM.asLookup()));

    @Test
    void acceptsEveryNestedShopSettingUsingRuntimeParsers() {
        JsonElement shop = JsonParser.parseString("""
                {
                  "deny_message": "Reserved shop",
                  "conditions": {
                    "player_tags_all": ["member"],
                    "player_tags_any": ["vip", "admin"],
                    "player_tags_none": ["banned"],
                    "advancements_all": ["minecraft:story/root"],
                    "advancements_any": ["minecraft:story/mine_diamond"],
                    "dimensions_any": ["minecraft:overworld"],
                    "time_ranges_any": [{"start_tick": 13000, "end_tick": 2000}],
                    "scores_all": [{"objective": "reputation", "min": 0, "max": 100}, {"objective": "quest", "equals": 3}]
                  },
                  "categories": [{
                    "name": "General", "conditions": {"player_tags_any": ["vip"]},
                    "offers": [{
                      "id": "emerald", "match": {"include": [{"item": "minecraft:emerald"}], "exclude": [{"item": "minecraft:coal"}]},
                      "count": 2, "price": "123456789012345678901234567890", "stock": 20,
                      "restock": {"type": "daily_reset", "hour": 4, "minute": 30, "time_zone": "Europe/Paris"},
                      "conditions": {"player_tags_none": ["banned"]},
                      "purchase_bonuses": [{"required_bundles": 2, "rewards": [{"item": "minecraft:diamond", "count": 1}]}]
                    }, {
                      "id": "coal", "match": {"include": [{"mod": "minecraft"}]}, "price": 1,
                      "stock": 10, "restock": {"type": "interval", "amount": 2, "every_seconds": 30}
                    }]
                  }]
                }
                """);
        assertDoesNotThrow(() -> AdminConfigService.validate(Map.of("shops/test/shop.json", shop), PROVIDER));
    }

    @Test
    void rejectsInvalidStockBeforeAnyConfigurationIsWritten() {
        JsonElement shop = JsonParser.parseString("""
                {"categories":[{"name":"Test","offers":[{
                  "id":"broken", "match":{"include":[{"item":"minecraft:diamond"}]}, "price":1,
                  "restock":{"type":"interval","amount":1,"every_seconds":10}
                }]}]}
                """);
        IOException failure = assertThrows(IOException.class,
                () -> AdminConfigService.validate(Map.of("shops/test/shop.json", shop), PROVIDER));
        assertTrue(failure.getMessage().contains("stock"));
    }

    @Test
    void validatesAuditFeedbackAndBothBankFormats() {
        assertDoesNotThrow(() -> AdminConfigService.validate(Map.of(
                "commands.json", JsonParser.parseString("{\"allow_player_open\":false}"),
                "audit.json", JsonParser.parseString("{\"enabled\":false,\"flush_each_entry\":false,\"events\":{\"buy_failure\":true}}"),
                "feedback.json", JsonParser.parseString("{\"buy_success\":{\"channels\":[\"chat\",\"sound\"],\"sound\":{\"id\":\"minecraft:entity.villager.no\",\"volume\":0.5,\"pitch\":1.2}},\"shop_denied\":{\"channels\":[],\"sound\":null}}"),
                "global_bank.json", JsonParser.parseString("[]"),
                "shops/test/bank.json", JsonParser.parseString("{\"conditions\":{\"player_tags_all\":[\"vip\"]},\"categories\":[{\"name\":\"Ores\",\"offers\":[{\"match\":{\"include\":[{\"item\":\"minecraft:diamond\"}]},\"price\":10}]}]}")
        ), PROVIDER));
        assertThrows(IOException.class, () -> AdminConfigService.validate(Map.of(
                "commands.json", JsonParser.parseString("{\"allow_player_open\":\"false\"}")), PROVIDER));
        assertThrows(IOException.class, () -> AdminConfigService.validate(Map.of(
                "commands.json", JsonParser.parseString("[]")), PROVIDER));
        assertThrows(IOException.class, () -> AdminConfigService.validate(Map.of(
                "audit.json", JsonParser.parseString("{\"enabled\":\"yes\"}")), PROVIDER));
        assertThrows(IOException.class, () -> AdminConfigService.validate(Map.of(
                "feedback.json", JsonParser.parseString("{\"buy_success\":{\"channels\":[\"invalid\"]}}")), PROVIDER));
        assertThrows(IOException.class, () -> AdminConfigService.validate(Map.of(
                "global_bank.json", JsonParser.parseString("{\"offers\":[],\"categories\":[]}")), PROVIDER));
    }

    @Test
    void acceptsExistingMixedCaseIdsButRejectsNormalizedDuplicates() {
        JsonElement shop = JsonParser.parseString("{\"categories\":[]}");
        assertDoesNotThrow(() -> AdminConfigService.validate(Map.of("shops/LegacyShop/shop.json", shop), PROVIDER));
        IOException failure = assertThrows(IOException.class, () -> AdminConfigService.validate(Map.of(
                "shops/LegacyShop/shop.json", shop,
                "shops/legacyshop/shop.json", shop), PROVIDER));
        assertTrue(failure.getMessage().contains("Duplicate shop id"));
    }
}
