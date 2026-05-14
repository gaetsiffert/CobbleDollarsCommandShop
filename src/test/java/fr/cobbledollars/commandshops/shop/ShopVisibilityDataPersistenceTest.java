package fr.cobbledollars.commandshops.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ShopVisibilityDataPersistenceTest {
    @Test
    void disableNormalizesBlankMessageAndEnableRestoresVisibility() throws ReflectiveOperationException {
        ShopVisibilityData data = load(new CompoundTag());

        ShopVisibilityData.VisibilityStatus disabled = data.disable("general_store", "   ", "tester", 123L);

        assertFalse(disabled.enabled());
        assertNull(disabled.message());
        assertEquals("tester", disabled.changedBy());
        assertEquals(123L, disabled.changedAtMillis());
        assertTrue(data.enable("general_store").enabled());
        assertTrue(data.status("general_store").enabled());
    }

    @Test
    void saveAndLoadRoundTripsDisabledShopState() throws ReflectiveOperationException {
        ShopVisibilityData data = load(new CompoundTag());
        data.disable("general_store", "Maintenance", "tester", 456L);

        CompoundTag saved = data.save(new CompoundTag(), TestRegistryAccess.provider());
        ShopVisibilityData reloaded = load(saved);

        ShopVisibilityData.VisibilityStatus status = reloaded.status("general_store");
        assertFalse(status.enabled());
        assertEquals("Maintenance", status.message());
        assertEquals("tester", status.changedBy());
        assertEquals(456L, status.changedAtMillis());
        assertTrue(reloaded.status("blacksmith").enabled());
    }

    private static ShopVisibilityData load(CompoundTag tag) throws ReflectiveOperationException {
        Method loadMethod = ShopVisibilityData.class.getDeclaredMethod(
                "load",
                CompoundTag.class,
                net.minecraft.core.HolderLookup.Provider.class
        );
        loadMethod.setAccessible(true);
        return (ShopVisibilityData) loadMethod.invoke(null, tag, TestRegistryAccess.provider());
    }
}
