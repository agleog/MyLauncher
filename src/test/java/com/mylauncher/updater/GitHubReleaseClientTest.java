package com.mylauncher.updater;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseClientTest {
    @Test
    void comparesReleaseVersionsNumerically() throws IOException {
        assertTrue(GitHubReleaseClient.isNewerVersion("v0.10.0", "0.9.9"));
        assertFalse(GitHubReleaseClient.isNewerVersion("v0.1.0", "0.1.0"));
        assertFalse(GitHubReleaseClient.isNewerVersion("0.1.9", "0.2.0"));
    }

    @Test
    void validatesConfiguredRepository() {
        assertEquals("agleog/MyLauncher", GitHubReleaseClient.validateRepository("agleog/MyLauncher"));
        assertThrows(IllegalArgumentException.class,
                () -> GitHubReleaseClient.validateRepository("https://example.com/attacker"));
    }

    @Test
    void rejectsUnsupportedReleaseVersion() {
        assertThrows(IOException.class, () -> GitHubReleaseClient.isNewerVersion("latest", "0.1.0"));
    }
}
