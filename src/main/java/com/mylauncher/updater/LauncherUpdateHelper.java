package com.mylauncher.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.net.URISyntaxException;

public final class LauncherUpdateHelper {
    private LauncherUpdateHelper() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Некорректные параметры процесса обновления.");
        }
        long parentPid = Long.parseLong(args[0]);
        Path installation = Path.of(args[1]).toAbsolutePath().normalize();
        Path stagedImage = Path.of(args[2]).toAbsolutePath().normalize();
        Path staging = Path.of(args[3]).toAbsolutePath().normalize();
        validatePaths(parentPid, installation, stagedImage, staging);
        waitForLauncher(parentPid);
        installAndRestart(installation, stagedImage, staging);
    }

    private static void waitForLauncher(long parentPid) throws IOException, InterruptedException {
        ProcessHandle parent = ProcessHandle.of(parentPid).orElse(null);
        if (parent == null || !parent.isAlive()) {
            return;
        }
        try {
            parent.onExit().get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException exception) {
            throw new IOException("Не удалось дождаться завершения MyLauncher.", exception.getCause());
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IOException("MyLauncher не завершил работу за отведённое время.", exception);
        }
    }

    private static void validatePaths(long parentPid, Path installation, Path stagedImage, Path staging)
            throws IOException {
        Path parent = installation.getParent();
        if (parentPid <= 0 || parent == null || !parent.equals(staging.getParent())
                || !stagedImage.equals(staging.resolve("image"))
                || !staging.getFileName().toString().startsWith(".mylauncher-update-")) {
            throw new IOException("Пути процесса обновления не прошли проверку.");
        }
        if (!Files.isDirectory(installation.resolve("app"))
                || !Files.isRegularFile(installation.resolve("MyLauncher.exe"))
                || !Files.isDirectory(stagedImage.resolve("app"))
                || !Files.isRegularFile(stagedImage.resolve("MyLauncher.exe"))) {
            throw new IOException("Не найдены текущие или подготовленные файлы лаунчера.");
        }
        if (Files.isSymbolicLink(installation.resolve("app"))
                || Files.isSymbolicLink(installation.resolve("MyLauncher.exe"))) {
            throw new IOException("Обновление не заменяет символические ссылки.");
        }
    }

    private static void installAndRestart(Path installation, Path stagedImage, Path staging) throws IOException {
        Path backup = Files.createTempDirectory(installation.getParent(), ".mylauncher-rollback-");
        String[] items = {"app", "MyLauncher.exe"};
        boolean[] saved = new boolean[items.length];
        boolean[] installed = new boolean[items.length];
        try {
            for (int index = 0; index < items.length; index++) {
                Path target = installation.resolve(items[index]);
                Path previous = backup.resolve(items[index]);
                Path replacement = stagedImage.resolve(items[index]);
                if (!Files.exists(replacement)) {
                    throw new IOException("В обновлении отсутствует " + items[index] + ".");
                }
                move(target, previous);
                saved[index] = true;
                move(replacement, target);
                installed[index] = true;
            }
            startLauncher(installation);
        } catch (IOException exception) {
            IOException rollbackFailure = rollback(installation, backup, items, saved, installed);
            if (rollbackFailure != null) {
                exception.addSuppressed(rollbackFailure);
                exception.addSuppressed(new IOException("Резервная копия сохранена в " + backup));
            } else {
                deleteTree(backup);
                try {
                    startLauncher(installation);
                } catch (IOException restartFailure) {
                    exception.addSuppressed(restartFailure);
                }
            }
            throw exception;
        }

        deleteTree(backup);
        deleteTree(staging);
        try {
            Path helperDirectory = Path.of(LauncherUpdateHelper.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            deleteTree(helperDirectory);
        } catch (URISyntaxException exception) {
            throw new IOException("Не удалось очистить временные файлы обновления.", exception);
        }
    }

    private static IOException rollback(Path installation, Path backup, String[] items,
                                        boolean[] saved, boolean[] installed) {
        IOException failure = null;
        for (int index = items.length - 1; index >= 0; index--) {
            Path target = installation.resolve(items[index]);
            Path previous = backup.resolve(items[index]);
            try {
                if (installed[index] && Files.exists(target)) {
                    deleteTree(target);
                }
                if (saved[index] && Files.exists(previous)) {
                    move(previous, target);
                }
            } catch (IOException exception) {
                if (failure == null) {
                    failure = new IOException("Не удалось полностью восстановить предыдущую версию.");
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

    private static void startLauncher(Path installation) throws IOException {
        new ProcessBuilder(installation.resolve("MyLauncher.exe").toString())
                .directory(installation.toFile())
                .start();
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }
}
