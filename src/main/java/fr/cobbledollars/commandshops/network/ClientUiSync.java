package fr.cobbledollars.commandshops.network;

import java.util.List;
import java.util.UUID;

import fr.cobbledollars.commandshops.network.payload.BankUiStatePayload;
import fr.cobbledollars.commandshops.network.payload.ClientOverlayMessagePayload;
import fr.cobbledollars.commandshops.network.payload.ShopUiStatePayload;
import fr.cobbledollars.commandshops.shop.BankDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class ClientUiSync {
    private ClientUiSync() {
    }

    public static boolean sendOverlayMessage(ServerPlayer player, Component message, int color, int ttlMillis) {
        if (!NetworkRegistry.hasChannel(player.connection, ClientOverlayMessagePayload.TYPE.id())) {
            return false;
        }

        PacketDistributor.sendToPlayer(player, new ClientOverlayMessagePayload(message, color, ttlMillis));
        return true;
    }

    public static void sendShopUiState(ServerPlayer player, UUID sessionUuid, List<ShopUiStatePayload.OfferState> offers) {
        if (!NetworkRegistry.hasChannel(player.connection, ShopUiStatePayload.TYPE.id())) {
            return;
        }

        PacketDistributor.sendToPlayer(player, new ShopUiStatePayload(sessionUuid, offers));
    }

    public static void sendBankUiState(ServerPlayer player, BankDefinition.RuntimeBankData runtimeBankData) {
        if (!NetworkRegistry.hasChannel(player.connection, BankUiStatePayload.TYPE.id())) {
            return;
        }

        PacketDistributor.sendToPlayer(
                player,
                BankUiStatePayload.fromOffers(
                        List.copyOf(runtimeBankData.exactOffersByKey().values()),
                        List.copyOf(runtimeBankData.genericOffersByItem().values())
                )
        );
    }
}
