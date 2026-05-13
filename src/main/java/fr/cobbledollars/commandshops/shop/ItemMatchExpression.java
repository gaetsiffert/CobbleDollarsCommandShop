package fr.cobbledollars.commandshops.shop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ItemMatchExpression {
    private final List<ItemMatchAtom> include;
    private final List<ItemMatchAtom> exclude;

    public ItemMatchExpression(List<ItemMatchAtom> include, List<ItemMatchAtom> exclude) {
        if (include.isEmpty()) {
            throw new IllegalArgumentException("Item match expression must define at least one include atom.");
        }
        this.include = List.copyOf(include);
        this.exclude = List.copyOf(exclude);
    }

    public static ItemMatchExpression include(ItemMatchAtom atom) {
        return new ItemMatchExpression(List.of(atom), List.of());
    }

    public List<ItemMatchAtom> include() {
        return include;
    }

    public List<ItemMatchAtom> exclude() {
        return exclude;
    }

    public ItemStack createDisplayStack(int count) {
        List<ResolvedMatch> matches = resolveMatches(count);
        if (matches.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return matches.get(0).stack().copy();
    }

    public List<ResolvedMatch> resolveMatches(int count) {
        ArrayList<ResolvedCandidate> expanded = new ArrayList<>();
        int sortOrder = 0;
        for (ItemMatchAtom atom : include) {
            for (ItemStack stack : atom.expandStacks(count)) {
                if (isExcluded(stack)) {
                    continue;
                }
                expanded.add(new ResolvedCandidate(sortOrder++, atom.kind(), stack));
            }
        }

        LinkedHashMap<MatchKey, ResolvedCandidate> winners = new LinkedHashMap<>();
        for (ResolvedCandidate candidate : expanded) {
            winners.merge(new MatchKey(candidate.stack().getItem(), candidate.stack().getComponents()), candidate, ItemMatchExpression::selectBetterCandidate);
        }

        ArrayList<ResolvedCandidate> resolved = new ArrayList<>(winners.values());
        resolved.sort(Comparator.comparingInt(ResolvedCandidate::sortOrder));

        ArrayList<ResolvedMatch> matches = new ArrayList<>(resolved.size());
        for (ResolvedCandidate candidate : resolved) {
            matches.add(new ResolvedMatch(candidate.stack(), candidate.kind()));
        }
        return List.copyOf(matches);
    }

    private boolean isExcluded(ItemStack stack) {
        for (ItemMatchAtom atom : exclude) {
            if (atom.matches(stack)) {
                return true;
            }
        }
        return false;
    }

    private static ResolvedCandidate selectBetterCandidate(ResolvedCandidate current, ResolvedCandidate incoming) {
        if (incoming.kind().priority() > current.kind().priority()) {
            return incoming;
        }
        if (incoming.kind().priority() < current.kind().priority()) {
            return current;
        }
        return incoming.sortOrder() < current.sortOrder() ? incoming : current;
    }

    public record ResolvedMatch(ItemStack stack, ItemMatchAtom.Kind kind) {
        public ResolvedMatch {
            stack = stack.copy();
        }
    }

    private record ResolvedCandidate(int sortOrder, ItemMatchAtom.Kind kind, ItemStack stack) {
        private ResolvedCandidate {
            stack = stack.copy();
        }
    }

    private record MatchKey(Item item, DataComponentMap components) {
    }
}
