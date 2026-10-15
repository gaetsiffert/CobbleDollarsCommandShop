package fr.cobbledollars.commandshops.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankFilesParsingTest {
    @TempDir
    Path tempDir;

    @Test
    void loadBankFileParsesFlatOfferArraysIntoDefaultCategory() throws IOException {
        Path bankFile = writeBankJson("""
                [
                  {
                    "match": {
                      "include": [
                        { "item": "minecraft:diamond" }
                      ]
                    },
                    "price": 75
                  }
                ]
                """);

        BankDefinition bank = BankFiles.loadBankFile(bankFile, TestRegistryAccess.provider());

        assertEquals(1, bank.categories().size());
        assertEquals("Default", bank.categories().getFirst().name());
        assertEquals(1, bank.categories().getFirst().offers().size());
    }

    @Test
    void loadBankFileRejectsObjectsWithBothOffersAndCategories() throws IOException {
        Path bankFile = writeBankJson("""
                {
                  "offers": [],
                  "categories": []
                }
                """);

        IOException exception = assertThrows(
                IOException.class,
                () -> BankFiles.loadBankFile(bankFile, TestRegistryAccess.provider())
        );

        assertEquals(
                "Bank file '" + bankFile + "' must define exactly one of 'offers' or 'categories'.",
                exception.getMessage()
        );
    }

    @Test
    void loadBankFileRejectsObjectsWithoutOffersOrCategories() throws IOException {
        Path bankFile = writeBankJson("""
                {
                  "conditions": {
                    "player_tags_none": ["blocked"]
                  }
                }
                """);

        IOException exception = assertThrows(
                IOException.class,
                () -> BankFiles.loadBankFile(bankFile, TestRegistryAccess.provider())
        );

        assertEquals(
                "Bank file '" + bankFile + "' must define exactly one of 'offers' or 'categories'.",
                exception.getMessage()
        );
    }

    @Test
    void loadBankFileRejectsLegacyCountField() throws IOException {
        Path bankFile = writeBankJson("""
                {
                  "offers": [
                    {
                      "match": {
                        "include": [
                          { "item": "minecraft:coal" }
                        ]
                      },
                      "count": 64,
                      "price": 3
                    }
                  ]
                }
                """);

        IOException exception = assertThrows(
                IOException.class,
                () -> BankFiles.loadBankFile(bankFile, TestRegistryAccess.provider())
        );

        assertEquals(
                "Field 'count' is not supported in bank offers anymore. Remove it from bank file '" + bankFile + "', offer #0.",
                exception.getMessage()
        );
    }

    @Test
    void loadBankFilePreservesOrderedAccessRulesAndFallbackMessage() throws IOException {
        Path bankFile = writeBankJson("""
                {
                  "deny_message": "Legacy bank access message",
                  "conditions": [
                    { "dimensions_any": ["minecraft:the_nether"], "deny_message": "Visit the Nether bank." },
                    { "player_tags_all": ["bank_permit"], "deny_message": "You need a banking permit." }
                  ],
                  "categories": []
                }
                """);

        BankDefinition bank = BankFiles.loadBankFile(bankFile, TestRegistryAccess.provider());

        assertEquals("Legacy bank access message", bank.denyMessage());
        JsonObject serialized = new JsonObject();
        ConditionSet.writeOptional(serialized, bank.conditions());
        assertEquals(JsonParser.parseString(Files.readString(bankFile)).getAsJsonObject().get("conditions"), serialized.get("conditions"));
    }

    @Test
    void loadBankFileTreatsBlankFallbackMessageAsUnset() throws IOException {
        BankDefinition bank = BankFiles.loadBankFile(
                writeBankJson("{\"deny_message\": \"   \", \"offers\": []}"), TestRegistryAccess.provider());

        assertNull(bank.denyMessage());
    }

    @Test
    void loadBankFileRejectsNonStringFallbackMessage() throws IOException {
        Path bankFile = writeBankJson("{\"deny_message\": 12, \"offers\": []}");

        IOException exception = assertThrows(IOException.class,
                () -> BankFiles.loadBankFile(bankFile, TestRegistryAccess.provider()));

        assertEquals("Field 'deny_message' in bank file '" + bankFile + "' must be a string.", exception.getMessage());
    }

    private Path writeBankJson(String json) throws IOException {
        Path bankFile = tempDir.resolve("bank.json");
        Files.writeString(bankFile, json);
        return bankFile;
    }
}
