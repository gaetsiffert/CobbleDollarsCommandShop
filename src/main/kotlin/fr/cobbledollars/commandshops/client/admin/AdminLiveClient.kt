package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonObject
import fr.cobbledollars.commandshops.admin.AdminMessage
import fr.cobbledollars.commandshops.admin.AdminMessages
import fr.cobbledollars.commandshops.admin.network.AdminLiveClientGuard
import fr.cobbledollars.commandshops.admin.network.AdminLiveChunkPayload
import fr.cobbledollars.commandshops.admin.network.AdminLiveEnvelope
import fr.cobbledollars.commandshops.admin.network.AdminLiveNetwork
import fr.cobbledollars.commandshops.admin.network.AdminLiveRequestPayload
import fr.cobbledollars.commandshops.admin.network.AdminNetwork
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.network.registration.NetworkRegistry
import java.util.UUID

data class AdminLiveResult(val success: Boolean, val state: JsonObject, val message: String)

/** Separate runtime channel: receiving stock or visibility never replaces an edited configuration. */
object AdminLiveClient {
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
        AdminLiveNetwork.clearClientState()
    }

    /** Call when a selection changes without immediately issuing another request. */
    fun invalidate(target: CommandShopAdminScreen) {
        if (screen !== target) return
        guard.invalidate()
        AdminLiveNetwork.clearClientState()
    }

    fun supported(): Boolean {
        val connection = Minecraft.getInstance().connection ?: return false
        return AdminNetwork.clientSessionId() != null &&
            NetworkRegistry.hasChannel(connection, AdminLiveRequestPayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(connection, AdminLiveChunkPayload.TYPE.id())
    }

    /** No retries: stock and visibility mutations take effect immediately on the server. */
    fun request(
        target: CommandShopAdminScreen,
        action: String,
        shopId: String = "",
        playerId: String = "",
        offerId: String = "",
        stockKey: String = "",
        amount: Int = 0,
        enabled: Boolean = true,
        message: String = "",
        allPlayers: Boolean = false,
        operationId: UUID = UUID.randomUUID()
    ): UUID? {
        val sessionId = AdminNetwork.clientSessionId() ?: return null
        if (!supported()) return null
        attach(target)
        guard.begin(operationId)
        try {
            AdminLiveNetwork.request(AdminLiveRequestPayload(sessionId, operationId, action, shopId, playerId,
                offerId, stockKey, amount, enabled, message, allPlayers))
        } catch (exception: RuntimeException) {
            acceptResult(operationId, AdminLiveEnvelope(false, null, AdminMessage.translated("live.failed",
                "Operation rejected: %s", AdminMessages.fromException(exception).english())).json())
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
        if (guard.accept(operationId)) target.acceptLiveResult(operationId, json)
    }

    fun result(json: String): AdminLiveResult {
        val envelope = AdminLiveEnvelope.parse(json)
        return AdminLiveResult(envelope.success(), envelope.state() ?: JsonObject(), envelope.message().component().string)
    }
}
