package fr.cobbledollars.commandshops.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class ItemMatchExpressionTest {
    private static final TagKey<net.minecraft.world.item.Item> TEST_GEM_TAG = TagKey.create(
            Registries.ITEM,
            ResourceLocation.parse("c:test_gems")
    );

    @Test
    void resolveMatchesPrefersMoreSpecificMatchesForDuplicateItems() {
        ItemMatchExpression expression = new ItemMatchExpression(
                List.of(
                        new ItemMatchAtom.Tag(TEST_GEM_TAG, "c:test_gems", List.of(Items.DIAMOND, Items.EMERALD)),
                        new ItemMatchAtom.ExactItem(Items.DIAMOND, "minecraft:diamond")
                ),
                List.of()
        );

        List<ItemMatchExpression.ResolvedMatch> matches = expression.resolveMatches(4);

        assertEquals(2, matches.size());
        assertEquals(Items.EMERALD, matches.get(0).stack().getItem());
        assertEquals(ItemMatchAtom.Kind.TAG, matches.get(0).kind());
        assertEquals(Items.DIAMOND, matches.get(1).stack().getItem());
        assertEquals(ItemMatchAtom.Kind.ITEM, matches.get(1).kind());
    }

    @Test
    void resolveMatchesPrefersExactStackOverExactItem() {
        ItemStack stackTemplate = new ItemStack(Items.DIAMOND_SWORD);
        ItemMatchExpression expression = new ItemMatchExpression(
                List.of(
                        new ItemMatchAtom.ExactItem(Items.DIAMOND_SWORD, "minecraft:diamond_sword"),
                        new ItemMatchAtom.ExactStack(stackTemplate, "minecraft:diamond_sword")
                ),
                List.of()
        );

        List<ItemMatchExpression.ResolvedMatch> matches = expression.resolveMatches(1);

        assertEquals(1, matches.size());
        assertEquals(Items.DIAMOND_SWORD, matches.getFirst().stack().getItem());
        assertEquals(ItemMatchAtom.Kind.STACK, matches.getFirst().kind());
    }

    @Test
    void resolveMatchesAppliesExclusionsBeforeReturningResults() {
        ItemMatchExpression expression = new ItemMatchExpression(
                List.of(new ItemMatchAtom.Tag(TEST_GEM_TAG, "c:test_gems", List.of(Items.DIAMOND, Items.EMERALD))),
                List.of(new ItemMatchAtom.ExactItem(Items.EMERALD, "minecraft:emerald"))
        );

        List<ItemMatchExpression.ResolvedMatch> matches = expression.resolveMatches(3);

        assertEquals(1, matches.size());
        assertEquals(Items.DIAMOND, matches.getFirst().stack().getItem());
        assertEquals(3, matches.getFirst().stack().getCount());
    }
}
