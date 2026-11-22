package com.mylauncher.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mylauncher.auth.AuthProvider;
import com.mylauncher.auth.MinecraftAccount;
import com.mylauncher.settings.LauncherSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftGameLauncherTest {
    @TempDir
    Path temporaryDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsCommandFromTheNeoForgeProfileAndInheritedMinecraftArguments() throws Exception {
        Path client = temporaryDirectory.resolve("versions/1.21.1/1.21.1.jar");
        Files.createDirectories(client.getParent());
        Files.writeString(client, "client");
        Path transformedClient = temporaryDirectory.resolve(
                "libraries/net/minecraft/client/test/client-srg.jar");
        Files.createDirectories(transformedClient.getParent());
        Files.writeString(transformedClient, "transformed-client");
        JsonNode profile = objectMapper.readTree("""
                {
                  "id": "neoforge-21.1.251",
                  "inheritsFrom": "1.21.1",
                  "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
                  "arguments": {
                    "jvm": ["-p", "${library_directory}", "--add-modules", "ALL-MODULE-PATH"],
                    "game": ["--fml.mcVersion", "1.21.1"]
                  },
                  "libraries": [
                    {
                      "name": "net.minecraft:client:1.21.1:srg",
                      "downloads": {
                        "artifact": {
                          "path": "net/minecraft/client/test/client-srg.jar"
                        }
                      }
                    }
                  ]
                }
                """);
        JsonNode vanilla = objectMapper.readTree("""
                {
                  "id": "1.21.1",
                  "assetIndex": {"id": "17"},
                  "arguments": {
                    "jvm": ["-cp", "${classpath}", "-Djava.library.path=${natives_directory}"],
                    "game": [
                      "--username", "${auth_player_name}",
                      "--accessToken", "${auth_access_token}",
                      "--uuid", "${auth_uuid}",
                      "--assetsDir", "${assets_root}",
                      "--assetIndex", "${assets_index_name}",
                      "--gameDir", "${game_directory}"
                    ]
                  },
                  "libraries": []
                }
                """);
        MinecraftAccount account = new MinecraftAccount("0123456789abcdef0123456789abcdef", "Player",
                "ely-session-token", "", "01234567-89ab-cdef-0123-456789abcdef", AuthProvider.ELY_BY);
        LauncherSettings settings = new LauncherSettings(6, 1280, 720, false, "", "", "");

        List<String> command = MinecraftGameLauncher.buildCommand(temporaryDirectory,
                Path.of("runtime", "bin", "java.exe"), Path.of("authlib-injector.jar"),
                account, settings, profile, vanilla);

        assertTrue(command.contains("cpw.mods.bootstraplauncher.BootstrapLauncher"));
        assertTrue(command.contains("--fml.mcVersion"));
        assertTrue(command.contains("--username"));
        assertTrue(command.contains("Player"));
        assertTrue(command.contains("--accessToken"));
        assertTrue(command.contains("ely-session-token"));
        assertTrue(command.contains("--uuid"));
        assertTrue(command.contains(account.uuid()));
        assertTrue(command.contains("--assetsDir"));
        assertTrue(command.contains(temporaryDirectory.resolve("assets").toString()));
        assertTrue(command.contains("-Xmx6G"));
        assertTrue(command.stream().anyMatch(argument -> argument.contains(transformedClient.toString())));
        assertFalse(command.stream().anyMatch(argument -> argument.contains(client.toString())));
        assertFalse(command.stream().anyMatch(argument -> argument.contains("${")));
    }

    @Test
    void rejectsUnsupportedAccountsAndMissingClientFiles() throws Exception {
        JsonNode profile = objectMapper.readTree("""
                {"id":"neoforge-21.1.251","inheritsFrom":"1.21.1","mainClass":"Bootstrap","libraries":[]}
                """);
        JsonNode vanilla = objectMapper.readTree("""
                {"id":"1.21.1","assetIndex":{"id":"17"},"libraries":[]}
                """);
        LauncherSettings settings = new LauncherSettings(4, 1280, 720, false, "", "", "");
        MinecraftAccount account = new MinecraftAccount("0123456789abcdef0123456789abcdef", "Player",
                "token", "", "01234567-89ab-cdef-0123-456789abcdef", AuthProvider.UNSUPPORTED);

        assertThrows(IOException.class, () -> MinecraftGameLauncher.buildCommand(temporaryDirectory,
                Path.of("java.exe"), Path.of("authlib.jar"), account, settings, profile, vanilla));

        MinecraftAccount elyAccount = new MinecraftAccount(account.uuid(), account.username(), account.sessionToken(),
                "", account.clientToken(), AuthProvider.ELY_BY);
        assertThrows(IOException.class, () -> MinecraftGameLauncher.buildCommand(temporaryDirectory,
                Path.of("java.exe"), Path.of("authlib.jar"), elyAccount, settings, profile, vanilla));
    }

    @Test
    void excludesNativeClassifierLibrariesFromTheJvmClasspath() {
        assertTrue(MinecraftGameLauncher.isNativeLibrary(
                "org.lwjgl:lwjgl-tinyfd:3.3.3:natives-windows-arm64"));
        assertTrue(MinecraftGameLauncher.isNativeLibrary(
                "org.lwjgl:lwjgl:3.3.3:natives-windows"));
        assertFalse(MinecraftGameLauncher.isNativeLibrary(
                "org.lwjgl:lwjgl-tinyfd:3.3.3"));
    }

    @Test
    void redactsAccessTokensFromGameLogLines() {
        assertEquals("args [--accessToken, [REDACTED], --clientId, ]",
                MinecraftGameLauncher.sanitizeLogLine(
                        "args [--accessToken, session-secret, --clientId, ]", "session-secret")
        );
        assertEquals("args [--accessToken=[REDACTED]]",
                MinecraftGameLauncher.sanitizeLogLine(
                        "args [--accessToken=old-secret]", "current-secret")
        );
    }
}
