package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonObject
import fr.cobbledollars.commandshops.admin.AdminMessage
import fr.cobbledollars.commandshops.admin.AdminMessages
import fr.cobbledollars.commandshops.admin.network.AdminLiveClientGuard
import fr.cobbledollars.commandshops.admin.network.AdminAuditChunkPayload
import fr.cobbledollars.commandshops.admin.network.AdminLiveEnvelope
import fr.cobbledollars.commandshops.admin.network.AdminAuditNetwork
import fr.cobbledollars.commandshops.admin.network.AdminAuditRequestPayload
import fr.cobbledollars.commandshops.admin.network.AdminNetwork
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.network.registration.NetworkRegistry
import java.util.UUID

/** Independent read-only audit replies never replace configuration drafts. */
object AdminAuditClient {
    private var screen: CommandShopAdminScreen? = null
    private val guard = AdminLiveClientGuard()

    @JvmStatic
    fun attach(target: CommandShopAdminScreen) {
        if (screen === target) return
        clear()
        screen = target
        guard.attach()
    }

    @JvmStatic
    fun detach(target: CommandShopAdminScreen) {
        if (screen === target) clear()
    }

    @JvmStatic
    fun clear() {
        screen = null
        guard.detach()
        AdminAuditNetwork.clearClientState()
    }

    /** Call when a selection changes without immediately issuing another request. */
    fun invalidate(target: CommandShopAdminScreen) {
        if (screen !== target) return
        guard.invalidate()
        AdminAuditNetwork.clearClientState()
    }

    fun supported(): Boolean {
        val connection = Minecraft.getInstance().connection ?: return false
        return AdminNetwork.clientSessionId() != null &&
            NetworkRegistry.hasChannel(connection, AdminAuditRequestPayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(connection, AdminAuditChunkPayload.TYPE.id())
    }

    fun request(target: CommandShopAdminScreen, query: String, refresh: Boolean, operationId: UUID): UUID? {
        val sessionId = AdminNetwork.clientSessionId() ?: return null
        if (!supported()) return null
        attach(target)
        guard.begin(operationId)
        try {
            AdminAuditNetwork.request(AdminAuditRequestPayload(sessionId, operationId, query, refresh))
        } catch (exception: RuntimeException) {
            acceptResult(operationId, AdminLiveEnvelope(false, null, AdminMessage.translated("audit.failed",
                "Could not read the audit: %s", AdminMessages.fromException(exception).english())).json())
        }
        return operationId
    }

    @JvmStatic
    fun acceptResult(operationId: UUID, json: String) {
        val target = screen ?: return
        if (Minecraft.getInstance().screen !== target) {
            clear()
            return
        }
        if (guard.accept(operationId)) target.acceptAuditResult(operationId, json)
    }

    fun result(json: String): AdminLiveResult {
        val envelope = AdminLiveEnvelope.parse(json)
        return AdminLiveResult(envelope.success(), envelope.state() ?: JsonObject(), envelope.message().component().string)
    }
}
