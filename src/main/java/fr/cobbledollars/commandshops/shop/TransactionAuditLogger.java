package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.StackCountMath;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

public final class TransactionAuditLogger {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path LOG_FILE = FMLPaths.GAMEDIR.get()
            .resolve("logs")
            .resolve(CobbleDollarsCommandShopsMod.MODID)
            .resolve("transactions.jsonl");
    private static final Object LOCK = new Object();

    private TransactionAuditLogger() {
    }

    public static void logBuySuccess(
            ServerPlayer player,
            ShopDefinition shop,
            ResolvedShopOffer offer,
            int bundleAmount,
            BigInteger totalPrice,
            List<ItemStack> bonusItems
    ) {
        JsonObject event = basePlayerEvent("buy_success", player);
        event.addProperty("shop_id", shop.id());
        event.addProperty("offer_id", offer.sourceId());
        event.addProperty("bundle_amount", bundleAmount);
        ItemStack bundleTemplate = offer.template();
        event.add("bundle_item", stackToJson(bundleTemplate.copyWithCount(bundleTemplate.getCount())));
        event.addProperty("item_count", StackCountMath.multiplyToLong(bundleTemplate.getCount(), bundleAmount));
        event.addProperty("total_price", totalPrice.toString());
        JsonArray bonusArray = new JsonArray();
        for (ItemStack bonusItem : bonusItems) {
            bonusArray.add(stackToJson(bonusItem));
        }
        event.add("bonus_items", bonusArray);
        append(event);
    }

    public static void logSellSuccess(
            ServerPlayer player,
            ShopDefinition shop,
            int soldItemCount,
            BigInteger totalValue,
            List<SoldItemLine> soldItems
    ) {
        JsonObject event = basePlayerEvent("sell_success", player);
        event.addProperty("shop_id", shop.id());
        event.addProperty("item_count", soldItemCount);
        event.addProperty("total_value", totalValue.toString());
        JsonArray lines = new JsonArray();
        for (SoldItemLine soldItem : soldItems) {
            JsonObject line = stackToJson(soldItem.stack());
            line.addProperty("unit_price", soldItem.unitPrice().toString());
            line.addProperty("line_total", soldItem.lineTotal().toString());
            lines.add(line);
        }
        event.add("sold_items", lines);
        append(event);
    }

    public static void logVisibilityChanged(String shopId, boolean enabled, String message, String actorName, String actorUuid) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "visibility_changed");
        event.addProperty("timestamp", Instant.now().toString());
        event.addProperty("shop_id", shopId);
        event.addProperty("enabled", enabled);
        if (message != null && !message.isBlank()) {
            event.addProperty("message", message);
        }
        if (actorName != null) {
            event.addProperty("actor_name", actorName);
        }
        if (actorUuid != null) {
            event.addProperty("actor_uuid", actorUuid);
        }
        append(event);
    }

    private static JsonObject basePlayerEvent(String type, ServerPlayer player) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("timestamp", Instant.now().toString());
        event.addProperty("player_uuid", player.getUUID().toString());
        event.addProperty("player_name", player.getGameProfile().getName());
        event.addProperty("dimension", player.level().dimension().location().toString());
        return event;
    }

    private static JsonObject stackToJson(ItemStack stack) {
        JsonObject object = new JsonObject();
        object.addProperty("item", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
        object.addProperty("count", stack.getCount());
        object.addProperty("name", stack.getHoverName().getString());
        DataComponentMap components = stack.getComponents();
        if (!components.isEmpty()) {
            object.addProperty("components_hash", Integer.toHexString(components.hashCode()));
        }
        return object;
    }

    private static void append(JsonObject event) {
        synchronized (LOCK) {
            try {
                Files.createDirectories(LOG_FILE.getParent());
                try (Writer writer = Files.newBufferedWriter(LOG_FILE, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    GSON.toJson(event, writer);
                    writer.write(System.lineSeparator());
                }
            } catch (IOException exception) {
                CobbleDollarsCommandShopsMod.LOGGER.error("Failed to append transaction audit entry", exception);
            }
        }
    }

    public record SoldItemLine(ItemStack stack, BigInteger unitPrice, BigInteger lineTotal) {
        public SoldItemLine {
            stack = stack.copy();
        }
    }
}
