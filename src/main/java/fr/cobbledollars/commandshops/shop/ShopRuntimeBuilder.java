package fr.cobbledollars.commandshops.shop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Category;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class ShopRuntimeBuilder {
    private final List<PreparedCategory> preparedCategories;

    ShopRuntimeBuilder(List<ShopCategoryDefinition> categories) {
        this.preparedCategories = prepareCategories(categories);
    }

    ShopDefinition.RuntimeShopData createRuntimeData(
            ShopDefinition shopDefinition,
            ConditionSet shopConditions,
            PlayerShopStockData stockData,
            ServerPlayer player,
            long nowMillis,
            CreateRuntimeMetrics metrics
    ) {
        long phaseStartNanos = metrics == null ? 0L : System.nanoTime();
        Shop runtimeShop = new Shop();
        ConditionSet.ConditionContext context = ConditionSet.ConditionContext.capture(player);
        if (metrics != null) {
            metrics.contextNanos += System.nanoTime() - phaseStartNanos;
        }
        if (!shopConditions.test(context)) {
            return new ShopDefinition.RuntimeShopData(runtimeShop, List.of(), 0, Long.MAX_VALUE, new ShopSessionSnapshot(List.of()));
        }

        phaseStartNanos = metrics == null ? 0L : System.nanoTime();
        ArrayList<VisibleCategory> visibleCategories = new ArrayList<>(preparedCategories.size());
        HashMap<ShopDisplayKey, PreparedCandidate> winners = new HashMap<>();
        for (PreparedCategory category : preparedCategories) {
            if (!category.conditions().test(context)) {
                continue;
            }

            ArrayList<PreparedOfferGroup> visibleOfferGroups = new ArrayList<>(category.offerGroups().size());
            for (PreparedOfferGroup offerGroup : category.offerGroups()) {
                if (!offerGroup.offerDefinition().isVisibleTo(context)) {
                    continue;
                }
                visibleOfferGroups.add(offerGroup);
                for (PreparedCandidate candidate : offerGroup.candidates()) {
                    winners.merge(candidate.displayKey(), candidate, ShopRuntimeBuilder::selectBetterCandidate);
                }
            }
            if (!visibleOfferGroups.isEmpty()) {
                visibleCategories.add(new VisibleCategory(category.name(), List.copyOf(visibleOfferGroups)));
            }
        }
        if (metrics != null) {
            metrics.candidateSelectionNanos += System.nanoTime() - phaseStartNanos;
        }

        phaseStartNanos = metrics == null ? 0L : System.nanoTime();
        UUID playerUuid = player.getUUID();
        ArrayList<ShopDefinition.RuntimeCategory> runtimeCategories = new ArrayList<>(visibleCategories.size());
        ArrayList<ShopSessionSnapshot.CategorySnapshot> snapshotCategories = new ArrayList<>(visibleCategories.size());
        int visibleOfferCount = 0;
        long nextVisibleRestockAtMillis = Long.MAX_VALUE;
        for (VisibleCategory category : visibleCategories) {
            int categoryOfferCapacity = 0;
            for (PreparedOfferGroup offerGroup : category.offerGroups()) {
                categoryOfferCapacity += offerGroup.candidates().size();
            }
            ArrayList<Offer> cobbleOffers = new ArrayList<>(categoryOfferCapacity);
            ArrayList<ShopDefinition.RuntimeShopOfferEntry> resolvedOffers = new ArrayList<>(categoryOfferCapacity);
            ArrayList<ShopSessionSnapshot.OfferSnapshot> snapshotOffers = new ArrayList<>(categoryOfferCapacity);
            for (PreparedOfferGroup offerGroup : category.offerGroups()) {
                for (PreparedCandidate candidate : offerGroup.candidates()) {
                    if (winners.get(candidate.displayKey()) != candidate) {
                        continue;
                    }

                    PlayerShopStockData.OfferRuntimeState offerState =
                            stockData.resolveOfferRuntimeState(playerUuid, shopDefinition, candidate.offer(), nowMillis);
                    Offer runtimeOffer = candidate.offer().createRuntimeOffer(offerState.stock());
                    cobbleOffers.add(runtimeOffer);
                    resolvedOffers.add(new ShopDefinition.RuntimeShopOfferEntry(
                            candidate.offer(),
                            runtimeOffer,
                            offerState.restockPreview()
                    ));
                    snapshotOffers.add(new ShopSessionSnapshot.OfferSnapshot(candidate.offer(), runtimeOffer.getStock()));
                    if (offerState.restockPreview().hasNextRestock()) {
                        nextVisibleRestockAtMillis = Math.min(
                                nextVisibleRestockAtMillis,
                                offerState.restockPreview().nextRestockAtMillis()
                        );
                    }
                }
            }
            if (!cobbleOffers.isEmpty()) {
                runtimeShop.add(new Category(category.name(), cobbleOffers));
                runtimeCategories.add(new ShopDefinition.RuntimeCategory(category.name(), List.copyOf(resolvedOffers)));
                snapshotCategories.add(new ShopSessionSnapshot.CategorySnapshot(category.name(), List.copyOf(snapshotOffers)));
                visibleOfferCount += resolvedOffers.size();
            }
        }
        if (metrics != null) {
            metrics.materializationNanos += System.nanoTime() - phaseStartNanos;
        }
        List<ShopDefinition.RuntimeCategory> immutableRuntimeCategories = List.copyOf(runtimeCategories);
        return new ShopDefinition.RuntimeShopData(
                runtimeShop,
                immutableRuntimeCategories,
                visibleOfferCount,
                nextVisibleRestockAtMillis,
                new ShopSessionSnapshot(List.copyOf(snapshotCategories))
        );
    }

    private static List<PreparedCategory> prepareCategories(List<ShopCategoryDefinition> categories) {
        ArrayList<PreparedCategory> preparedCategories = new ArrayList<>(categories.size());
        int sourceOrder = 0;
        for (ShopCategoryDefinition category : categories) {
            ArrayList<PreparedOfferGroup> preparedOfferGroups = new ArrayList<>(category.offers().size());
            for (ShopOfferDefinition offerDefinition : category.offers()) {
                List<ResolvedShopOffer> resolvedOffers = offerDefinition.createResolvedOffers();
                ArrayList<PreparedCandidate> candidates = new ArrayList<>(resolvedOffers.size());
                for (ResolvedShopOffer resolvedOffer : resolvedOffers) {
                    candidates.add(new PreparedCandidate(displayKey(resolvedOffer.template()), sourceOrder, resolvedOffer));
                }
                preparedOfferGroups.add(new PreparedOfferGroup(offerDefinition, List.copyOf(candidates)));
                sourceOrder++;
            }
            preparedCategories.add(new PreparedCategory(category.name(), category.conditions(), List.copyOf(preparedOfferGroups)));
        }
        return List.copyOf(preparedCategories);
    }

    private static PreparedCandidate selectBetterCandidate(PreparedCandidate current, PreparedCandidate incoming) {
        int currentPriority = current.offer().matchKind().priority();
        int incomingPriority = incoming.offer().matchKind().priority();
        if (incomingPriority > currentPriority) {
            return incoming;
        }
        if (incomingPriority < currentPriority) {
            return current;
        }
        return incoming.sourceOrder() < current.sourceOrder() ? incoming : current;
    }

    private static ShopDisplayKey displayKey(ItemStack stack) {
        return new ShopDisplayKey(stack.getItem(), stack.getComponents());
    }

    private record PreparedCategory(String name, ConditionSet conditions, List<PreparedOfferGroup> offerGroups) {
    }

    private record VisibleCategory(String name, List<PreparedOfferGroup> offerGroups) {
    }

    private record PreparedOfferGroup(ShopOfferDefinition offerDefinition, List<PreparedCandidate> candidates) {
    }

    private record PreparedCandidate(ShopDisplayKey displayKey, int sourceOrder, ResolvedShopOffer offer) {
    }

    private record ShopDisplayKey(Item item, DataComponentMap components) {
    }

    static final class CreateRuntimeMetrics {
        private long contextNanos;
        private long candidateSelectionNanos;
        private long materializationNanos;

        long contextNanos() {
            return contextNanos;
        }

        long candidateSelectionNanos() {
            return candidateSelectionNanos;
        }

        long materializationNanos() {
            return materializationNanos;
        }
    }
}
