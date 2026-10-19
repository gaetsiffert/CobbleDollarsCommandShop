package fr.cobbledollars.commandshops.gametest;

import java.io.IOException;
import java.nio.file.Files;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import fr.cobbledollars.commandshops.admin.config.AdminConfigService;
import fr.cobbledollars.commandshops.admin.runtime.AdminLiveService;
import fr.cobbledollars.commandshops.command.CommandSettings;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.cobbledollars.commandshops.shop.ShopVisibilityData;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.GameType;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import net.neoforged.fml.ModList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

@ForEachTest(idPrefix = "commands.", groups = "commands", side = Dist.DEDICATED_SERVER)
public final class CommandShopCommandGameTests {
    private CommandShopCommandGameTests() {
    }

    @TestHolder(value = "player_open_setting_preserves_admin_and_targeted_access", title = "Player opening can be disabled without blocking administrators or targeted opens")
    @GameTest(batch = "commands.player_open_setting", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void player_open_setting_preserves_admin_and_targeted_access(ExtendedGameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        var admin = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        player.addTag("command_settings_target");
        byte[] original = Files.readAllBytes(CommandSettings.getConfigFile());
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 2, false));
        Runnable cleanup = () -> {
            CommandShopSessions.cleanupPlayer(player.getUUID());
            CommandShopSessions.cleanupPlayer(admin.getUUID());
            server.getPlayerList().deop(admin.getGameProfile());
            try {
                Files.write(CommandSettings.getConfigFile(), original);
                AdminConfigService.reload(server, "GameTest cleanup", null);
            } catch (IOException exception) {
                helper.fail("Could not restore command settings: " + exception.getMessage());
            }
        };
        try {
            var dispatcher = server.getCommands().getDispatcher();
            var publicSource = player.createCommandSourceStack().withPermission(0);
            var adminSource = admin.createCommandSourceStack().withPermission(2);
            String open = "cdshops open general_store";
            AdminConfigService.reload(server, "GameTest setup", null);
            setPlayerOpen(helper, true);
            var staleParse = dispatcher.parse(open, publicSource);
            assertCommandDenied(helper, open + " @s", publicSource);

            setPlayerOpen(helper, false);
            helper.assertTrue(!CommandSettings.allowPlayerOpen(), "Saving must immediately apply the setting.");
            CommandSettings.clear();
            CommandSettings.reload();
            helper.assertTrue(!CommandSettings.allowPlayerOpen(), "The setting must survive reloading from disk.");
            var openNode = dispatcher.getRoot().getChild("cdshops").getChild("open");
            for (int permission = 0; permission <= 4; permission++) {
                helper.assertValueEqual(permission >= 2, openNode.canUse(publicSource.withPermission(permission)),
                        "Disabled player opening must allow only permission level 2 and above.");
            }
            assertCommandDenied(helper, open, publicSource);
            assertCommandDenied(helper, open, publicSource.withPermission(1));
            assertCommandDenied(helper, open + " @s", publicSource);
            boolean staleDenied = false;
            try { dispatcher.execute(staleParse); }
            catch (CommandSyntaxException expected) { staleDenied = true; }
            helper.assertTrue(staleDenied, "Commands parsed before disabling must also be denied.");
            helper.assertValueEqual(1, dispatcher.execute(open, adminSource), "Administrators must retain self-opening.");
            helper.assertValueEqual(1, dispatcher.execute(open + " @a[tag=command_settings_target]",
                    server.createCommandSourceStack().withPermission(2)), "Targeted server/NPC opens must still work.");
        } catch (Exception | AssertionError failure) {
            cleanup.run();
            throw failure;
        }
        helper.runAtTickTime(6, () -> {
            try {
                helper.assertTrue(player.containerMenu instanceof ShopMenu, "Targeted opening must reach the non-admin player's menu.");
                helper.assertTrue(admin.containerMenu instanceof ShopMenu, "The administrator's own shop must open.");
                player.closeContainer();
                CommandShopSessions.cleanupPlayer(player.getUUID());
                setPlayerOpen(helper, true);
                helper.assertValueEqual(1, server.getCommands().getDispatcher().execute("cdshops open general_store",
                        player.createCommandSourceStack().withPermission(0)), "Re-enabling must restore public self-opening.");
            } catch (Exception | AssertionError failure) {
                cleanup.run();
                helper.fail("Command access test failed: " + failure.getMessage());
            }
        });
        helper.runAtTickTime(12, () -> {
            try {
                helper.assertTrue(player.containerMenu instanceof ShopMenu, "Re-enabled self-opening must reach the player's menu.");
            } finally {
                cleanup.run();
            }
            helper.succeed();
        });
    }

    private static void setPlayerOpen(ExtendedGameTestHelper helper, boolean enabled) throws IOException {
        var server = helper.getLevel().getServer();
        JsonObject draft = JsonParser.parseString(AdminConfigService.snapshot(server)).getAsJsonObject();
        draft.getAsJsonObject("files").getAsJsonObject("commands.json").addProperty("allow_player_open", enabled);
        var result = AdminConfigService.save(server, draft.toString());
        helper.assertTrue(result.success(), "The administrator setting must save successfully: " + result.message());
    }

    private static void assertCommandDenied(ExtendedGameTestHelper helper, String command, CommandSourceStack source) {
        boolean denied = false;
        try { source.getServer().getCommands().getDispatcher().execute(command, source); }
        catch (CommandSyntaxException expected) { denied = true; }
        helper.assertTrue(denied, "The command must be denied: " + command);
    }

    @TestHolder(value = "easy_npc_command_source_opens_shop", title = "Easy NPC command sources open shops")
    @GameTest(batch = "commands.easy_npc", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void easy_npc_command_source_opens_shop(ExtendedGameTestHelper helper) {
        verifyNpcCommandSource(helper, "easy_npc");
    }

    @TestHolder(value = "customnpcs_command_source_opens_shop", title = "CustomNPCs command sources open shops")
    @GameTest(batch = "commands.customnpcs", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void customnpcs_command_source_opens_shop(ExtendedGameTestHelper helper) {
        verifyNpcCommandSource(helper, "customnpcs");
    }

    private static void verifyNpcCommandSource(ExtendedGameTestHelper helper, String modId) {
        if (!ModList.get().isLoaded(modId)) {
            helper.succeed();
            return;
        }
        var typeId = BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                .filter(id -> id.getNamespace().equals(modId))
                .sorted()
                .findFirst().orElseThrow();
        var npc = BuiltInRegistries.ENTITY_TYPE.get(typeId).create(helper.getLevel());
        helper.assertTrue(npc != null, "Expected an NPC entity from " + modId);
        var player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        npc.moveTo(player.position());
        helper.getLevel().addFreshEntity(npc);
        try {
            int result = helper.getLevel().getServer().getCommands().getDispatcher().execute(
                    "cdshops open general_store " + player.getGameProfile().getName(),
                    npc.createCommandSourceStack().withPermission(2).withSuppressedOutput());
            helper.assertValueEqual(1, result, "The NPC command should queue a shop for the target player.");
        } catch (CommandSyntaxException exception) {
            npc.discard();
            helper.fail("NPC command failed: " + exception.getMessage());
            return;
        }
        helper.runAtTickTime(6, () -> {
            try {
                helper.assertTrue(player.containerMenu instanceof ShopMenu, "The NPC command should open the target player's shop.");
                helper.succeed();
            } finally {
                npc.discard();
                CommandShopSessions.cleanupPlayer(player.getUUID());
            }
        });
    }

    @TestHolder(
            value = "command_tree_is_registered",
            title = "Command tree is registered",
            description = "Verifies that the cdshops root command and its main subcommands are present on the dedicated server."
    )
    @GameTest(batch = "commands.read_only", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void command_tree_is_registered(ExtendedGameTestHelper helper) {
        CommandNode<CommandSourceStack> root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("cdshops");
        CommandSourceStack adminSource = helper.getLevel().getServer().createCommandSourceStack().withPermission(4);
        CommandSourceStack publicSource = helper.getLevel().getServer().createCommandSourceStack().withPermission(0);
        helper.assertTrue(root != null, "The /cdshops root command was not registered.");
        helper.assertTrue(root.getChild("admin") != null, "The /cdshops admin subcommand is missing.");
        helper.assertTrue(root.getChild("open") != null, "The /cdshops open subcommand is missing.");
        helper.assertTrue(root.getChild("reload") != null, "The /cdshops reload subcommand is missing.");
        helper.assertTrue(root.getChild("restock") == null, "Restocking should be available through the admin panel only.");
        helper.assertTrue(root.getChild("stock") == null, "Stock inspection should be available through the admin panel only.");
        helper.assertTrue(root.getChild("visibility") == null, "Visibility should be available through the admin panel only.");
        helper.assertTrue(root.getChild("list") == null, "Shop listing should be available through the admin panel only.");
        helper.assertTrue(root.getChild("where") != null, "The /cdshops where subcommand is missing.");
        helper.assertTrue(root.getChild("stats") == null, "Audit browsing should be available through the admin panel only.");
        helper.assertTrue(root.canUse(adminSource),
                "The /cdshops root command should remain available to permissioned sources.");
        helper.assertTrue(root.canUse(publicSource),
                "The /cdshops root command should remain visible to public sources.");
        helper.assertTrue(root.getChild("open").canUse(publicSource),
                "The /cdshops open subcommand should remain visible to public sources.");
        helper.assertTrue(!root.getChild("reload").canUse(publicSource),
                "The /cdshops reload subcommand should remain hidden from public sources.");
        helper.assertTrue(!root.getChild("admin").canUse(publicSource),
                "The /cdshops admin subcommand should remain hidden from public sources.");
        helper.assertTrue(!root.getChild("where").canUse(publicSource),
                "The /cdshops where subcommand should remain hidden from public sources.");
        helper.succeed();
    }

    @TestHolder(
            value = "reload_command_updates_registry",
            title = "Reload command updates registry",
            description = "Executes /cdshops reload and verifies that a temporary shop is added then removed from the registry."
    )
    @GameTest(batch = "commands.reload_registry", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void reload_command_updates_registry(ExtendedGameTestHelper helper) {
        try {
            CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.COMMAND_RELOAD_SHOP_ID);

            int initialShopCount = ShopRegistry.listShopIds().size();
            CommandShopGameTestSupport.writeShopJson(CommandShopGameTestSupport.COMMAND_RELOAD_SHOP_ID, """
                    {
                      "categories": [
                        {
                          "name": "Command Reload",
                          "offers": [
                            {
                              "id": "diamond_bundle",
                              "match": {
                                "include": [
                                  { "item": "minecraft:diamond" }
                                ]
                              },
                              "count": 2,
                              "price": 75
                            }
                          ]
                        }
                      ]
                    }
                    """);

            int addResult = CommandShopGameTestSupport.executeCommand(helper, "cdshops reload");
            helper.assertValueEqual(1, addResult, "/cdshops reload did not report success when adding a shop.");
            helper.assertValueEqual(initialShopCount + 1, ShopRegistry.listShopIds().size(),
                    "The reload command did not add the temporary shop.");

            ShopDefinition shop = ShopRegistry.getShop(CommandShopGameTestSupport.COMMAND_RELOAD_SHOP_ID);
            helper.assertTrue(shop != null, "The temporary shop was not registered after /cdshops reload.");
            helper.assertValueEqual(1, shop.offers().size(), "The temporary shop offer count is incorrect after reload.");

            CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.COMMAND_RELOAD_SHOP_ID);
            int removeResult = CommandShopGameTestSupport.executeCommand(helper, "cdshops reload");
            helper.assertValueEqual(1, removeResult, "/cdshops reload did not report success when removing a shop.");
            helper.assertValueEqual(initialShopCount, ShopRegistry.listShopIds().size(),
                    "The reload command did not remove the deleted temporary shop.");
            helper.assertTrue(ShopRegistry.getShop(CommandShopGameTestSupport.COMMAND_RELOAD_SHOP_ID) == null,
                    "The deleted temporary shop remained in the registry after /cdshops reload.");
        } catch (IOException | CommandSyntaxException exception) {
            helper.fail("Reload command GameTest failed: " + exception.getMessage());
            return;
        } finally {
            try {
                CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.COMMAND_RELOAD_SHOP_ID);
                ShopRegistry.reload(helper.getLevel().registryAccess());
            } catch (IOException ignored) {
            }
        }

        helper.succeed();
    }

    @TestHolder(
            value = "admin_visibility_toggles_shop_state",
            title = "Admin visibility toggles shop state",
            description = "Uses the administrator service to disable and enable a shop with its custom denial message and author."
    )
    @GameTest(batch = "commands.visibility_state", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void admin_visibility_toggles_shop_state(ExtendedGameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var admin = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 2, false));
        ShopVisibilityData visibilityData = ShopVisibilityData.get(server);
        try {
            visibilityData.enable("general_store");

            AdminLiveService.execute(admin, "visibility", "general_store", "", "", "", 0,
                    false, "Maintenance window", false);

            ShopVisibilityData.VisibilityStatus disabledStatus = visibilityData.status("general_store");
            helper.assertTrue(!disabledStatus.enabled(), "general_store should be disabled after the administrator action.");
            helper.assertValueEqual("Maintenance window", disabledStatus.message(),
                    "The visibility disable message was not persisted correctly.");
            helper.assertValueEqual(admin.getGameProfile().getName(), disabledStatus.changedBy(),
                    "The administrator who changed visibility was not recorded.");

            AdminLiveService.execute(admin, "visibility", "general_store", "", "", "", 0,
                    true, "", false);
            helper.assertTrue(visibilityData.status("general_store").enabled(),
                    "general_store should be enabled again after the administrator action.");
        } finally {
            visibilityData.enable("general_store");
            server.getPlayerList().deop(admin.getGameProfile());
            CommandShopSessions.cleanupPlayer(admin.getUUID());
        }

        helper.succeed();
    }
}
