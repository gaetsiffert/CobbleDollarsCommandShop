package fr.cobbledollars.commandshops.audit;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** The identity recorded at the time of a movement, independent of later name changes. */
public record AuditPlayerRef(UUID uuid, String name) {
    public AuditPlayerRef {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }

    public static AuditPlayerRef of(ServerPlayer player) {
        return new AuditPlayerRef(player.getUUID(), player.getGameProfile().getName());
    }
}
