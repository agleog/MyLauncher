package com.mylauncher.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeInstallerTest {
    @TempDir
    Path temporaryDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void targetsOnlyTheConfiguredMinecraftAndNeoForgeVersions() {
        assertEquals("1.21.1", NeoForgeInstaller.MINECRAFT_VERSION);
        assertEquals("21.1.251", NeoForgeInstaller.VERSION);
        assertEquals("neoforge-21.1.251", NeoForgeInstaller.PROFILE_ID);
    }

    @Test
    void acceptsOnlyTheExpectedGeneratedVersionProfile() throws IOException {
        JsonNode profile = objectMapper.readTree("""
                {
                  "id": "neoforge-21.1.251",
                  "inheritsFrom": "1.21.1",
                  "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher"
                }
                """);
        JsonNode wrongLoader = objectMapper.readTree("""
                {"id": "neoforge-21.1.250", "inheritsFrom": "1.21.1"}
                """);
        JsonNode wrongMinecraft = objectMapper.readTree("""
                {"id": "neoforge-21.1.251", "inheritsFrom": "1.21.2"}
                """);

        assertTrue(NeoForgeInstaller.isExpectedProfile(profile));
        assertFalse(NeoForgeInstaller.isExpectedProfile(wrongLoader));
        assertFalse(NeoForgeInstaller.isExpectedProfile(wrongMinecraft));
    }

    @Test
    void recognizesOnlyACompleteNeoForgeProfile() throws IOException {
        assertFalse(new NeoForgeInstaller().isInstalled(temporaryDirectory.resolve("not-installed")));

        Path versionDirectory = temporaryDirectory.resolve("versions")
                .resolve(NeoForgeInstaller.PROFILE_ID);
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve(NeoForgeInstaller.PROFILE_ID + ".json"), """
                {"id":"neoforge-21.1.251","inheritsFrom":"1.21.1",
                 "mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher"}
                """);
        assertFalse(new NeoForgeInstaller().isInstalled(temporaryDirectory));

        Path libraryDirectory = temporaryDirectory.resolve("libraries/net/neoforged/neoforge")
                .resolve(NeoForgeInstaller.VERSION);
        Files.createDirectories(libraryDirectory);
        Files.createFile(libraryDirectory.resolve("neoforge-21.1.251-client.jar"));

        assertTrue(new NeoForgeInstaller().isInstalled(temporaryDirectory));
    }

    @Test
    void acceptsOnlyTheOfficialNeoForgeHttpsHost() throws IOException {
        assertEquals(URI.create("https://maven.neoforged.net/releases/example.jar"),
                NeoForgeInstaller.trustedInstallerUri(
                        "https://maven.neoforged.net/releases/example.jar"));
        assertThrows(IOException.class,
                () -> NeoForgeInstaller.trustedInstallerUri("http://maven.neoforged.net/example.jar"));
        assertThrows(IOException.class,
                () -> NeoForgeInstaller.trustedInstallerUri("https://maven.neoforged.net.example.org/file"));
        assertThrows(IOException.class,
                () -> NeoForgeInstaller.trustedInstallerUri("https://user@maven.neoforged.net/file"));
    }
}
