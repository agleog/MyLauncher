package com.mylauncher.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class SettingsManager {
    private static final String APP_DIRECTORY = "MyLauncher";
    private final Path settingsFile;

    public SettingsManager() {
        this(defaultConfigDirectory().resolve("settings.properties"));
    }

    SettingsManager(Path settingsFile) {
        this.settingsFile = settingsFile;
    }

    public LauncherSettings load() throws IOException {
        Properties properties = new Properties();
        if (Files.exists(settingsFile)) {
            try (InputStream input = Files.newInputStream(settingsFile)) {
                properties.load(input);
            }
        }

        LauncherSettings defaults = defaults();
        return new LauncherSettings(
                readInt(properties, "memoryGiB", defaults.memoryGiB()),
                readInt(properties, "windowWidth", defaults.windowWidth()),
                readInt(properties, "windowHeight", defaults.windowHeight()),
                readBoolean(properties, defaults.fullscreen()),
                properties.getProperty("jvmArguments", defaults.jvmArguments()),
                properties.getProperty("launcherDirectory", defaults.launcherDirectory()),
                properties.getProperty("gameDirectory", defaults.gameDirectory())
        );
    }

    public void save(LauncherSettings settings) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("memoryGiB", Integer.toString(settings.memoryGiB()));
        properties.setProperty("windowWidth", Integer.toString(settings.windowWidth()));
        properties.setProperty("windowHeight", Integer.toString(settings.windowHeight()));
        properties.setProperty("fullscreen", Boolean.toString(settings.fullscreen()));
        properties.setProperty("jvmArguments", settings.jvmArguments());
        properties.setProperty("launcherDirectory", settings.launcherDirectory());
        properties.setProperty("gameDirectory", settings.gameDirectory());

        Path parent = settingsFile.getParent();
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, "settings-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                properties.store(output, "MyLauncher settings");
            }
            try {
                Files.move(temporaryFile, settingsFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public static LauncherSettings defaults() {
        return new LauncherSettings(
                4,
                1280,
                720,
                false,
                "",
                defaultLocalDirectory().resolve(APP_DIRECTORY).toString(),
                defaultRoamingDirectory().resolve(".mylauncher").resolve("Game").toString()
        );
    }

    private int readInt(Properties properties, String key, int defaultValue) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IOException("Некорректное значение настройки " + key + " в " + settingsFile, exception);
        }
    }

    private static boolean readBoolean(Properties properties, boolean defaultValue) throws IOException {
        String value = properties.getProperty("fullscreen");
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IOException("Некорректное значение настройки fullscreen");
    }

    private static Path defaultConfigDirectory() {
        return defaultRoamingDirectory().resolve(APP_DIRECTORY);
    }

    private static Path defaultLocalDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData);
        }
        return Path.of(System.getProperty("user.home"), "AppData", "Local");
    }

    private static Path defaultRoamingDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData);
        }
        return Path.of(System.getProperty("user.home"), "AppData", "Roaming");
    }
}
