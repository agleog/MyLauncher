package com.mylauncher.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LauncherUpdateService {
    private static final Set<String> ARCHIVE_ROOTS = Set.of("app", "runtime", "MyLauncher.exe");
    private static final long MAX_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final String UPDATE_SCRIPT = "apply-update.ps1";
    private final GitHubReleaseClient releaseClient = new GitHubReleaseClient();

    public Optional<ReleaseInfo> checkForUpdate(String repository, String currentVersion)
            throws IOException, InterruptedException {
        ReleaseInfo release = releaseClient.fetchLatest(repository);
        return GitHubReleaseClient.isNewerVersion(release.version(), currentVersion)
                ? Optional.of(release) : Optional.empty();
    }

    public void downloadAndApply(ReleaseInfo release) throws IOException, InterruptedException {
        Path launcherExecutable = runningLauncherExecutable();
        Path installationDirectory = launcherExecutable.getParent();
        Path installationParent = installationDirectory.getParent();
        if (installationParent == null) {
            throw new IOException("Не удалось определить папку установки лаунчера.");
        }
        if (!Files.isDirectory(installationDirectory.resolve("runtime"))
                || !Files.isDirectory(installationDirectory.resolve("app"))) {
            throw new IOException("Автообновление работает только для установленного MyLauncher.exe.");
        }

        Path staging = Files.createTempDirectory(installationParent, ".mylauncher-update-");
        boolean helperStarted = false;
        try {
            Path archive = staging.resolve(GitHubReleaseClient.ARCHIVE_NAME);
            releaseClient.download(release.archiveUri(), archive, release.archiveSize());
            if (Files.size(archive) != release.archiveSize()) {
                throw new IOException("Размер загруженного архива не совпал с данными GitHub.");
            }

            String expectedHash = release.sha256();
            if (expectedHash.isBlank()) {
                Path checksumFile = staging.resolve("archive.sha256");
                expectedHash = releaseClient.downloadChecksum(release.checksumUri(), checksumFile);
            }
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(expectedHash),
                    HexFormat.of().parseHex(sha256(archive)))) {
                throw new IOException("SHA-256 обновления не совпал. Архив отклонён.");
            }

            Path stagedImage = staging.resolve("image");
            extractAndValidate(archive, stagedImage);
            Path updateScript = staging.resolve(UPDATE_SCRIPT);
            copyUpdateScript(updateScript);
            Path helperLog = Path.of(System.getProperty("java.io.tmpdir"), "MyLauncher-update.log");
            Path powershell = windowsPowerShell();
            new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", updateScript.toString(),
                    "-ParentPid", Long.toString(ProcessHandle.current().pid()),
                    "-InstallationDirectory", installationDirectory.toString(),
                    "-StagedImage", stagedImage.toString(), "-StagingDirectory", staging.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(helperLog.toFile())
                    .start();
            helperStarted = true;
        } finally {
            if (!helperStarted) {
                deleteTree(staging);
            }
        }
    }

    private static Path runningLauncherExecutable() throws IOException {
        String command = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IOException("Не удалось определить запущенный файл MyLauncher.exe."));
        Path executable = Path.of(command).toAbsolutePath().normalize();
        if (!"MyLauncher.exe".equalsIgnoreCase(executable.getFileName().toString())) {
            throw new IOException("Обновление можно установить только из установленного MyLauncher.exe.");
        }
        return executable;
    }

    private static void copyUpdateScript(Path destination) throws IOException {
        try (InputStream input = LauncherUpdateService.class.getResourceAsStream(UPDATE_SCRIPT)) {
            if (input == null) {
                throw new IOException("Не удалось подготовить процесс установки обновления.");
            }
            Files.copy(input, destination);
        }
    }

    private static Path windowsPowerShell() throws IOException {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            throw new IOException("Не удалось найти Windows PowerShell.");
        }
        Path executable = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Не удалось найти Windows PowerShell: " + executable);
        }
        return executable;
    }

    static void extractAndValidate(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        Set<String> entries = new HashSet<>();
        long totalBytes = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ARCHIVE_ENTRIES || !entries.add(entry.getName())) {
                    throw new IOException("Архив содержит слишком много файлов или повторяющиеся записи.");
                }
                Path relative = safeRelativePath(entry.getName());
                if (relative.getNameCount() == 0 || !ARCHIVE_ROOTS.contains(relative.getName(0).toString())) {
                    throw new IOException("В архиве обновления обнаружен неожиданный путь.");
                }
                Path output = destination.resolve(relative).normalize();
                if (!output.startsWith(destination)) {
                    throw new IOException("Путь файла обновления выходит за пределы временной папки.");
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
                                throw new IOException("Распакованный архив обновления слишком велик.");
                            }
                            stream.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        if (!Files.isRegularFile(destination.resolve("MyLauncher.exe"))
                || !Files.isDirectory(destination.resolve("app"))
                || !Files.isDirectory(destination.resolve("runtime"))) {
            throw new IOException("В архиве обновления отсутствуют необходимые файлы лаунчера.");
        }
    }

    private static Path safeRelativePath(String entryName) throws IOException {
        if (entryName == null || entryName.isBlank() || entryName.startsWith("/")
                || entryName.startsWith("\\") || entryName.contains("\\") || entryName.contains(":")
                || entryName.indexOf('\0') >= 0 || java.util.Arrays.asList(entryName.split("/"))
                .contains("..")) {
            throw new IOException("В архиве обновления обнаружен небезопасный путь.");
        }
        Path path = Path.of(entryName).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().equals(".")) {
            throw new IOException("В архиве обновления обнаружен небезопасный путь.");
        }
        return path;
    }

    private static String sha256(Path file) throws IOException {
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
            throw new IllegalStateException("Java не поддерживает обязательный алгоритм SHA-256.", exception);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }
}
