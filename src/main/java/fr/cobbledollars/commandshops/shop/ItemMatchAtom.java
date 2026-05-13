package fr.cobbledollars.commandshops.shop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public sealed interface ItemMatchAtom permits ItemMatchAtom.ExactStack, ItemMatchAtom.ExactItem, ItemMatchAtom.Tag, ItemMatchAtom.Mod {
    Kind kind();

    String sourceValue();

    List<ItemStack> expandStacks(int count);

    boolean matches(ItemStack stack);

    enum Kind {
        MOD(1),
        TAG(2),
        ITEM(3),
        STACK(4);

        private final int priority;

        Kind(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }
    }

    record ExactStack(ItemStack template, String sourceValue) implements ItemMatchAtom {
        public ExactStack {
            template = template.copyWithCount(1);
        }

        @Override
        public Kind kind() {
            return Kind.STACK;
        }

        @Override
        public List<ItemStack> expandStacks(int count) {
            ItemStack stack = template.copy();
            stack.setCount(count);
            return List.of(stack);
        }

        @Override
        public boolean matches(ItemStack stack) {
            return stack.getItem() == template.getItem() && stack.getComponents().equals(template.getComponents());
        }
    }

    record ExactItem(Item item, String sourceValue) implements ItemMatchAtom {
        @Override
        public Kind kind() {
            return Kind.ITEM;
        }

        @Override
        public List<ItemStack> expandStacks(int count) {
            return List.of(new ItemStack(item, count));
        }

        @Override
        public boolean matches(ItemStack stack) {
            return stack.getItem() == item;
        }
    }

    record Tag(TagKey<Item> tagKey, String sourceValue, List<Item> items) implements ItemMatchAtom {
        public Tag {
            items = sortItems(items);
        }

        @Override
        public Kind kind() {
            return Kind.TAG;
        }

        @Override
        public List<ItemStack> expandStacks(int count) {
            ArrayList<ItemStack> stacks = new ArrayList<>(items.size());
            for (Item item : items) {
                stacks.add(new ItemStack(item, count));
            }
            return List.copyOf(stacks);
        }

        @Override
        public boolean matches(ItemStack stack) {
            return stack.is(tagKey);
        }
    }

    record Mod(String modId, String sourceValue, List<Item> items) implements ItemMatchAtom {
        public Mod {
            items = sortItems(items);
        }

        @Override
        public Kind kind() {
            return Kind.MOD;
        }

        @Override
        public List<ItemStack> expandStacks(int count) {
            ArrayList<ItemStack> stacks = new ArrayList<>(items.size());
            for (Item item : items) {
                stacks.add(new ItemStack(item, count));
            }
            return List.copyOf(stacks);
        }

        @Override
        public boolean matches(ItemStack stack) {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals(modId);
        }
    }

    private static List<Item> sortItems(List<Item> items) {
        ArrayList<Item> sorted = new ArrayList<>(items.size());
        for (Item item : items) {
            if (item != Items.AIR) {
                sorted.add(item);
            }
        }
        sorted.sort(Comparator.comparing(item -> String.valueOf(BuiltInRegistries.ITEM.getKey(item))));
        return List.copyOf(sorted);
    }
}
