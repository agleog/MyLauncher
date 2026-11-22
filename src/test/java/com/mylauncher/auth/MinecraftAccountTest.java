package com.mylauncher.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinecraftAccountTest {
    @Test
    void normalizesUuidAndDoesNotExposeSessionTokenInStringRepresentation() {
        MinecraftAccount account = new MinecraftAccount(
                "0123456789ABCDEF0123456789ABCDEF", "Player", "secret-session-token", "",
                "12345678-1234-1234-1234-123456789abc", AuthProvider.ELY_BY);

        assertEquals("0123456789abcdef0123456789abcdef", account.uuid());
        assertFalse(account.toString().contains("secret-session-token"));
        assertEquals(AuthProvider.ELY_BY, account.authProvider());
    }

    @Test
    void rejectsUntrustedSkinUrls() {
        assertThrows(IllegalArgumentException.class, () -> new MinecraftAccount(
                "0123456789abcdef0123456789abcdef", "Player", "refresh-token", "https://example.com/skin.png"));
    }

    @Test
    void treatsAccountsFromUnsupportedProvidersAsIneligibleForElyBy() throws IOException {
        String savedAccount = """
                {
                  "uuid": "0123456789abcdef0123456789abcdef",
                  "username": "OldPlayer",
                  "refreshToken": "legacy-provider-session-token",
                  "skinUrl": "",
                  "authProvider": "LEGACY"
                }
                """;

        MinecraftAccount account = new ObjectMapper().readValue(savedAccount, MinecraftAccount.class);

        assertEquals(AuthProvider.UNSUPPORTED, account.authProvider());
        assertEquals("legacy-provider-session-token", account.sessionToken());
        assertEquals("", account.clientToken());
    }
}
