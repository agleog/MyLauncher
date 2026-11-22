package com.mylauncher.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLogReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsRecentGameLogAndRedactsAccessToken() throws Exception {
        Path log = temporaryDirectory.resolve("logs/mylauncher-game.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, """
                startup complete
                args [--accessToken, super-secret-token, --username, Player]
                """, StandardCharsets.UTF_8);

        String result = new GameLogReader().readLatest(temporaryDirectory);

        assertTrue(result.contains("startup complete"));
        assertTrue(result.contains("--username, Player"));
        assertTrue(result.contains("--accessToken, [СКРЫТО]"));
        assertFalse(result.contains("super-secret-token"));
    }

    @Test
    void boundsTheAmountOfLogDisplayed() throws Exception {
        Path log = temporaryDirectory.resolve("logs/mylauncher-game.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "x".repeat(300 * 1024), StandardCharsets.UTF_8);

        String result = new GameLogReader().readLatest(temporaryDirectory);

        assertTrue(result.startsWith("[Показан конец лога; предыдущие строки скрыты]"));
        assertTrue(result.length() < 260 * 1024);
    }

    @Test
    void rejectsASymbolicLinkForTheGameRoot() throws Exception {
        Path realDirectory = Files.createDirectory(temporaryDirectory.resolve("real"));
        Path link = temporaryDirectory.resolve("game-link");
        try {
            Files.createSymbolicLink(link, realDirectory);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }

        assertThrows(IOException.class, () -> new GameLogReader().readLatest(link));
    }
}
