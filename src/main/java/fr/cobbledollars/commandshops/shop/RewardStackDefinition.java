package fr.cobbledollars.commandshops.shop;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public record RewardStackDefinition(ItemStack template, String itemId, String stackDefinition) {
    public RewardStackDefinition {
        template = template.copy();
    }

    public static RewardStackDefinition parse(JsonObject rewardObject, HolderLookup.Provider provider, String context) throws IOException {
        String stackDefinition = ConfigParsing.readOptionalString(rewardObject, "stack", null, context);
        String itemId = ConfigParsing.readOptionalString(rewardObject, "item", null, context);
        int fieldCount = (stackDefinition != null ? 1 : 0) + (itemId != null ? 1 : 0);
        if (fieldCount != 1) {
            throw new IOException("Exactly one of 'item' or 'stack' must be set in " + context + ".");
        }

        int count = ConfigParsing.readInt(rewardObject, "count", 1, context);
        if (count <= 0) {
            throw new IOException("Field 'count' in " + context + " must be greater than 0.");
        }

        if (stackDefinition != null) {
            try {
                ItemParser.ItemResult parsed = new ItemParser(provider).parse(new StringReader(stackDefinition));
                return new RewardStackDefinition(new ItemStack(parsed.item(), count, parsed.components()), null, stackDefinition);
            } catch (CommandSyntaxException exception) {
                throw new IOException("Field 'stack' in " + context + " is invalid: " + stackDefinition, exception);
            }
        }

        ResourceLocation itemLocation = ConfigParsing.readResourceLocation(itemId, "item", context);
        if (!BuiltInRegistries.ITEM.containsKey(itemLocation)) {
            throw new IOException("Field 'item' in " + context + " references an unknown item: " + itemId);
        }
        return new RewardStackDefinition(new ItemStack(BuiltInRegistries.ITEM.get(itemLocation), count), itemId, null);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        if (stackDefinition != null) {
            object.addProperty("stack", stackDefinition);
        } else {
            object.addProperty("item", itemId);
        }
        object.addProperty("count", template.getCount());
        return object;
    }

    public ItemStack createScaledStack(int multiplier) {
        return template.copyWithCount(Math.multiplyExact(template.getCount(), multiplier));
    }
}
