package fr.cobbledollars.commandshops.admin.network;

import com.google.gson.JsonObject;
import fr.cobbledollars.commandshops.admin.AdminMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminLiveEnvelopeTest {
    @Test
    void preservesAuthoritativeRuntimeStateAndLocalizationSeparatelyFromDrafts() {
        JsonObject state = new JsonObject();
        state.addProperty("shopId", "shop");
        state.addProperty("current", 42);
        AdminMessage message = AdminMessage.translated("live.updated", "Live state updated.");
        AdminLiveEnvelope result = AdminLiveEnvelope.parse(new AdminLiveEnvelope(true, state, message).json());
        assertTrue(result.success());
        assertEquals(state, result.state());
        assertEquals(message, result.message());
        assertFalse(result.json().contains("revision"));
        assertFalse(result.json().contains("documents"));
    }

    @Test
    void carriesLocalizedFailuresWithoutRuntimeData() {
        AdminMessage message = AdminMessage.translated("live.failed", "Operation rejected: %s", "Unknown shop");
        AdminLiveEnvelope result = AdminLiveEnvelope.parse(new AdminLiveEnvelope(false, null, message).json());
        assertFalse(result.success());
        assertNull(result.state());
        assertEquals("Operation rejected: Unknown shop", result.message().english());
    }
}
