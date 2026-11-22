package com.mylauncher.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SecureAccountStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void encryptsAndRestoresAccountsWithWindowsDpapi() throws IOException {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
        Path file = temporaryDirectory.resolve("accounts.dat");
        SecureAccountStore store = new SecureAccountStore(file);
        MinecraftAccount account = new MinecraftAccount(
                "0123456789abcdef0123456789abcdef", "Player", "token-that-must-not-be-plaintext", "",
                "12345678-1234-1234-1234-123456789abc", AuthProvider.ELY_BY);

        store.save(new SecureAccountStore.StoredAccounts(java.util.List.of(account), account.uuid()));

        byte[] encrypted = Files.readAllBytes(file);
        assertFalse(new String(encrypted, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(account.sessionToken()));
        assertEquals(new SecureAccountStore.StoredAccounts(java.util.List.of(account), account.uuid()), store.load());
    }
}
