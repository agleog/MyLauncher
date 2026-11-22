package com.mylauncher.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElyByAuthServiceTest {
    private static final String CLIENT_TOKEN = "12345678-1234-1234-1234-123456789abc";

    @Test
    void acceptsLoginAndOneTimeTwoFactorCode() {
        ElyByAuthService.validateCredentials("player@example.com", "password", CLIENT_TOKEN);
        ElyByAuthService.validateCredentials("player", "password", CLIENT_TOKEN);
    }

    @Test
    void rejectsBlankCredentialsAndMalformedClientToken() {
        assertThrows(IllegalArgumentException.class,
                () -> ElyByAuthService.validateCredentials("", "password", CLIENT_TOKEN));
        assertThrows(IllegalArgumentException.class,
                () -> ElyByAuthService.validateCredentials("player", "", CLIENT_TOKEN));
        assertThrows(IllegalArgumentException.class,
                () -> ElyByAuthService.validateCredentials("player", "password", "not-a-uuid"));
    }

    @Test
    void parsesElyByProfileWithoutRetainingThePassword() throws IOException {
        String response = """
                {
                  "accessToken": "ely-session-token",
                  "clientToken": "%s",
                  "selectedProfile": {
                    "id": "0123456789ABCDEF0123456789ABCDEF",
                    "name": "ElyPlayer"
                  }
                }
                """.formatted(CLIENT_TOKEN);

        MinecraftAccount account = ElyByAuthService.parseAccount(
                new ObjectMapper().readTree(response), CLIENT_TOKEN);

        assertEquals("0123456789abcdef0123456789abcdef", account.uuid());
        assertEquals("ElyPlayer", account.username());
        assertEquals("ely-session-token", account.sessionToken());
        assertEquals(CLIENT_TOKEN, account.clientToken());
        assertEquals(AuthProvider.ELY_BY, account.authProvider());
    }

    @Test
    void rejectsUnexpectedClientTokenAndMissingProfile() throws IOException {
        String unexpectedToken = """
                {
                  "accessToken": "session",
                  "clientToken": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "selectedProfile": {"id": "0123456789abcdef0123456789abcdef", "name": "ElyPlayer"}
                }
                """;
        String missingProfile = """
                {"accessToken": "session", "clientToken": "%s"}
                """.formatted(CLIENT_TOKEN);

        assertThrows(IOException.class, () -> ElyByAuthService.parseAccount(
                new ObjectMapper().readTree(unexpectedToken), CLIENT_TOKEN));
        assertThrows(IOException.class, () -> ElyByAuthService.parseAccount(
                new ObjectMapper().readTree(missingProfile), CLIENT_TOKEN));
    }
}
