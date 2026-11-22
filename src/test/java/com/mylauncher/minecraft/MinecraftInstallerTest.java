package com.mylauncher.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftInstallerTest {
    @TempDir
    Path temporaryDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recognizesAnExistingInstallationWithoutDownloadingItsFiles() throws Exception {
        Path versionDirectory = temporaryDirectory.resolve("versions/1.21.1");
        Path indexDirectory = temporaryDirectory.resolve("assets/indexes");
        Files.createDirectories(versionDirectory);
        Files.createDirectories(indexDirectory);
        byte[] index = "{\"objects\":{}}".getBytes(StandardCharsets.UTF_8);
        String indexSha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(index));
        byte[] client = "client".getBytes(StandardCharsets.UTF_8);
        String clientSha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(client));

        Files.write(indexDirectory.resolve("17.json"), index);
        Files.write(versionDirectory.resolve("1.21.1.jar"), client);
        Files.writeString(versionDirectory.resolve("1.21.1.json"), """
                {
                  "id": "1.21.1",
                  "downloads": {
                    "client": {
                      "url": "https://piston-data.mojang.com/client.jar",
                      "sha1": "%s",
                      "size": %d
                    }
                  },
                  "assetIndex": {
                    "id": "17",
                    "url": "https://piston-meta.mojang.com/assets.json",
                    "sha1": "%s"
                  },
                  "libraries": []
                }
                """.formatted(clientSha1, client.length, indexSha1));

        assertTrue(new MinecraftInstaller().isInstalled(temporaryDirectory));

        Files.delete(versionDirectory.resolve("1.21.1.jar"));
        assertFalse(new MinecraftInstaller().isInstalled(temporaryDirectory));
    }

    @Test
    void selectsOnlyTheRequestedReleaseFromOfficialManifest() throws IOException {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "versions": [
                    {"id": "25w01a", "type": "snapshot"},
                    {"id": "1.21.1", "type": "release", "url": "https://piston-meta.mojang.com/version.json"}
                  ]
                }
                """);

        JsonNode version = MinecraftInstaller.findVersion(manifest, "1.21.1");

        assertEquals("release", version.path("type").asText());
        assertEquals("https://piston-meta.mojang.com/version.json", version.path("url").asText());
        assertThrows(IOException.class, () -> MinecraftInstaller.findVersion(manifest, "1.21.2"));
    }

    @Test
    void rejectsDuplicateEntriesForRequestedVersion() throws IOException {
        JsonNode manifest = objectMapper.readTree("""
                {"versions": [{"id": "1.21.1"}, {"id": "1.21.1"}]}
                """);

        assertThrows(IOException.class, () -> MinecraftInstaller.findVersion(manifest, "1.21.1"));
    }

    @Test
    void allowsOnlyOfficialHttpsDownloadHosts() throws IOException {
        assertEquals(URI.create("https://libraries.minecraft.net/com/mojang/example.jar"),
                MinecraftInstaller.trustedHttpsUri(
                        "https://libraries.minecraft.net/com/mojang/example.jar"));
        assertThrows(IOException.class,
                () -> MinecraftInstaller.trustedHttpsUri("http://libraries.minecraft.net/example.jar"));
        assertThrows(IOException.class,
                () -> MinecraftInstaller.trustedHttpsUri("https://libraries.minecraft.net.example.org/file"));
        assertThrows(IOException.class,
                () -> MinecraftInstaller.trustedHttpsUri("https://user@piston-meta.mojang.com/file"));
    }

    @Test
    void rejectsPathsThatCouldEscapeTheGameDirectory() throws IOException {
        assertEquals("com/mojang/example/1.0/example.jar",
                MinecraftInstaller.safeRelativePath("com/mojang/example/1.0/example.jar").toString()
                        .replace('\\', '/'));
        assertThrows(IOException.class, () -> MinecraftInstaller.safeRelativePath("../outside.jar"));
        assertThrows(IOException.class, () -> MinecraftInstaller.safeRelativePath("libraries/../../outside.jar"));
        assertThrows(IOException.class, () -> MinecraftInstaller.safeRelativePath("C:/outside.jar"));
        assertThrows(IOException.class, () -> MinecraftInstaller.safeRelativePath("..\\outside.jar"));
    }

    @Test
    void appliesMinecraftLibraryRulesForWindowsArchitectureAndVersion() throws IOException {
        JsonNode library = objectMapper.readTree("""
                {
                  "rules": [
                    {"action": "allow", "os": {"name": "windows", "arch": "64"}},
                    {"action": "disallow", "os": {"name": "windows", "version": "^11\\\\."}}
                  ]
                }
                """);

        assertTrue(MinecraftInstaller.libraryAllowed(library, "windows", "amd64", "10.0.19045"));
        assertFalse(MinecraftInstaller.libraryAllowed(library, "windows", "amd64", "11.0.22631"));
        assertFalse(MinecraftInstaller.libraryAllowed(library, "linux", "amd64", "6.8"));
    }

    @Test
    void selectsOnlyX64WindowsNativeLibraries() {
        assertTrue(MinecraftInstaller.windowsX64NativeVariant("org.lwjgl:lwjgl:3.3.3:natives-windows"));
        assertFalse(MinecraftInstaller.windowsX64NativeVariant(
                "org.lwjgl:lwjgl:3.3.3:natives-windows-arm64"));
        assertFalse(MinecraftInstaller.windowsX64NativeVariant(
                "org.lwjgl:lwjgl:3.3.3:natives-windows-x86"));
        assertTrue(MinecraftInstaller.isWindowsNativeLibrary(
                "org.lwjgl:lwjgl:3.3.3:natives-windows"));
    }

    @Test
    void validatesFileIntegrityAgainstTheOfficialSha1() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        MinecraftInstaller.verifySha1(content, "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", "тестовый файл");

        assertThrows(IOException.class, () -> MinecraftInstaller.verifySha1(
                content, "0000000000000000000000000000000000000000", "тестовый файл"));
    }
}
