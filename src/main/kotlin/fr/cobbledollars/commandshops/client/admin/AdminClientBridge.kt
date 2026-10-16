package fr.cobbledollars.commandshops.client.admin

import fr.cobbledollars.commandshops.admin.AdminMessage
import fr.cobbledollars.commandshops.admin.network.AdminOperationGuard
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.UUID

/** Loaded reflectively by the optional network handlers, only on the physical client. */
object AdminClientBridge {
    private val unsolicited = UUID(0, 0)
    private val operations = AdminOperationGuard()
    private var boundScreen: CommandShopAdminScreen? = null

    private fun tr(key: String): String = Component.translatable("gui.cobbledollarscommandshops.admin.$key").string

    @JvmStatic
    fun bind(screen: CommandShopAdminScreen) {
        if (boundScreen !== screen) {
            operations.detach()
            boundScreen = screen
            operations.attach()
            AdminLiveClient.attach(screen)
        }
    }

    @JvmStatic
    fun beginOperation(screen: CommandShopAdminScreen, draft: String, save: Boolean): UUID {
        bind(screen)
        return UUID.randomUUID().also { operations.begin(it, draft, save) }
    }

    @JvmStatic
    fun expireOperation(screen: CommandShopAdminScreen, id: UUID) {
        if (boundScreen === screen) operations.expire(id)
    }

    @JvmStatic
    fun detach(screen: CommandShopAdminScreen) {
        if (boundScreen === screen) {
            operations.detach()
            AdminLiveClient.detach(screen)
            boundScreen = null
        }
    }

    @JvmStatic
    fun clear() {
        boundScreen = null
        operations.clear()
        AdminLiveClient.clear()
    }

    private fun currentScreen(): CommandShopAdminScreen? {
        val screen = Minecraft.getInstance().screen as? CommandShopAdminScreen
        if (screen !== boundScreen) {
            operations.detach()
            boundScreen?.let { AdminLiveClient.detach(it) }
            boundScreen = null
        }
        return screen
    }

    /** Initial command snapshots use a reserved id; all editor replies must match a live operation. */
    @JvmStatic
    fun acceptSnapshot(operationId: UUID, json: String) {
        val screen = currentScreen()
        if (operationId == unsolicited) {
            if (screen == null) {
                val opened = CommandShopAdminScreen(json)
                Minecraft.getInstance().setScreen(opened)
                bind(opened)
            } else {
                screen.acceptDeferredSnapshot(json, true, tr("server_state_available"))
            }
            return
        }
        if (screen == null || boundScreen !== screen) return
        val save = operations.isSave(operationId)
        when (operations.snapshot(operationId, screen.currentDraft())) {
            AdminOperationGuard.Delivery.APPLY -> {
                screen.completeOperation(operationId)
                screen.acceptSnapshot(json)
            }
            AdminOperationGuard.Delivery.DEFER -> {
                screen.completeOperation(operationId)
                screen.acceptDeferredSnapshot(json, true, tr(if (save) "saved_state_available" else "server_state_available"))
            }
            AdminOperationGuard.Delivery.IGNORE -> Unit
        }
    }

    /** Kept for the read-only first snapshot of older servers and the local UI smoke fixture. */
    @JvmStatic
    fun acceptSnapshot(json: String) {
        val minecraft = Minecraft.getInstance()
        val screen = minecraft.screen
        if (screen is CommandShopAdminScreen) screen.acceptDeferredSnapshot(json, true, tr("server_state_available"))
        else {
            val opened = CommandShopAdminScreen(json)
            minecraft.setScreen(opened)
            bind(opened)
        }
    }

    @JvmStatic
    fun acceptResult(success: Boolean, message: String) {
        (Minecraft.getInstance().screen as? CommandShopAdminScreen)?.acceptResult(success, message)
    }

    @JvmStatic
    fun acceptLocalizedResult(success: Boolean, message: AdminMessage) {
        (Minecraft.getInstance().screen as? CommandShopAdminScreen)?.acceptResult(success, message.component().string)
    }

    @JvmStatic
    fun acceptLocalizedResult(operationId: UUID, success: Boolean, message: AdminMessage) {
        val screen = currentScreen() ?: return
        val text = message.component().string
        if (operationId == unsolicited) {
            screen.acceptDeferredResult(success, text)
            return
        }
        if (boundScreen !== screen) return
        when (operations.result(operationId, success)) {
            AdminOperationGuard.Delivery.APPLY -> {
                if (!success) screen.completeOperation(operationId)
                screen.acceptResult(success, text)
            }
            AdminOperationGuard.Delivery.DEFER -> {
                if (!success) screen.completeOperation(operationId)
                screen.acceptDeferredResult(success,
                    if (success && operations.isSave(operationId)) tr("late_save_confirmed") else text)
            }
            AdminOperationGuard.Delivery.IGNORE -> Unit
        }
    }

    @JvmStatic
    fun acceptTimeout(operationId: UUID, message: AdminMessage) {
        val screen = currentScreen() ?: return
        if (boundScreen === screen && operations.expire(operationId)) {
            screen.completeOperation(operationId)
            screen.acceptResult(false, message.component().string)
        }
    }
}
