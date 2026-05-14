package fr.cobbledollars.commandshops.gametest;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import fr.cobbledollars.commandshops.shop.BankCategoryDefinition;
import fr.cobbledollars.commandshops.shop.BankDefinition;
import fr.cobbledollars.commandshops.shop.BankOfferDefinition;
import fr.cobbledollars.commandshops.shop.ConditionSet;
import fr.cobbledollars.commandshops.shop.ItemMatchAtom;
import fr.cobbledollars.commandshops.shop.ItemMatchExpression;
import fr.cobbledollars.commandshops.shop.PlayerShopStockData;
import fr.cobbledollars.commandshops.shop.ShopCategoryDefinition;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopOfferDefinition;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

@ForEachTest(idPrefix = "resolution.", groups = "resolution", side = Dist.DEDICATED_SERVER)
public final class CommandShopResolutionGameTests {
    private CommandShopResolutionGameTests() {
    }

    @TestHolder(
            value = "shop_runtime_prefers_specific_offer_for_duplicate_display_stack",
            title = "Shop runtime prefers the most specific duplicate offer",
            description = "Builds an in-memory shop with duplicate display stacks and verifies runtime resolution keeps the item override over the generic tag offer."
    )
    @GameTest(batch = "resolution.shop_runtime", timeoutTicks = 80, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void shop_runtime_prefers_specific_offer_for_duplicate_display_stack(ExtendedGameTestHelper helper) {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);

        ShopOfferDefinition genericOffer = new ShopOfferDefinition(
                "generic_gems",
                new ItemMatchExpression(
                        List.of(new ItemMatchAtom.Tag(
                                TagKey.create(Registries.ITEM, ResourceLocation.parse("c:test_gems")),
                                "c:test_gems",
                                List.of(Items.DIAMOND, Items.EMERALD)
                        )),
                        List.of()
                ),
                1,
                BigInteger.valueOf(10L),
                -1,
                null,
                ConditionSet.NONE,
                List.of()
        );
        ShopOfferDefinition diamondOverride = new ShopOfferDefinition(
                "diamond_override",
                ItemMatchExpression.include(new ItemMatchAtom.ExactItem(Items.DIAMOND, "minecraft:diamond")),
                1,
                BigInteger.valueOf(25L),
                -1,
                null,
                ConditionSet.NONE,
                List.of()
        );
        ShopDefinition shop = new ShopDefinition(
                "runtime_resolution",
                List.of(new ShopCategoryDefinition("Default", List.of(genericOffer, diamondOverride), ConditionSet.NONE)),
                ConditionSet.NONE,
                null,
                Path.of("runtime_resolution.json")
        );

        ShopDefinition.RuntimeShopData runtimeData = shop.createRuntimeData(
                PlayerShopStockData.get(helper.getLevel().getServer()),
                player,
                0L
        );
        Map<Item, ShopDefinition.RuntimeShopOfferEntry> offersByItem = runtimeData.categories().getFirst().offers().stream()
                .collect(Collectors.toMap(entry -> entry.resolvedOffer().itemStack().getItem(), entry -> entry));

        helper.assertValueEqual(2, offersByItem.size(), "Expected exactly two visible runtime offers.");
        helper.assertTrue(
                BigInteger.valueOf(25L).equals(offersByItem.get(Items.DIAMOND).resolvedOffer().price()),
                "The diamond override should win over the generic tag offer."
        );
        helper.assertTrue(
                BigInteger.valueOf(10L).equals(offersByItem.get(Items.EMERALD).resolvedOffer().price()),
                "The emerald entry should keep the generic tag price."
        );
        helper.assertTrue(
                offersByItem.get(Items.DIAMOND).resolvedOffer().matchKind() == ItemMatchAtom.Kind.ITEM,
                "The winning diamond offer should come from the exact item override."
        );
        helper.assertTrue(
                offersByItem.get(Items.EMERALD).resolvedOffer().matchKind() == ItemMatchAtom.Kind.TAG,
                "The emerald offer should still come from the generic tag rule."
        );
        helper.succeed();
    }

    @TestHolder(
            value = "bank_runtime_prefers_exact_stack_override_over_generic_item_offer",
            title = "Bank runtime prefers exact stack overrides",
            description = "Builds an in-memory bank with a generic item offer and an exact stack override, then verifies runtime lookup returns the override."
    )
    @GameTest(batch = "resolution.bank_runtime", timeoutTicks = 80, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void bank_runtime_prefers_exact_stack_override_over_generic_item_offer(ExtendedGameTestHelper helper) {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);

        BankOfferDefinition genericOffer = new BankOfferDefinition(
                ItemMatchExpression.include(new ItemMatchAtom.ExactItem(Items.DIAMOND_SWORD, "minecraft:diamond_sword")),
                BigInteger.valueOf(50L),
                ConditionSet.NONE
        );
        BankOfferDefinition exactStackOverride = new BankOfferDefinition(
                ItemMatchExpression.include(new ItemMatchAtom.ExactStack(new ItemStack(Items.DIAMOND_SWORD), "minecraft:diamond_sword")),
                BigInteger.valueOf(90L),
                ConditionSet.NONE
        );
        BankDefinition bank = new BankDefinition(
                List.of(new BankCategoryDefinition("Weapons", List.of(genericOffer, exactStackOverride), ConditionSet.NONE)),
                ConditionSet.NONE,
                Path.of("runtime_bank.json")
        );

        var runtimeData = bank.createRuntimeData(player);
        var resolved = runtimeData.get(new ItemStack(Items.DIAMOND_SWORD));
        helper.assertTrue(resolved != null, "Expected a runtime bank offer for the diamond sword.");
        helper.assertTrue(BigInteger.valueOf(90L).equals(resolved.getPrice()), "The exact stack override should win over the generic item offer.");

        helper.succeed();
    }
}
