package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Bank;

public final class ShopRegistry {
    private static RegistryState state = RegistryState.empty();

    private ShopRegistry() {
    }

    public static synchronized ReloadSummary initialize() throws IOException {
        ShopFiles.ensureExampleShopExists();
        BankFiles.ensureExampleGlobalBankExists();
        RegistryState loadedState = loadState();
        state = loadedState;
        return new ReloadSummary(loadedState.shops().size(), loadedState.shopBanks().size(), BankFiles.getGlobalBankFile());
    }

    public static synchronized ReloadSummary reload() throws IOException {
        RegistryState loadedState = loadState();
        state = loadedState;
        return new ReloadSummary(loadedState.shops().size(), loadedState.shopBanks().size(), BankFiles.getGlobalBankFile());
    }

    public static synchronized void clear() {
        state = RegistryState.empty();
    }

    public static ShopDefinition getShop(String shopId) {
        return state.shops().get(ShopFiles.normalizeId(shopId, "shop id"));
    }

    public static Bank getBank(String shopId) {
        String normalizedShopId = ShopFiles.normalizeId(shopId, "shop id");
        Bank bank = state.shopBanks().get(normalizedShopId);
        if (bank != null) {
            return BankFiles.copyBank(bank);
        }
        return BankFiles.copyBank(state.globalBank());
    }

    public static List<String> listShopIds() {
        return state.shops().keySet().stream().sorted().toList();
    }

    public static Path getShopDirectory() {
        return ShopFiles.getShopDirectory();
    }

    public static Path getGlobalBankFile() {
        return BankFiles.getGlobalBankFile();
    }

    private static RegistryState loadState() throws IOException {
        Files.createDirectories(ShopFiles.getShopDirectory());

        LinkedHashMap<String, ShopDefinition> shops = new LinkedHashMap<>();
        LinkedHashMap<String, Bank> shopBanks = new LinkedHashMap<>();

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

            ShopDefinition shop = ShopFiles.parseShopFile(shopId, shopFile);
            ShopDefinition previous = shops.putIfAbsent(shop.id(), shop);
            if (previous != null) {
                throw new IOException("Duplicate shop id '" + shop.id() + "' in " + previous.sourceFile() + " and " + shop.sourceFile() + ".");
            }

            Path localBankFile = BankFiles.resolveLocalBankFile(entry);
            if (Files.isRegularFile(localBankFile)) {
                shopBanks.put(shop.id(), BankFiles.loadBankFile(localBankFile));
            }
        }

        Bank globalBank = BankFiles.loadGlobalBank();
        return new RegistryState(Map.copyOf(shops), Map.copyOf(shopBanks), BankFiles.copyBank(globalBank));
    }

    public record ReloadSummary(int shopCount, int localBankCount, Path globalBankFile) {
    }

    private record RegistryState(Map<String, ShopDefinition> shops, Map<String, Bank> shopBanks, Bank globalBank) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), Map.of(), new Bank(new ArrayList<>()));
        }
    }
}
