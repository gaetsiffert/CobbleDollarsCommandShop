package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Bank;

public final class ShopRegistry {
    private static RegistryState state = RegistryState.empty();

    private ShopRegistry() {
    }

    public static synchronized ReloadSummary initialize(HolderLookup.Provider provider) throws IOException {
        ShopFiles.ensureDefaultShopsExist();
        BankFiles.ensureDefaultGlobalBankExists();
        ConfigGuideFiles.ensureDetailedGuideExists();
        RegistryState loadedState = loadState(provider);
        state = loadedState;
        return new ReloadSummary(loadedState.shops().size(), loadedState.shopBanks().size(), BankFiles.getGlobalBankFile());
    }

    public static synchronized ReloadSummary reload(HolderLookup.Provider provider) throws IOException {
        RegistryState loadedState = loadState(provider);
        state = loadedState;
        return new ReloadSummary(loadedState.shops().size(), loadedState.shopBanks().size(), BankFiles.getGlobalBankFile());
    }

    public static synchronized void clear() {
        state = RegistryState.empty();
    }

    public static ShopDefinition getShop(String shopId) {
        return state.shops().get(ShopFiles.normalizeId(shopId, "shop id"));
    }

    public static Bank getBank(String shopId, ServerPlayer player) {
        return getBankDefinition(shopId).createRuntimeBank(player);
    }

    static BankDefinition getBankDefinition(String shopId) {
        String normalizedShopId = ShopFiles.normalizeId(shopId, "shop id");
        BankDefinition bank = state.shopBanks().get(normalizedShopId);
        if (bank != null) {
            return bank;
        }
        return state.globalBank();
    }

    public static List<String> listShopIds() {
        return state.shops().keySet().stream().sorted().toList();
    }

    public static ShopAccessResult evaluateAccess(MinecraftServer server, ShopDefinition shop, ServerPlayer player) {
        ShopVisibilityData.VisibilityStatus visibilityStatus = ShopVisibilityData.get(server).status(shop.id());
        if (!visibilityStatus.enabled()) {
            return ShopAccessResult.deny(visibilityStatus.denialMessage(shop.id()));
        }
        if (!shop.isAccessibleBy(player)) {
            return ShopAccessResult.deny(defaultDenyMessage(shop));
        }
        return ShopAccessResult.allow();
    }

    public static Path getShopDirectory() {
        return ShopFiles.getShopDirectory();
    }

    public static Path getGlobalBankFile() {
        return BankFiles.getGlobalBankFile();
    }

    private static RegistryState loadState(HolderLookup.Provider provider) throws IOException {
        Files.createDirectories(ShopFiles.getShopDirectory());

        LinkedHashMap<String, ShopDefinition> shops = new LinkedHashMap<>();
        LinkedHashMap<String, BankDefinition> shopBanks = new LinkedHashMap<>();

        List<Path> entries;
        try (var paths = Files.list(ShopFiles.getShopDirectory())) {
            entries = paths.sorted().toList();
        }

        for (Path entry : entries) {
            if (!Files.isDirectory(entry)) {
                continue;
            }

            String shopId = ShopFiles.normalizeId(entry.getFileName().toString(), "shop folder name");
            Path shopFile = ShopFiles.resolveShopFile(entry);
            if (!Files.isRegularFile(shopFile)) {
                throw new IOException("Shop folder '" + entry + "' must contain a 'shop.json' file.");
            }

            ShopDefinition shop = ShopFiles.parseShopFile(shopId, shopFile, provider);
            ShopDefinition previous = shops.putIfAbsent(shop.id(), shop);
            if (previous != null) {
                throw new IOException("Duplicate shop id '" + shop.id() + "' in " + previous.sourceFile() + " and " + shop.sourceFile() + ".");
            }

            Path localBankFile = BankFiles.resolveLocalBankFile(entry);
            if (Files.isRegularFile(localBankFile)) {
                shopBanks.put(shop.id(), BankFiles.loadBankFile(localBankFile, provider));
            }
        }

        BankDefinition globalBank = BankFiles.loadGlobalBank(provider);
        return new RegistryState(Map.copyOf(shops), Map.copyOf(shopBanks), globalBank);
    }

    public record ReloadSummary(int shopCount, int localBankCount, Path globalBankFile) {
    }

    public record ShopAccessResult(boolean allowed, Component denialMessage) {
        private static ShopAccessResult allow() {
            return new ShopAccessResult(true, null);
        }

        private static ShopAccessResult deny(Component denialMessage) {
            return new ShopAccessResult(false, denialMessage);
        }
    }

    private record RegistryState(Map<String, ShopDefinition> shops, Map<String, BankDefinition> shopBanks, BankDefinition globalBank) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), Map.of(), new BankDefinition(List.of(), ConditionSet.NONE, BankFiles.getGlobalBankFile()));
        }
    }

    private static Component defaultDenyMessage(ShopDefinition shop) {
        if (shop.denyMessage() == null || shop.denyMessage().isBlank()) {
            return Component.translatable("cobbledollarscommandshops.feedback.shop_denied.default");
        }
        return Component.literal(shop.denyMessage());
    }
}
