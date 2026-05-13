package fr.cobbledollars.commandshops.network;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.network.payload.BankUiStatePayload;
import fr.cobbledollars.commandshops.network.payload.ClientOverlayMessagePayload;
import fr.cobbledollars.commandshops.network.payload.ShopUiStatePayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = CobbleDollarsCommandShopsMod.MODID)
public final class CommandShopPayloads {
    private static final String NETWORK_VERSION = "2";
    private static final String CLIENT_UI_STATE_CLASS = "fr.cobbledollars.commandshops.client.ClientUiState";
    private static final Map<String, Method> CLIENT_HANDLER_METHODS = new HashMap<>();

    private CommandShopPayloads() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION).optional();
        registrar.playToClient(ClientOverlayMessagePayload.TYPE, ClientOverlayMessagePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> dispatchOverlayMessage(payload)));
        registrar.playToClient(BankUiStatePayload.TYPE, BankUiStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> dispatchBankUiState(payload)));
        registrar.playToClient(ShopUiStatePayload.TYPE, ShopUiStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> dispatchShopUiState(payload)));
    }

    private static void dispatchOverlayMessage(ClientOverlayMessagePayload payload) {
        if (FMLEnvironment.dist.isClient()) {
            invokeClientHandler("acceptOverlayMessage", ClientOverlayMessagePayload.class, payload);
        }
    }

    private static void dispatchShopUiState(ShopUiStatePayload payload) {
        if (FMLEnvironment.dist.isClient()) {
            invokeClientHandler("acceptShopUiState", ShopUiStatePayload.class, payload);
        }
    }

    private static void dispatchBankUiState(BankUiStatePayload payload) {
        if (FMLEnvironment.dist.isClient()) {
            invokeClientHandler("acceptBankUiState", BankUiStatePayload.class, payload);
        }
    }

    private static void invokeClientHandler(String methodName, Class<?> parameterType, Object payload) {
        try {
            Method handlerMethod = CLIENT_HANDLER_METHODS.get(methodName);
            if (handlerMethod == null) {
                Class<?> clientStateClass = Class.forName(CLIENT_UI_STATE_CLASS);
                handlerMethod = clientStateClass.getMethod(methodName, parameterType);
                CLIENT_HANDLER_METHODS.put(methodName, handlerMethod);
            }
            handlerMethod.invoke(null, payload);
        } catch (ReflectiveOperationException exception) {
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to dispatch optional client UI payload '{}'.", methodName, exception);
        }
    }
}
