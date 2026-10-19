package fr.cobbledollars.commandshops.admin.network;

import fr.cobbledollars.commandshops.audit.AuditQuery;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminAuditRequestPayloadTest {
    @Test void roundTripsIndependentReadQuery() {
        var query=new AuditQuery("journal","7d","Poké","shop","uuid","minecraft:apple","offer","buy_success","recent",2,"");
        var request=new AdminAuditRequestPayload(UUID.randomUUID(),UUID.randomUUID(),query.json(),true);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            AdminAuditRequestPayload.STREAM_CODEC.encode(buffer,request);
            assertEquals(request,AdminAuditRequestPayload.STREAM_CODEC.decode(buffer)); assertFalse(buffer.isReadable());
            assertEquals(query,AuditQuery.parse(query.json()));
        } finally { buffer.release(); }
    }
    @Test void rejectsUnsupportedViewsPeriodsAndUnboundedFilters() {
        for(String json:new String[]{"{\"view\":\"delete\"}","{\"window\":\"century\"}","{\"page\":-1}","{\"event\":\"configuration_saved\"}",
                "{\"shop\":\""+"x".repeat(257)+"\"}"})
            assertThrows(RuntimeException.class,()->new AdminAuditRequestPayload(UUID.randomUUID(),UUID.randomUUID(),json,false));
        assertThrows(IllegalArgumentException.class,()->new AdminAuditRequestPayload(UUID.randomUUID(),new UUID(0,0),"{}",false));
        assertThrows(IllegalArgumentException.class,()->new AdminAuditRequestPayload(UUID.randomUUID(),UUID.randomUUID()," ".repeat(4097),false));
    }
}
