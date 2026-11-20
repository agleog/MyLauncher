package com.mylauncher.settings;

public record LauncherSettings(
        int memoryGiB,
        int windowWidth,
        int windowHeight,
        boolean fullscreen,
        String jvmArguments,
        String launcherDirectory,
        String gameDirectory
) {
}
