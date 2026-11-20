package com.mylauncher.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

final class InstallationService {
    private final GithubReleaseClient releaseClient;
    private final ShortcutManager shortcutManager;

    InstallationService(GithubReleaseClient releaseClient, ShortcutManager shortcutManager) {
        this.releaseClient = releaseClient;
        this.shortcutManager = shortcutManager;
    }

    void install(GithubRelease release, Path installationDirectory,
                 Consumer<String> status, LongConsumer progress)
            throws IOException, InterruptedException {
        Path installDirectory = installationDirectory.toAbsolutePath().normalize();
        if (installDirectory.getParent() == null) {
            throw new IOException("Выберите папку установки, а не корень диска.");
        }
        Files.createDirectories(installDirectory.getParent());
        Path temporaryDirectory = Files.createTempDirectory(installDirectory.getParent(), "mylauncher-setup-");
        try {
            Path archive = temporaryDirectory.resolve(GithubReleaseClient.ARCHIVE_NAME);
            status.accept("Загрузка версии " + release.version() + "...");
            releaseClient.download(release.archiveUri(), archive, progress);
            if (Files.size(archive) != release.archiveSize()) {
                throw new IOException("Размер скачанного архива не совпал с данными GitHub Releases.");
            }

            status.accept("Проверка SHA-256...");
            String expectedHash = release.sha256();
            if (expectedHash.isBlank()) {
                Path checksumFile = temporaryDirectory.resolve("archive.sha256");
                expectedHash = releaseClient.downloadChecksum(release.checksumUri(), checksumFile);
            }
            String actualHash = LauncherArchive.sha256(archive);
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(expectedHash), HexFormat.of().parseHex(actualHash))) {
                throw new IOException("Контрольная сумма установочного архива не совпала. Архив удалён.");
            }

            Path stagedImage = temporaryDirectory.resolve("image");
            status.accept("Проверка и распаковка файлов...");
            LauncherArchive.extract(archive, stagedImage);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Установка отменена пользователем.");
            }

            status.accept("Установка файлов лаунчера...");
            LauncherArchive.replaceLauncherFiles(stagedImage, installDirectory);
            status.accept("Создание ярлыков...");
            shortcutManager.create(installDirectory.resolve("MyLauncher.exe"));
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    private static void deleteTemporaryDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
