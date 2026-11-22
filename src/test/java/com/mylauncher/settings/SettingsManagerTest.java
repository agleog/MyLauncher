package com.mylauncher.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettingsManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsAllSettings() throws IOException {
        SettingsManager manager = new SettingsManager(temporaryDirectory.resolve("settings.properties"));
        LauncherSettings expected = new LauncherSettings(
                6, 1920, 1080, true, "-XX:+UseG1GC",
                temporaryDirectory.resolve("Launcher").toString(),
                temporaryDirectory.resolve("Game files").toString(),
                temporaryDirectory.resolve("background.png").toString()
        );

        manager.save(expected);

        assertEquals(expected, manager.load());
    }

    @Test
    void rejectsInvalidIntegerSetting() throws IOException {
        Path settingsFile = temporaryDirectory.resolve("settings.properties");
        Files.writeString(settingsFile, "memoryGiB=not-a-number\n");

        SettingsManager manager = new SettingsManager(settingsFile);

        assertThrows(IOException.class, manager::load);
    }

    @Test
    void rejectsInvalidBooleanSetting() throws IOException {
        Path settingsFile = temporaryDirectory.resolve("settings.properties");
        Files.writeString(settingsFile, "fullscreen=sometimes\n");

        SettingsManager manager = new SettingsManager(settingsFile);

        assertThrows(IOException.class, manager::load);
    }
}
