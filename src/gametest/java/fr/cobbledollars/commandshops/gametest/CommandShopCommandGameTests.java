package fr.cobbledollars.commandshops.gametest;

import java.io.IOException;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.cobbledollars.commandshops.shop.ShopVisibilityData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

@ForEachTest(idPrefix = "commands.", groups = "commands", side = Dist.DEDICATED_SERVER)
public final class CommandShopCommandGameTests {
    private CommandShopCommandGameTests() {
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
        helper.assertTrue(root != null, "The /cdshops root command was not registered.");
        helper.assertTrue(root.getChild("open") != null, "The /cdshops open subcommand is missing.");
        helper.assertTrue(root.getChild("reload") != null, "The /cdshops reload subcommand is missing.");
        helper.assertTrue(root.getChild("restock") != null, "The /cdshops restock subcommand is missing.");
        helper.assertTrue(root.getChild("stock") != null, "The /cdshops stock subcommand is missing.");
        helper.assertTrue(root.getChild("visibility") != null, "The /cdshops visibility subcommand is missing.");
        helper.assertTrue(root.getChild("list") != null, "The /cdshops list subcommand is missing.");
        helper.assertTrue(root.getChild("where") != null, "The /cdshops where subcommand is missing.");
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
            value = "visibility_commands_toggle_shop_state",
            title = "Visibility commands toggle shop state",
            description = "Executes the visibility subcommands and verifies the persisted visibility state of a shop."
    )
    @GameTest(batch = "commands.visibility_state", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void visibility_commands_toggle_shop_state(ExtendedGameTestHelper helper) {
        ShopVisibilityData visibilityData = ShopVisibilityData.get(helper.getLevel().getServer());
        try {
            visibilityData.enable("general_store");

            int disableResult = CommandShopGameTestSupport.executeCommand(
                    helper,
                    "cdshops visibility general_store disable Maintenance window"
            );
            helper.assertValueEqual(1, disableResult, "The visibility disable command did not report success.");

            ShopVisibilityData.VisibilityStatus disabledStatus = visibilityData.status("general_store");
            helper.assertTrue(!disabledStatus.enabled(), "general_store should be disabled after the visibility command.");
            helper.assertValueEqual("Maintenance window", disabledStatus.message(),
                    "The visibility disable message was not persisted correctly.");
            helper.assertTrue(disabledStatus.changedBy() != null && !disabledStatus.changedBy().isBlank(),
                    "The visibility change author was not recorded.");

            int enableResult = CommandShopGameTestSupport.executeCommand(helper, "cdshops visibility general_store enable");
            helper.assertValueEqual(1, enableResult, "The visibility enable command did not report success.");
            helper.assertTrue(visibilityData.status("general_store").enabled(),
                    "general_store should be enabled again after the visibility enable command.");
        } catch (CommandSyntaxException exception) {
            helper.fail("Visibility command GameTest failed: " + exception.getMessage());
            return;
        } finally {
            visibilityData.enable("general_store");
        }

        helper.succeed();
    }
}
