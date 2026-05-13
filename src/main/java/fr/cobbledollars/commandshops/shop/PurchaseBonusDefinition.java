package fr.cobbledollars.commandshops.shop;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;

public record PurchaseBonusDefinition(int requiredBundles, List<RewardStackDefinition> rewards) {
    public PurchaseBonusDefinition {
        rewards = List.copyOf(rewards);
    }

    public int multiplierFor(int purchasedBundles) {
        if (requiredBundles <= 0 || purchasedBundles < requiredBundles) {
            return 0;
        }
        return purchasedBundles / requiredBundles;
    }

    public List<ItemStack> createRewardStacks(int purchasedBundles) {
        int multiplier = multiplierFor(purchasedBundles);
        if (multiplier <= 0 || rewards.isEmpty()) {
            return List.of();
        }

        ArrayList<ItemStack> stacks = new ArrayList<>(rewards.size());
        for (RewardStackDefinition reward : rewards) {
            stacks.add(reward.createScaledStack(multiplier));
        }
        return List.copyOf(stacks);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("required_bundles", requiredBundles);
        JsonArray rewardsArray = new JsonArray();
        for (RewardStackDefinition reward : rewards) {
            rewardsArray.add(reward.toJson());
        }
        object.add("rewards", rewardsArray);
        return object;
    }
}
