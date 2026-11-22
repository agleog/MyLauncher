package com.mylauncher.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mylauncher.auth.AuthProvider;
import com.mylauncher.auth.MinecraftAccount;
import com.mylauncher.settings.LauncherSettings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MinecraftGameLauncher {
    private static final String AUTHLIB_VERSION = "1.2.8";
    private static final URI AUTHLIB_URI = URI.create(
            "https://github.com/yushijinhun/authlib-injector/releases/download/v" + AUTHLIB_VERSION
                    + "/authlib-injector-" + AUTHLIB_VERSION + ".jar");
    private static final String AUTHLIB_SHA256 =
            "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10";
    private static final String ELY_BY_AUTHORITY = "ely.by";
    private static final long MAX_AUTHLIB_BYTES = 8L * 1024 * 1024;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern ACCESS_TOKEN_ARGUMENT =
            Pattern.compile("(?i)(--accessToken(?:[=,\\s]+))([^,\\]\\s]+)");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> TRUSTED_DOWNLOAD_HOSTS =
            Set.of("github.com", "release-assets.githubusercontent.com");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public Process launch(Path gameDirectory, Path javaExecutable, MinecraftAccount account,
                          LauncherSettings settings, ProgressListener progress,
                          BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (account == null || account.authProvider() != AuthProvider.ELY_BY) {
            throw new IOException("Для запуска игры войдите в аккаунт Ely.by.");
        }
        Path root = gameDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IOException("Папка игры отсутствует или небезопасна.");
        }
        if (!Files.isRegularFile(javaExecutable) || Files.isSymbolicLink(javaExecutable)) {
            throw new IOException("Не найдена проверенная Eclipse Temurin Java 21.");
        }

        NeoForgeInstaller neoForgeInstaller = new NeoForgeInstaller();
        if (!neoForgeInstaller.isInstalled(root)) {
            throw new IOException("Сначала установите NeoForge " + NeoForgeInstaller.VERSION + ".");
        }

        checkCancelled(cancelled);
        Path authlibInjector = root.resolve("libraries/com/mylauncher/authlib-injector")
                .resolve(AUTHLIB_VERSION).resolve("authlib-injector-" + AUTHLIB_VERSION + ".jar");
        createSafeDirectories(root, authlibInjector.getParent());
        ensureSafePath(root, authlibInjector);
        if (!isValidAuthlib(authlibInjector)) {
            report(progress, "Загружаем официальный Ely.by authlib-injector…");
            downloadAuthlib(authlibInjector, cancelled);
        }
        checkCancelled(cancelled);

        Path profileFile = root.resolve("versions").resolve(NeoForgeInstaller.PROFILE_ID)
                .resolve(NeoForgeInstaller.PROFILE_ID + ".json");
        Path vanillaFile = root.resolve("versions").resolve(MinecraftInstaller.VERSION)
                .resolve(MinecraftInstaller.VERSION + ".json");
        JsonNode profile = readVersion(root, profileFile);
        JsonNode vanilla = readVersion(root, vanillaFile);
        if (!NeoForgeInstaller.isExpectedProfile(profile)) {
            throw new IOException("Профиль NeoForge не соответствует Minecraft 1.21.1.");
        }
        checkCancelled(cancelled);
        report(progress, "Подготавливаем параметры и библиотеки Minecraft…");
        List<String> command = buildCommand(root, javaExecutable, authlibInjector, account,
                settings, profile, vanilla);
        Path logDirectory = root.resolve("logs");
        createSafeDirectories(root, logDirectory);
        Path logFile = logDirectory.resolve("mylauncher-game.log");
        ensureSafePath(root, logFile);
        checkCancelled(cancelled);
        BufferedWriter logWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true);
        report(progress, "Запускаем Minecraft 1.21.1 с NeoForge " + NeoForgeInstaller.VERSION + "…");
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException | RuntimeException exception) {
            logWriter.close();
            throw exception;
        }
        startLogCapture(process, logWriter, account.sessionToken());
        return process;
    }

    static List<String> buildCommand(Path gameDirectory, Path javaExecutable, Path authlibInjector,
                                     MinecraftAccount account, LauncherSettings settings,
                                     JsonNode profile, JsonNode vanilla) throws IOException {
        if (account == null || account.authProvider() != AuthProvider.ELY_BY) {
            throw new IOException("Для запуска игры войдите в аккаунт Ely.by.");
        }
        if (!NeoForgeInstaller.isExpectedProfile(profile)
                || !MinecraftInstaller.VERSION.equals(vanilla.path("id").asText())
                || !"cpw.mods.bootstraplauncher.BootstrapLauncher".equals(profile.path("mainClass").asText())) {
            throw new IOException("Профили Minecraft и NeoForge не соответствуют заданной конфигурации.");
        }
        Path root = gameDirectory.toAbsolutePath().normalize();
        Path librariesRoot = root.resolve("libraries");
        LinkedHashSet<Path> classpath = new LinkedHashSet<>();
        collectClasspathLibraries(root, vanilla, classpath);
        collectClasspathLibraries(root, profile, classpath);
        Path clientJar = root.resolve("versions").resolve(MinecraftInstaller.VERSION)
                .resolve(MinecraftInstaller.VERSION + ".jar");
        requireRegularFile(root, clientJar, "Клиент Minecraft");

        String classpathValue = String.join(FileSystemValues.PATH_SEPARATOR,
                classpath.stream().map(path -> path.toAbsolutePath().toString()).toList());
        Path nativesDirectory = root.resolve("versions").resolve(MinecraftInstaller.VERSION).resolve("natives");
        Path assetsDirectory = root.resolve("assets");
        String assetIndexId = vanilla.path("assetIndex").path("id").asText("");
        if (assetIndexId.isBlank()) {
            throw new IOException("Манифест Minecraft не содержит индекс ресурсов.");
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("auth_player_name", account.username());
        placeholders.put("version_name", NeoForgeInstaller.PROFILE_ID);
        placeholders.put("game_directory", root.toString());
        placeholders.put("assets_root", assetsDirectory.toString());
        placeholders.put("assets_index_name", assetIndexId);
        placeholders.put("auth_uuid", account.uuid());
        placeholders.put("auth_access_token", account.sessionToken());
        placeholders.put("clientid", "");
        placeholders.put("auth_xuid", "");
        placeholders.put("user_type", "legacy");
        placeholders.put("version_type", "release");
        placeholders.put("launcher_name", "MyLauncher");
        placeholders.put("launcher_version", "0.3.0");
        placeholders.put("classpath", classpathValue);
        placeholders.put("classpath_separator", FileSystemValues.PATH_SEPARATOR);
        placeholders.put("library_directory", librariesRoot.toString());
        placeholders.put("natives_directory", nativesDirectory.toString());
        placeholders.put("resolution_width", Integer.toString(settings.windowWidth()));
        placeholders.put("resolution_height", Integer.toString(settings.windowHeight()));

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toAbsolutePath().toString());
        command.add("-Dauthlibinjector.noLogFile");
        command.add("-javaagent:" + authlibInjector.toAbsolutePath() + "=" + ELY_BY_AUTHORITY);
        int memoryGiB = settings.memoryGiB();
        if (memoryGiB < 1 || memoryGiB > 64) {
            throw new IOException("В настройках задан недопустимый объём памяти для Minecraft.");
        }
        command.add("-Xmx" + memoryGiB + "G");
        command.addAll(parseJvmArguments(settings.jvmArguments() == null ? "" : settings.jvmArguments()));
        appendArguments(command, vanilla.path("arguments").path("jvm"), placeholders);
        appendArguments(command, profile.path("arguments").path("jvm"), placeholders);
        command.add(requiredText(profile, "mainClass", "Профиль NeoForge"));
        if (settings.fullscreen()) {
            command.add("--fullscreen");
        }
        appendArguments(command, vanilla.path("arguments").path("game"), placeholders);
        appendArguments(command, profile.path("arguments").path("game"), placeholders);
        return List.copyOf(command);
    }

    private static void collectClasspathLibraries(Path root, JsonNode version, Set<Path> classpath)
            throws IOException {
        JsonNode libraries = version.path("libraries");
        if (!libraries.isArray()) {
            throw new IOException("Манифест версии не содержит список библиотек.");
        }
        String osName = normalizedOsName();
        String architecture = System.getProperty("os.arch", "");
        String osVersion = System.getProperty("os.version", "");
        for (JsonNode library : libraries) {
            String libraryName = library.path("name").asText("");
            if (!MinecraftInstaller.libraryAllowed(library, osName, architecture, osVersion)
                    || isNativeLibrary(libraryName)) {
                continue;
            }
            JsonNode artifact = library.path("downloads").path("artifact");
            if (!artifact.isObject()) {
                continue;
            }
            String relativePath = requiredText(artifact, "path", "Библиотека Minecraft");
            Path target = root.resolve("libraries").resolve(MinecraftInstaller.safeRelativePath(relativePath))
                    .normalize();
            requireRegularFile(root, target, "Библиотека " + library.path("name").asText());
            classpath.add(target);
        }
    }

    private static void appendArguments(List<String> command, JsonNode arguments,
                                        Map<String, String> placeholders) throws IOException {
        if (!arguments.isArray()) {
            throw new IOException("Профиль игры содержит некорректный список аргументов.");
        }
        for (JsonNode argument : arguments) {
            JsonNode values = argument;
            if (argument.isObject()) {
                if (!MinecraftInstaller.libraryAllowed(argument, normalizedOsName(),
                        System.getProperty("os.arch", ""), System.getProperty("os.version", ""))) {
                    continue;
                }
                values = argument.path("value");
            }
            if (values.isTextual()) {
                command.add(expandPlaceholders(values.asText(), placeholders));
            } else if (values.isArray()) {
                for (JsonNode value : values) {
                    if (!value.isTextual()) {
                        throw new IOException("Аргумент профиля Minecraft имеет неверный тип.");
                    }
                    command.add(expandPlaceholders(value.asText(), placeholders));
                }
            } else {
                throw new IOException("Профиль игры содержит неподдерживаемый аргумент.");
            }
        }
    }

    private static String expandPlaceholders(String argument, Map<String, String> placeholders) throws IOException {
        Matcher matcher = PLACEHOLDER.matcher(argument);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String value = placeholders.get(matcher.group(1));
            if (value == null) {
                throw new IOException("Не удалось подготовить параметр запуска " + matcher.group(1) + ".");
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(expanded);
        if (PLACEHOLDER.matcher(expanded).find()) {
            throw new IOException("Параметры запуска содержат нераскрытое значение.");
        }
        return expanded.toString();
    }

    private static List<String> parseJvmArguments(String arguments) throws IOException {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < arguments.length(); index++) {
            char character = arguments.charAt(index);
            if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                } else if (character == '\\' && index + 1 < arguments.length()
                        && arguments.charAt(index + 1) == quote) {
                    current.append(quote);
                    index++;
                } else {
                    current.append(character);
                }
            } else if (character == '"' || character == '\'') {
                quote = character;
            } else if (Character.isWhitespace(character)) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (quote != 0) {
            throw new IOException("Дополнительные аргументы Java содержат незакрытые кавычки.");
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private void downloadAuthlib(Path target, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Path temporary = Files.createTempFile(target.getParent(), ".authlib-injector-", ".tmp");
        boolean installed = false;
        try {
            URI uri = AUTHLIB_URI;
            for (int redirectCount = 0; redirectCount <= 4; redirectCount++) {
                requireTrustedDownloadUri(uri);
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMinutes(2))
                        .header("User-Agent", "MyLauncher")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new IOException("GitHub вернул перенаправление без адреса."));
                    response.body().close();
                    if (redirectCount == 4) {
                        throw new IOException("Слишком много перенаправлений при загрузке authlib-injector.");
                    }
                    uri = AUTHLIB_URI.resolve(location);
                    continue;
                }
                try (InputStream input = response.body()) {
                    if (response.statusCode() != 200) {
                        throw new IOException("Не удалось загрузить authlib-injector (HTTP "
                                + response.statusCode() + ").");
                    }
                    requireTrustedDownloadUri(response.uri());
                    MessageDigest digest = sha256();
                    long bytes = 0;
                    byte[] buffer = new byte[32 * 1024];
                    try (OutputStream output = Files.newOutputStream(temporary,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            checkCancelled(cancelled);
                            bytes += count;
                            if (bytes > MAX_AUTHLIB_BYTES) {
                                throw new IOException("Архив authlib-injector превышает допустимый размер.");
                            }
                            digest.update(buffer, 0, count);
                            output.write(buffer, 0, count);
                        }
                    }
                    if (!MessageDigest.isEqual(HexFormat.of().formatHex(digest.digest())
                                    .getBytes(StandardCharsets.US_ASCII),
                            AUTHLIB_SHA256.getBytes(StandardCharsets.US_ASCII))) {
                        throw new IOException("Проверка SHA-256 authlib-injector не прошла.");
                    }
                    moveAtomically(temporary, target);
                    installed = true;
                    return;
                }
            }
            throw new IOException("Не удалось загрузить authlib-injector.");
        } finally {
            if (!installed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static boolean isValidAuthlib(Path file) throws IOException {
        if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("Файл authlib-injector небезопасен.");
        }
        if (Files.size(file) > MAX_AUTHLIB_BYTES) {
            return false;
        }
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return MessageDigest.isEqual(HexFormat.of().formatHex(digest.digest())
                        .getBytes(StandardCharsets.US_ASCII),
                AUTHLIB_SHA256.getBytes(StandardCharsets.US_ASCII));
    }

    private static JsonNode readVersion(Path gameDirectory, Path file) throws IOException {
        ensureSafePath(gameDirectory, file);
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("Не найден файл профиля Minecraft: " + file.getFileName());
        }
        JsonNode version = OBJECT_MAPPER.readTree(file.toFile());
        if (version == null || !version.isObject()) {
            throw new IOException("Файл профиля Minecraft содержит некорректный JSON.");
        }
        return version;
    }

    private static String requiredText(JsonNode node, String field, String description) throws IOException {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IOException(description + " не содержит обязательное поле " + field + ".");
        }
        return value;
    }

    private static void requireRegularFile(Path root, Path path, String description) throws IOException {
        ensureSafePath(root, path);
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw new IOException(description + " отсутствует. Проверьте или переустановите файлы игры.");
        }
    }

    private static void createSafeDirectories(Path root, Path directory) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(normalizedRoot)
                || Files.isSymbolicLink(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            throw new IOException("Папка запуска выходит за пределы папки игры.");
        }
        Path current = normalizedRoot;
        for (Path component : normalizedRoot.relativize(normalizedDirectory)) {
            current = current.resolve(component);
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current)) {
                    throw new IOException("Небезопасный путь запуска: " + current);
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
            throw new IOException("Путь запуска выходит за пределы папки игры.");
        }
        Path current = normalizedRoot;
        for (Path component : normalizedRoot.relativize(normalizedTarget)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Путь запуска содержит символическую ссылку: " + current);
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

    private static void requireTrustedDownloadUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !TRUSTED_DOWNLOAD_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IOException("authlib-injector разрешено загружать только с официального GitHub.");
        }
    }

    private static String normalizedOsName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "osx";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return "unknown";
    }

    static boolean isNativeLibrary(String libraryName) {
        return libraryName != null
                && libraryName.toLowerCase(Locale.ROOT).contains(":natives-");
    }

    private static void startLogCapture(Process process, BufferedWriter logWriter, String sessionToken) {
        Thread.ofPlatform().daemon().name("minecraft-game-log").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = logWriter) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(sanitizeLogLine(line, sessionToken));
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException exception) {
                System.err.println("MyLauncher could not capture Minecraft output: " + exception.getMessage());
            }
        });
    }

    static String sanitizeLogLine(String line, String sessionToken) {
        String sanitizedLine = ACCESS_TOKEN_ARGUMENT.matcher(line).replaceAll("$1[REDACTED]");
        return sanitizedLine.replace(sessionToken, "[REDACTED]");
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new InterruptedException("Запуск игры отменён.");
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("В системе недоступен SHA-256 для проверки authlib-injector.", exception);
        }
    }

    private static void report(ProgressListener listener, String message) {
        listener.update(message);
    }

    private static final class FileSystemValues {
        private static final String PATH_SEPARATOR = File.pathSeparator;
    }

    @FunctionalInterface
    public interface ProgressListener {
        void update(String message);
    }
}
