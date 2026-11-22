package com.mylauncher.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class NeoForgeInstaller {
    public static final String MINECRAFT_VERSION = "1.21.1";
    public static final String VERSION = "21.1.251";
    public static final String PROFILE_ID = "neoforge-" + VERSION;

    private static final URI INSTALLER_URI = URI.create(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + VERSION
                    + "/neoforge-" + VERSION + "-installer.jar");
    private static final String INSTALLER_SHA256 =
            "f63d9011b89aa5db0f5cbe437bb21b2f8c2182ff0c6ac329b7bbf6c9b19bc741";
    private static final Set<String> TRUSTED_HOSTS = Set.of("maven.neoforged.net");
    private static final long MAX_INSTALLER_BYTES = 100L * 1024 * 1024;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public boolean isInstalled(Path gameDirectory) throws IOException {
        Path root = gameDirectory.toAbsolutePath().normalize();
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw new IOException("Папка игры NeoForge должна быть обычной директорией.");
        }
        Path versionJson = root.resolve("versions").resolve(PROFILE_ID).resolve(PROFILE_ID + ".json");
        Path patchedClient = root.resolve("libraries/net/neoforged/neoforge")
                .resolve(VERSION).resolve("neoforge-" + VERSION + "-client.jar");
        if (!isRegularFileWithoutLinks(root, versionJson)
                || !isRegularFileWithoutLinks(root, patchedClient)) {
            return false;
        }
        JsonNode profile = OBJECT_MAPPER.readTree(versionJson.toFile());
        return isExpectedProfile(profile);
    }

    public InstallResult install(Path gameDirectory, Path javaExecutable,
                                ProgressListener progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        requireWindowsX64();
        checkCancelled(cancelled);
        Path root = gameDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw new IOException("Сначала установите Minecraft 1.21.1 в обычную папку игры.");
        }
        if (isInstalled(root)) {
            report(progress, "NeoForge " + VERSION + " уже установлен и проверен.");
            return new InstallResult(root, PROFILE_ID, false);
        }

        prepareLauncherProfiles(root);
        Path installerDirectory = root.resolve(".mylauncher/installers");
        createSafeDirectories(root, installerDirectory);
        Path installerJar = installerDirectory.resolve("neoforge-" + VERSION + "-installer.jar");
        ensureSafePath(root, installerJar);
        if (!isValidInstaller(installerJar)) {
            downloadInstaller(installerJar, progress, cancelled);
        }
        checkCancelled(cancelled);
        if (!Files.isRegularFile(javaExecutable) || Files.isSymbolicLink(javaExecutable)) {
            throw new IOException("Не найдена проверенная Eclipse Temurin Java 21. Установите её в настройках.");
        }

        report(progress, "Запускаем официальный установщик NeoForge " + VERSION + "…");
        runInstaller(root, javaExecutable, installerJar, progress, cancelled);
        checkCancelled(cancelled);
        if (!isInstalled(root)) {
            throw new IOException("Установщик завершился, но профиль NeoForge или его файлы не прошли проверку.");
        }
        report(progress, "NeoForge " + VERSION + " установлен и проверен.");
        return new InstallResult(root, PROFILE_ID, true);
    }

    static URI trustedInstallerUri(String value) throws IOException {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Адрес установщика NeoForge некорректен.", exception);
        }
        requireTrustedUri(uri);
        return uri;
    }

    static boolean isExpectedProfile(JsonNode profile) {
        return profile != null
                && PROFILE_ID.equals(profile.path("id").asText())
                && MINECRAFT_VERSION.equals(profile.path("inheritsFrom").asText())
                && "cpw.mods.bootstraplauncher.BootstrapLauncher"
                        .equals(profile.path("mainClass").asText());
    }

    private void downloadInstaller(Path target, ProgressListener progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Path temporary = Files.createTempFile(target.getParent(), ".neoforge-installer-", ".tmp");
        boolean installed = false;
        try {
            URI uri = INSTALLER_URI;
            for (int redirectCount = 0; redirectCount <= 3; redirectCount++) {
                requireTrustedUri(uri);
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMinutes(2))
                        .header("User-Agent", "MyLauncher")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new IOException("Сервер NeoForge вернул перенаправление без адреса."));
                    response.body().close();
                    if (redirectCount == 3) {
                        throw new IOException("Превышено число перенаправлений при загрузке NeoForge.");
                    }
                    uri = trustedInstallerUri(uri.resolve(location).toString());
                    continue;
                }
                try (InputStream input = response.body()) {
                    if (response.statusCode() != 200) {
                        throw new IOException("Не удалось загрузить NeoForge (HTTP "
                                + response.statusCode() + ").");
                    }
                    requireTrustedUri(response.uri());
                    MessageDigest digest = sha256();
                    long bytes = 0;
                    byte[] buffer = new byte[64 * 1024];
                    try (OutputStream output = Files.newOutputStream(temporary,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            checkCancelled(cancelled);
                            bytes += count;
                            if (bytes > MAX_INSTALLER_BYTES) {
                                throw new IOException("Архив установщика NeoForge превышает допустимый размер.");
                            }
                            digest.update(buffer, 0, count);
                            output.write(buffer, 0, count);
                            report(progress, "Загружаем официальный установщик NeoForge "
                                    + formatMegabytes(bytes) + " МБ");
                        }
                    }
                    if (!MessageDigest.isEqual(HexFormat.of().formatHex(digest.digest())
                            .getBytes(StandardCharsets.US_ASCII),
                            INSTALLER_SHA256.getBytes(StandardCharsets.US_ASCII))) {
                        throw new IOException("Проверка SHA-256 установщика NeoForge не прошла.");
                    }
                    moveAtomically(temporary, target);
                    installed = true;
                    return;
                }
            }
            throw new IOException("Не удалось загрузить установщик NeoForge.");
        } finally {
            if (!installed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void runInstaller(Path gameDirectory, Path javaExecutable, Path installerJar,
                                     ProgressListener progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(javaExecutable.toString(),
                "-Djava.net.preferIPv4Stack=true",
                "-jar", installerJar.toString(),
                "--installClient", gameDirectory.toString())
                .directory(gameDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        BlockingQueue<String> outputLines = new ArrayBlockingQueue<>(128);
        AtomicReference<IOException> readerFailure = new AtomicReference<>();
        Thread reader = Thread.ofPlatform().daemon().name("neoforge-installer-output").start(() -> {
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    String trimmed = line.strip();
                    if (!trimmed.isEmpty()) {
                        if (!outputLines.offer(trimmed)) {
                            outputLines.poll();
                            outputLines.offer(trimmed);
                        }
                    }
                }
            } catch (IOException exception) {
                readerFailure.set(exception);
            }
        });

        ArrayDeque<String> recentOutput = new ArrayDeque<>();
        try {
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                checkCancelled(cancelled);
                reportOutput(outputLines, recentOutput, progress);
            }
            reader.join(2_000);
            reportOutput(outputLines, recentOutput, progress);
            if (readerFailure.get() != null) {
                throw new IOException("Не удалось прочитать вывод установщика NeoForge.",
                        readerFailure.get());
            }
            if (process.exitValue() != 0) {
                String details = String.join(" | ", recentOutput);
                throw new IOException("Официальный установщик NeoForge завершился с кодом "
                        + process.exitValue() + (details.isBlank() ? "." : ": " + details));
            }
        } catch (InterruptedException exception) {
            destroyProcessTree(process);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (IOException | RuntimeException exception) {
            destroyProcessTree(process);
            throw exception;
        } finally {
            reader.interrupt();
            if (process.isAlive()) {
                destroyProcessTree(process);
            }
        }
    }

    private static void reportOutput(BlockingQueue<String> outputLines, ArrayDeque<String> recentOutput,
                                     ProgressListener progress) {
        String line;
        while ((line = outputLines.poll()) != null) {
            if (line.length() > 300) {
                line = line.substring(0, 300);
            }
            if (recentOutput.size() == 20) {
                recentOutput.removeFirst();
            }
            recentOutput.addLast(line);
            report(progress, "NeoForge: " + line);
        }
    }

    private static void destroyProcessTree(Process process) {
        process.toHandle().descendants().forEach(handle -> handle.destroyForcibly());
        process.destroyForcibly();
    }

    private static void prepareLauncherProfiles(Path root) throws IOException {
        Path profiles = root.resolve("launcher_profiles.json");
        ensureSafePath(root, profiles);
        if (Files.exists(profiles, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(profiles, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(profiles)) {
                throw new IOException("Файл профилей Minecraft небезопасен или имеет неверный тип.");
            }
            JsonNode existing = OBJECT_MAPPER.readTree(profiles.toFile());
            if (existing == null || !existing.isObject()) {
                throw new IOException("Файл профилей Minecraft содержит некорректный JSON.");
            }
            return;
        }
        Path temporary = Files.createTempFile(root, ".mylauncher-profiles-", ".tmp");
        try {
            Files.writeString(temporary, "{\"profiles\":{},\"settings\":{}}",
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            moveAtomically(temporary, profiles);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean isValidInstaller(Path installer) throws IOException {
        if (!Files.exists(installer, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isRegularFile(installer, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(installer)) {
            throw new IOException("Файл NeoForge installer существует, но небезопасен.");
        }
        if (Files.size(installer) > MAX_INSTALLER_BYTES) {
            return false;
        }
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(installer)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return MessageDigest.isEqual(HexFormat.of().formatHex(digest.digest())
                        .getBytes(StandardCharsets.US_ASCII),
                INSTALLER_SHA256.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean isRegularFileWithoutLinks(Path root, Path file) throws IOException {
        ensureSafePath(root, file);
        return Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(file);
    }

    private static void createSafeDirectories(Path root, Path directory) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(normalizedRoot)
                || Files.isSymbolicLink(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            throw new IOException("Папка NeoForge выходит за пределы папки игры.");
        }
        Path current = normalizedRoot;
        for (Path component : normalizedRoot.relativize(normalizedDirectory)) {
            current = current.resolve(component);
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current)) {
                    throw new IOException("Небезопасный путь установки NeoForge: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private static void ensureSafePath(Path root, Path target) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new IOException("Путь установщика NeoForge выходит за пределы папки игры.");
        }
        Path current = normalizedRoot;
        for (Path component : normalizedRoot.relativize(normalizedTarget)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Путь NeoForge содержит символическую ссылку: " + current);
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void requireTrustedUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !TRUSTED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443
                || uri.getFragment() != null) {
            throw new IOException("Загрузка NeoForge разрешена только с официального HTTPS-сервера.");
        }
    }

    private static void requireWindowsX64() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win") || !architecture.matches("amd64|x86_64")) {
            throw new IOException("Установка NeoForge поддерживается только в Windows x64.");
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new InterruptedException("Установка NeoForge отменена.");
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("В системе недоступен SHA-256 для проверки NeoForge.", exception);
        }
    }

    private static String formatMegabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    private static void report(ProgressListener listener, String message) {
        listener.update(message);
    }

    public record InstallResult(Path gameDirectory, String profileId, boolean installedNow) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void update(String message);
    }
}
