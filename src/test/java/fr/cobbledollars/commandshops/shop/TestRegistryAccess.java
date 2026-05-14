package fr.cobbledollars.commandshops.shop;

import java.util.stream.Stream;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;

final class TestRegistryAccess {
    private static final HolderLookup.Provider PROVIDER = HolderLookup.Provider.create(
            Stream.of(BuiltInRegistries.ITEM.asLookup())
    );

    private TestRegistryAccess() {
    }

    static HolderLookup.Provider provider() {
        return PROVIDER;
    }
}
