package com.mylauncher.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class LauncherArchive {
    private static final Set<String> ROOT_ITEMS = Set.of("app", "runtime", "MyLauncher.exe");
    private static final long MAX_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;

    private LauncherArchive() {
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java but is unavailable.", exception);
        }
    }

    static void extract(Path archive, Path destination) throws IOException {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDestination);
        long totalBytes = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("Архив содержит слишком много файлов.");
                }
                Path relative = safeRelativePath(entry.getName());
                if (relative.getNameCount() == 0 || !ROOT_ITEMS.contains(relative.getName(0).toString())) {
                    throw new IOException("В архиве обнаружен неожиданный путь: " + entry.getName());
                }
                Path output = normalizedDestination.resolve(relative).normalize();
                if (!output.startsWith(normalizedDestination)) {
                    throw new IOException("Путь файла выходит за пределы папки установки.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    try (var stream = Files.newOutputStream(output)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            totalBytes += read;
                            if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                                throw new IOException("Распакованный архив превышает допустимый размер.");
                            }
                            stream.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        if (!Files.isRegularFile(normalizedDestination.resolve("MyLauncher.exe"))
                || !Files.isDirectory(normalizedDestination.resolve("app"))
                || !Files.isDirectory(normalizedDestination.resolve("runtime"))) {
            throw new IOException("В архиве отсутствуют исполняемый файл или встроенная Java Runtime лаунчера.");
        }
    }

    static void replaceLauncherFiles(Path stagedImage, Path installDirectory) throws IOException {
        Path destination = installDirectory.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("Нельзя установить лаунчер в корень диска.");
        }
        Files.createDirectories(parent);
        Files.createDirectories(destination);

        Path transaction = Files.createTempDirectory(parent, "mylauncher-install-");
        Path backup = transaction.resolve("backup");
        Files.createDirectories(backup);
        String[] managedItems = {"app", "runtime", "MyLauncher.exe"};
        boolean[] backedUp = new boolean[managedItems.length];
        boolean[] installed = new boolean[managedItems.length];
        boolean preserveRecoveryBackup = false;
        try {
            for (int index = 0; index < managedItems.length; index++) {
                Path source = stagedImage.resolve(managedItems[index]);
                Path target = destination.resolve(managedItems[index]);
                if (!Files.exists(source)) {
                    throw new IOException("В подготовленной установке не найден файл " + managedItems[index]);
                }
                if (Files.isSymbolicLink(target)) {
                    throw new IOException("Отказано в замене символической ссылки: " + target);
                }
                if (Files.exists(target)) {
                    Path saved = backup.resolve(managedItems[index]);
                    move(target, saved);
                    backedUp[index] = true;
                }
                move(source, target);
                installed[index] = true;
            }
        } catch (IOException exception) {
            IOException rollbackError = rollback(destination, backup, managedItems, backedUp, installed);
            if (rollbackError != null) {
                preserveRecoveryBackup = true;
                exception.addSuppressed(rollbackError);
                exception.addSuppressed(new IOException("Прежние файлы лаунчера сохранены в " + backup));
            }
            throw exception;
        } finally {
            if (!preserveRecoveryBackup) {
                deleteTree(transaction);
            }
        }
    }

    private static Path safeRelativePath(String entryName) throws IOException {
        if (entryName == null || entryName.isBlank() || entryName.startsWith("/")
                || entryName.startsWith("\\") || entryName.contains("\\") || entryName.indexOf(':') >= 0
                || entryName.indexOf('\0') >= 0) {
            throw new IOException("В архиве обнаружен небезопасный путь.");
        }
        Path path = Path.of(entryName).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().equals(".")) {
            throw new IOException("В архиве обнаружен небезопасный путь: " + entryName);
        }
        return path;
    }

    private static IOException rollback(Path destination, Path backup, String[] items,
                                        boolean[] backedUp, boolean[] installed) {
        IOException failure = null;
        for (int index = items.length - 1; index >= 0; index--) {
            Path target = destination.resolve(items[index]);
            Path saved = backup.resolve(items[index]);
            try {
                if (installed[index] && Files.exists(target)) {
                    deleteTree(target);
                }
                if (backedUp[index] && Files.exists(saved)) {
                    move(saved, target);
                }
            } catch (IOException exception) {
                if (failure == null) {
                    failure = new IOException("Не удалось полностью восстановить прежнюю установку.");
                }
                failure.addSuppressed(exception);
            }
        }
        return failure;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path child : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }
}
