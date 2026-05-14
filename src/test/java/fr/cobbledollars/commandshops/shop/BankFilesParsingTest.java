package fr.cobbledollars.commandshops.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private Path writeBankJson(String json) throws IOException {
        Path bankFile = tempDir.resolve("bank.json");
        Files.writeString(bankFile, json);
        return bankFile;
    }
}
