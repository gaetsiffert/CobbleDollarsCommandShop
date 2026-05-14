package fr.cobbledollars.commandshops.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

final class CommandShopGameTestSupport {
    static final String CUSTOM_SHOP_ID = "gametest_runtime_shop";
    static final String INVALID_SHOP_ID = "gametest_invalid_shop";
    static final String COMMAND_RELOAD_SHOP_ID = "gametest_command_reload_shop";
    static final String STOCK_SHOP_ID = "gametest_stock_shop";

    private CommandShopGameTestSupport() {
    }

    static int executeCommand(ExtendedGameTestHelper helper, String command) throws CommandSyntaxException {
        return executeCommand(helper.getLevel().getServer(), command);
    }

    static int executeCommand(MinecraftServer server, String command) throws CommandSyntaxException {
        return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack().withPermission(4));
    }

    static int executePlayerCommand(ServerPlayer player, String command) throws CommandSyntaxException {
        return player.getServer().getCommands().getDispatcher().execute(command, player.createCommandSourceStack().withPermission(4));
    }

    static void writeShopJson(String shopId, String json) throws IOException {
        Path shopDirectory = ShopRegistry.getShopDirectory().resolve(shopId);
        Files.createDirectories(shopDirectory);
        Files.writeString(shopDirectory.resolve("shop.json"), json);
    }

    static void writeLocalBankJson(String shopId, String json) throws IOException {
        Path shopDirectory = ShopRegistry.getShopDirectory().resolve(shopId);
        Files.createDirectories(shopDirectory);
        Files.writeString(shopDirectory.resolve("bank.json"), json);
    }

    static void cleanupShop(String shopId) throws IOException {
        deleteRecursively(ShopRegistry.getShopDirectory().resolve(shopId));
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(currentPath -> {
                try {
                    Files.deleteIfExists(currentPath);
                } catch (IOException exception) {
                    throw new RuntimeException("Failed to delete GameTest path: " + currentPath, exception);
                }
            });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }
}
