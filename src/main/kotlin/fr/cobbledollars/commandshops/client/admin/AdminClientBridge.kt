package fr.cobbledollars.commandshops.client.admin

import fr.cobbledollars.commandshops.admin.AdminMessage
import net.minecraft.client.Minecraft

/** Loaded reflectively by the optional network handlers, only on the physical client. */
object AdminClientBridge {
    @JvmStatic
    fun acceptSnapshot(json: String) {
        val minecraft = Minecraft.getInstance()
        val screen = minecraft.screen
        if (screen is CommandShopAdminScreen) screen.acceptSnapshot(json)
        else minecraft.setScreen(CommandShopAdminScreen(json))
    }

    @JvmStatic
    fun acceptResult(success: Boolean, message: String) {
        (Minecraft.getInstance().screen as? CommandShopAdminScreen)?.acceptResult(success, message)
    }

    @JvmStatic
    fun acceptLocalizedResult(success: Boolean, message: AdminMessage) {
        (Minecraft.getInstance().screen as? CommandShopAdminScreen)?.acceptResult(success, message.component().string)
    }
}
