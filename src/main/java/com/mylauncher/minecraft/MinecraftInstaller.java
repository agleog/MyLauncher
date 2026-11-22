package com.mylauncher.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class MinecraftInstaller {
    public static final String VERSION = "1.21.1";

    private static final URI VERSION_MANIFEST_URI =
            URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final Set<String> TRUSTED_HOSTS = Set.of(
            "piston-meta.mojang.com",
            "launchermeta.mojang.com",
            "launcher.mojang.com",
            "piston-data.mojang.com",
            "resources.download.minecraft.net",
            "libraries.minecraft.net"
    );
    private static final int MAX_CONCURRENT_DOWNLOADS = 6;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ASSET_INDEX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_DOWNLOADS = 100_000;
    private static final long MAX_FILE_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;
    private static final long MAX_NATIVE_BYTES = 256L * 1024 * 1024;

    private final HttpClient httpClient;

    public MinecraftInstaller() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean isInstalled(Path gameDirectory) throws IOException {
        Path root = gameDirectory.toAbsolutePath().normalize();
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw new IOException("Папка игры должна быть обычной директорией: " + root);
        }
        Path versionFile = confinedPath(root, "versions/" + VERSION + "/" + VERSION + ".json");
        ensureSafePath(root, versionFile);
        if (!isRegularFile(versionFile)) {
            return false;
        }
        JsonNode version = parseJson(Files.readAllBytes(versionFile), "Локальный манифест Minecraft");
        if (!VERSION.equals(version.path("id").asText())) {
            return false;
        }

        JsonNode client = version.path("downloads").path("client");
        Path clientFile = confinedPath(root, "versions/" + VERSION + "/" + VERSION + ".jar");
        ensureSafePath(root, clientFile);
        if (!isRegularFileWithSize(clientFile, requiredSize(client, "size"))) {
            return false;
        }

        JsonNode assetIndex = version.path("assetIndex");
        String assetIndexId = requiredSafeIdentifier(assetIndex, "id");
        Path indexFile = confinedPath(root, "assets/indexes/" + assetIndexId + ".json");
        ensureSafePath(root, indexFile);
        if (!isRegularFile(indexFile) || Files.size(indexFile) > MAX_ASSET_INDEX_BYTES) {
            return false;
        }
        byte[] indexBytes = Files.readAllBytes(indexFile);
        verifySha1(indexBytes, requiredSha1(assetIndex, "sha1"), "Локальный индекс игровых ресурсов");
        JsonNode parsedAssetIndex = parseJson(indexBytes, "Локальный индекс игровых ресурсов");

        List<DownloadJob> jobs = new ArrayList<>();
        Map<Path, DownloadJob> jobsByTarget = new HashMap<>();
        List<Path> nativeArchives = new ArrayList<>();
        collectLibraries(root, version, jobs, jobsByTarget, nativeArchives);
        collectAssets(root, parsedAssetIndex, jobs, jobsByTarget);
        if (jobs.size() > MAX_DOWNLOADS) {
            throw new IOException("Манифест Minecraft содержит слишком много файлов.");
        }
        for (DownloadJob job : jobs) {
            ensureSafePath(root, job.target());
            if (!isRegularFileWithSize(job.target(), job.size())) {
                return false;
            }
        }
        return true;
    }

    public InstallResult install(Path gameDirectory, ProgressListener progress)
            throws IOException, InterruptedException {
        requireWindowsX64();
        Path root = gameDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw new IOException("Папка игры должна быть обычной директорией: " + root);
        }

        report(progress, "Получаем официальный манифест Minecraft " + VERSION + "…", 0, 0, 0, 0, 0);
        JsonNode versionManifest = readJson(VERSION_MANIFEST_URI, MAX_MANIFEST_BYTES);
        JsonNode versionEntry = findVersion(versionManifest, VERSION);
        URI versionUri = trustedHttpsUri(requiredText(versionEntry, "url"));
        String versionSha1 = requiredSha1(versionEntry, "sha1");

        byte[] versionBytes = readBytes(versionUri, MAX_MANIFEST_BYTES);
        verifySha1(versionBytes, versionSha1, "Манифест Minecraft " + VERSION);
        JsonNode version = parseJson(versionBytes, "Манифест Minecraft " + VERSION);
        if (!VERSION.equals(requiredText(version, "id"))) {
            throw new IOException("Манифест Mojang содержит неожиданную версию Minecraft.");
        }

        Path versionDirectory = confinedPath(root, "versions/" + VERSION);
        createSafeDirectories(root, versionDirectory);
        ensureSafePath(root, versionDirectory.resolve(VERSION + ".json"));
        writeAtomically(versionDirectory.resolve(VERSION + ".json"), versionBytes);

        List<DownloadJob> jobs = new ArrayList<>();
        Map<Path, DownloadJob> jobsByTarget = new HashMap<>();
        List<Path> nativeArchives = new ArrayList<>();
        JsonNode client = version.path("downloads").path("client");
        addDownload(jobs, trustedHttpsUri(requiredText(client, "url")),
                versionDirectory.resolve(VERSION + ".jar"), requiredSha1(client, "sha1"),
                requiredSize(client, "size"), "Клиент Minecraft", jobsByTarget);

        collectLibraries(root, version, jobs, jobsByTarget, nativeArchives);
        if (jobs.size() > MAX_DOWNLOADS) {
            throw new IOException("Манифест Minecraft содержит слишком много файлов.");
        }

        JsonNode assetIndex = version.path("assetIndex");
        String assetIndexId = requiredSafeIdentifier(assetIndex, "id");
        byte[] assetIndexBytes = readBytes(trustedHttpsUri(requiredText(assetIndex, "url")),
                MAX_ASSET_INDEX_BYTES);
        verifySha1(assetIndexBytes, requiredSha1(assetIndex, "sha1"), "Индекс игровых ресурсов");
        JsonNode parsedAssetIndex = parseJson(assetIndexBytes, "Индекс игровых ресурсов");
        Path indexFile = confinedPath(root, "assets/indexes/" + assetIndexId + ".json");
        createSafeDirectories(root, indexFile.getParent());
        writeAtomically(indexFile, assetIndexBytes);
        collectAssets(root, parsedAssetIndex, jobs, jobsByTarget);

        if (jobs.size() > MAX_DOWNLOADS) {
            throw new IOException("Манифест Minecraft содержит слишком много файлов.");
        }
        long totalBytes = 0;
        for (DownloadJob job : jobs) {
            totalBytes = Math.addExact(totalBytes, job.size());
            if (job.size() > MAX_FILE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                throw new IOException("Размер загрузки Minecraft превышает допустимый предел.");
            }
        }

        AtomicLong completedBytes = new AtomicLong();
        AtomicLong downloadedBytes = new AtomicLong();
        AtomicInteger completedFiles = new AtomicInteger();
        AtomicInteger downloadedFiles = new AtomicInteger();
        report(progress, "Проверяем файлы Minecraft " + VERSION + "…",
                0, totalBytes, 0, jobs.size(), 0);
        downloadAll(root, jobs, totalBytes, progress, completedBytes, completedFiles,
                downloadedBytes, downloadedFiles);
        extractNatives(root, nativeArchives, progress, completedFiles, jobs.size(), downloadedBytes);
        report(progress, "Minecraft " + VERSION + " установлен. Следующий этап — NeoForge.",
                totalBytes, totalBytes, jobs.size(), jobs.size(), downloadedBytes.get());
        return new InstallResult(root, VERSION, jobs.size(), totalBytes,
                downloadedFiles.get(), downloadedBytes.get());
    }

    static JsonNode findVersion(JsonNode manifest, String versionId) throws IOException {
        JsonNode versions = manifest.path("versions");
        if (!versions.isArray()) {
            throw new IOException("Официальный манифест Mojang не содержит список версий.");
        }
        JsonNode match = null;
        for (JsonNode candidate : versions) {
            if (versionId.equals(candidate.path("id").asText())) {
                if (match != null) {
                    throw new IOException("Манифест Mojang содержит повторяющуюся запись версии "
                            + versionId + ".");
                }
                match = candidate;
            }
        }
        if (match == null) {
            throw new IOException("Официальный манифест Mojang не содержит Minecraft " + versionId + ".");
        }
        return match;
    }

    static boolean libraryAllowed(JsonNode library, String osName, String architecture, String osVersion)
            throws IOException {
        JsonNode rules = library.path("rules");
        if (!rules.isArray()) {
            return true;
        }
        boolean allowed = false;
        for (JsonNode rule : rules) {
            if (!ruleMatches(rule.path("os"), rule.path("features"), osName, architecture, osVersion)) {
                continue;
            }
            String action = rule.path("action").asText();
            if ("allow".equals(action)) {
                allowed = true;
            } else if ("disallow".equals(action)) {
                allowed = false;
            } else {
                throw new IOException("Правила библиотеки Minecraft содержат неизвестное действие.");
            }
        }
        return allowed;
    }

    static boolean windowsX64NativeVariant(String libraryName) {
        return libraryName.endsWith(":natives-windows")
                || libraryName.endsWith(":natives-windows-64")
                || libraryName.endsWith(":natives-windows-x64");
    }

    static boolean isWindowsNativeLibrary(String libraryName) {
        return libraryName.endsWith(":natives-windows")
                || libraryName.endsWith(":natives-windows-64")
                || libraryName.endsWith(":natives-windows-x64");
    }

    static URI trustedHttpsUri(String value) throws IOException {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Манифест Mojang содержит некорректный адрес загрузки.", exception);
        }
        requireTrustedHttpsUri(uri);
        return uri;
    }

    static Path safeRelativePath(String value) throws IOException {
        if (value == null || value.isBlank() || value.startsWith("/") || value.startsWith("\\")
                || value.contains("\\") || value.contains(":") || value.indexOf('\0') >= 0) {
            throw new IOException("Манифест Minecraft содержит небезопасный путь.");
        }
        Path path;
        try {
            path = Path.of(value).normalize();
        } catch (RuntimeException exception) {
            throw new IOException("Манифест Minecraft содержит некорректный путь.", exception);
        }
        if (path.isAbsolute() || path.startsWith("..") || path.toString().equals(".")) {
            throw new IOException("Манифест Minecraft содержит небезопасный путь.");
        }
        for (Path component : path) {
            if (component.toString().equals("..") || component.toString().equals(".")) {
                throw new IOException("Манифест Minecraft содержит небезопасный путь.");
            }
        }
        return path;
    }

    private JsonNode readJson(URI uri, int maximumBytes) throws IOException, InterruptedException {
        return parseJson(readBytes(uri, maximumBytes), "Ответ сервера Minecraft");
    }

    private byte[] readBytes(URI initialUri, int maximumBytes) throws IOException, InterruptedException {
        URI uri = initialUri;
        for (int redirectCount = 0; redirectCount <= 5; redirectCount++) {
            requireTrustedHttpsUri(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(45))
                    .header("Accept", "application/json")
                    .header("User-Agent", "MyLauncher")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("Сервер Mojang вернул перенаправление без адреса."));
                response.body().close();
                if (redirectCount == 5) {
                    throw new IOException("Превышено число перенаправлений сервера Mojang.");
                }
                uri = resolveRedirect(uri, location);
                continue;
            }
            try (InputStream input = response.body()) {
                if (status != 200) {
                    throw new IOException("Сервер Minecraft вернул HTTP " + status + ".");
                }
                requireTrustedHttpsUri(response.uri());
                return readLimited(input, maximumBytes);
            }
        }
        throw new IOException("Не удалось получить ответ сервера Mojang.");
    }

    private void collectLibraries(Path root, JsonNode version, List<DownloadJob> jobs,
                                  Map<Path, DownloadJob> jobsByTarget,
                                  List<Path> nativeArchives) throws IOException {
        JsonNode libraries = version.path("libraries");
        if (!libraries.isArray()) {
            throw new IOException("Манифест Minecraft не содержит список библиотек.");
        }
        String osName = normalizedOsName();
        String architecture = System.getProperty("os.arch", "");
        String osVersion = System.getProperty("os.version", "");
        for (JsonNode library : libraries) {
            String libraryName = library.path("name").asText("");
            if (!libraryAllowed(library, osName, architecture, osVersion)
                    || osName.equals("windows") && !windowsX64NativeVariant(libraryName)) {
                continue;
            }
            JsonNode artifact = library.path("downloads").path("artifact");
            if (artifact.isObject()) {
                Path target = libraryPath(root, requiredText(artifact, "path"));
                addDownload(jobs, trustedHttpsUri(requiredText(artifact, "url")), target,
                        optionalSha1(artifact), optionalSize(artifact),
                        "Библиотека " + libraryName, jobsByTarget);
                if (osName.equals("windows") && isWindowsNativeLibrary(libraryName)) {
                    nativeArchives.add(target);
                }
            }
            JsonNode nativeNames = library.path("natives");
            String nativeName = nativeNames.path("windows").asText("");
            if (!nativeName.isBlank()) {
                nativeName = nativeName.replace("${arch}", nativeArchitecture());
                JsonNode classifier = library.path("downloads").path("classifiers").path(nativeName);
                if (!classifier.isObject()) {
                    throw new IOException("В манифесте отсутствует библиотека Windows " + nativeName + ".");
                }
                Path target = libraryPath(root, requiredText(classifier, "path"));
                addDownload(jobs, trustedHttpsUri(requiredText(classifier, "url")), target,
                        optionalSha1(classifier), optionalSize(classifier), "Библиотека Windows "
                                + libraryName, jobsByTarget);
                nativeArchives.add(target);
            }
        }
    }

    private void collectAssets(Path root, JsonNode assetIndex, List<DownloadJob> jobs,
                               Map<Path, DownloadJob> jobsByTarget) throws IOException {
        JsonNode objects = assetIndex.path("objects");
        if (!objects.isObject() || objects.size() > MAX_DOWNLOADS) {
            throw new IOException("Индекс игровых ресурсов Minecraft имеет неверный формат.");
        }
        Iterator<Map.Entry<String, JsonNode>> entries = objects.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String hash = requiredSha1(entry.getValue(), "hash");
            Path target = confinedPath(root, "assets/objects/" + hash.substring(0, 2) + "/" + hash);
            URI uri = trustedHttpsUri("https://resources.download.minecraft.net/"
                    + hash.substring(0, 2) + "/" + hash);
            addDownload(jobs, uri, target, hash, requiredSize(entry.getValue(), "size"),
                    "Ресурс " + entry.getKey(), jobsByTarget);
        }
    }

    private void downloadAll(Path root, List<DownloadJob> jobs, long totalBytes, ProgressListener progress,
                             AtomicLong completedBytes, AtomicInteger completedFiles,
                             AtomicLong downloadedBytes, AtomicInteger downloadedFiles)
            throws IOException, InterruptedException {
        if (jobs.isEmpty()) {
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS,
                Thread.ofPlatform().name("minecraft-download-", 0).factory());
        CompletionService<Void> completion = new ExecutorCompletionService<>(executor);
        List<Future<Void>> workers = new ArrayList<>();
        java.util.concurrent.ConcurrentLinkedQueue<DownloadJob> queue =
                new java.util.concurrent.ConcurrentLinkedQueue<>(jobs);
        int workerCount = Math.min(MAX_CONCURRENT_DOWNLOADS, jobs.size());
        try {
            for (int i = 0; i < workerCount; i++) {
                workers.add(completion.submit(() -> {
                    DownloadJob job;
                    while ((job = queue.poll()) != null) {
                        DownloadJob currentJob = job;
                        checkInterrupted();
                        boolean downloaded = downloadFile(root, currentJob, delta -> {
                            long transferred = downloadedBytes.addAndGet(delta);
                            long verifiedBytes = completedBytes.addAndGet(delta);
                            report(progress, "Загружается " + currentJob.label(), verifiedBytes, totalBytes,
                                    completedFiles.get(), jobs.size(), transferred);
                        });
                        if (downloaded) {
                            downloadedFiles.incrementAndGet();
                        } else if (currentJob.size() > 0) {
                            completedBytes.addAndGet(currentJob.size());
                        }
                        int fileCount = completedFiles.incrementAndGet();
                        report(progress, downloaded
                                        ? "Загружено и проверено файлов: " + fileCount + " из " + jobs.size()
                                        : "Проверены локальные файлы: " + fileCount + " из " + jobs.size()
                                                + " — повторная загрузка не нужна",
                                completedBytes.get(), totalBytes, fileCount, jobs.size(), downloadedBytes.get());
                    }
                    return null;
                }));
            }
            for (int i = 0; i < workerCount; i++) {
                try {
                    completion.take().get();
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    if (cause instanceof InterruptedException interruptedException) {
                        throw interruptedException;
                    }
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new IOException("Не удалось загрузить файлы Minecraft.", cause);
                }
            }
        } finally {
            for (Future<Void> worker : workers) {
                worker.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    private boolean downloadFile(Path root, DownloadJob job, java.util.function.LongConsumer byteProgress)
            throws IOException, InterruptedException {
        Path target = job.target();
        ensureSafePath(root, target);
        createSafeDirectories(root, target.getParent());
        ensureSafePath(root, target);
        if (isValidFile(target, job)) {
            return false;
        }

        Path temporary = Files.createTempFile(target.getParent(), ".mylauncher-download-", ".tmp");
        boolean installed = false;
        try {
            URI uri = job.uri();
            for (int redirectCount = 0; redirectCount <= 5; redirectCount++) {
                requireTrustedHttpsUri(uri);
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMinutes(5))
                        .header("User-Agent", "MyLauncher")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new IOException("Сервер файла Minecraft вернул перенаправление без адреса."));
                    response.body().close();
                    if (redirectCount == 5) {
                        throw new IOException("Превышено число перенаправлений при загрузке Minecraft.");
                    }
                    uri = resolveRedirect(uri, location);
                    continue;
                }
                try (InputStream input = response.body()) {
                    if (status != 200) {
                        throw new IOException("Не удалось загрузить " + job.label() + " (HTTP " + status + ").");
                    }
                    requireTrustedHttpsUri(response.uri());
                    MessageDigest digest = sha1Digest();
                    long bytes = 0;
                    byte[] buffer = new byte[64 * 1024];
                    try (OutputStream output = Files.newOutputStream(temporary,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            checkInterrupted();
                            bytes += read;
                            if (bytes > MAX_FILE_BYTES || job.size() > 0 && bytes > job.size()) {
                                throw new IOException(job.label() + " превысил ожидаемый размер.");
                            }
                            digest.update(buffer, 0, read);
                            output.write(buffer, 0, read);
                            byteProgress.accept(read);
                        }
                    }
                    if (job.size() > 0 && bytes != job.size()) {
                        throw new IOException("Размер файла " + job.label() + " не совпал с манифестом Mojang.");
                    }
                    String actualSha1 = HexFormat.of().formatHex(digest.digest());
                    if (!job.sha1().isBlank() && !constantTimeEquals(actualSha1, job.sha1())) {
                        throw new IOException("Проверка SHA-1 файла " + job.label() + " не прошла.");
                    }
                    moveAtomically(temporary, target);
                    installed = true;
                    return true;
                }
            }
            throw new IOException("Не удалось загрузить " + job.label() + ".");
        } finally {
            if (!installed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private void extractNatives(Path root, List<Path> nativeArchives, ProgressListener progress,
                                AtomicInteger completedFiles, int totalFiles, AtomicLong downloadedBytes)
            throws IOException, InterruptedException {
        if (nativeArchives.isEmpty()) {
            return;
        }
        Path nativeDirectory = confinedPath(root, "versions/" + VERSION + "/natives");
        createSafeDirectories(root, nativeDirectory);
        long totalExpanded = 0;
        for (Path archive : nativeArchives) {
            checkInterrupted();
            if (!Files.isRegularFile(archive) || Files.isSymbolicLink(archive)) {
                throw new IOException("Не найдена проверенная библиотека Windows: " + archive.getFileName());
            }
            try (InputStream fileInput = Files.newInputStream(archive);
                 ZipInputStream zipInput = new ZipInputStream(fileInput)) {
                ZipEntry entry;
                while ((entry = zipInput.getNextEntry()) != null) {
                    checkInterrupted();
                    String name = entry.getName();
                    if (name.isBlank() || name.contains("\\") || name.contains(":")
                            || name.startsWith("/") || name.matches("(?i)^[a-z]:.*")) {
                        throw new IOException("Архив библиотеки Windows содержит небезопасный путь.");
                    }
                    if (!entry.isDirectory() && name.toLowerCase(Locale.ROOT).endsWith(".dll")) {
                        Path relative = safeRelativePath(name);
                        Path output = nativeDirectory.resolve(relative).normalize();
                        if (!output.startsWith(nativeDirectory)) {
                            throw new IOException("Путь нативной библиотеки выходит за пределы папки игры.");
                        }
                        ensureSafePath(root, output);
                        createSafeDirectories(root, output.getParent());
                        Path temporary = Files.createTempFile(output.getParent(), ".mylauncher-native-", ".tmp");
                        try (OutputStream stream = Files.newOutputStream(temporary,
                                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                            byte[] buffer = new byte[32 * 1024];
                            int count;
                            while ((count = zipInput.read(buffer)) != -1) {
                                checkInterrupted();
                                totalExpanded += count;
                                if (totalExpanded > MAX_NATIVE_BYTES) {
                                    throw new IOException("Распакованный архив Windows-библиотек слишком велик.");
                                }
                                stream.write(buffer, 0, count);
                            }
                        } catch (IOException | InterruptedException | RuntimeException exception) {
                            Files.deleteIfExists(temporary);
                            throw exception;
                        }
                        moveAtomically(temporary, output);
                    }
                    zipInput.closeEntry();
                }
            }
        }
        report(progress, "Windows-библиотеки Minecraft подготовлены",
                0, 0, completedFiles.get(), totalFiles, downloadedBytes.get());
    }

    private static Path libraryPath(Path root, String relative) throws IOException {
        Path safe = safeRelativePath(relative);
        if (safe.getNameCount() < 3) {
            throw new IOException("Манифест Minecraft содержит некорректный путь библиотеки.");
        }
        Path target = root.resolve("libraries").resolve(safe).normalize();
        Path libraryRoot = root.resolve("libraries").normalize();
        if (!target.startsWith(libraryRoot)) {
            throw new IOException("Путь библиотеки Minecraft выходит за пределы папки игры.");
        }
        return target;
    }

    private static Path confinedPath(Path root, String relative) throws IOException {
        Path target = root.resolve(safeRelativePath(relative)).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Путь файла Minecraft выходит за пределы папки игры.");
        }
        return target;
    }

    private static void ensureSafePath(Path root, Path target) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new IOException("Путь файла Minecraft выходит за пределы папки игры.");
        }
        Path current = normalizedRoot;
        if (Files.isSymbolicLink(current)) {
            throw new IOException("Папка игры не должна быть символической ссылкой.");
        }
        for (Path component : normalizedRoot.relativize(normalizedTarget)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Обнаружена символическая ссылка в папке игры: " + current);
            }
        }
    }

    private static void createSafeDirectories(Path root, Path directory) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(normalizedRoot) || Files.isSymbolicLink(normalizedRoot)) {
            throw new IOException("Путь папки Minecraft выходит за пределы игровой директории.");
        }
        Path current = normalizedRoot;
        for (Path component : normalizedRoot.relativize(normalizedDirectory)) {
            current = current.resolve(component);
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current)) {
                    throw new IOException("Небезопасный путь в папке Minecraft: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private static boolean isValidFile(Path file, DownloadJob job) throws IOException {
        if (!Files.exists(file)) {
            return false;
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
            throw new IOException("Файл Minecraft существует, но не является обычным файлом: " + file);
        }
        if (job.size() > 0 && Files.size(file) != job.size()) {
            return false;
        }
        return job.sha1().isBlank() || constantTimeEquals(sha1File(file), job.sha1());
    }

    private static boolean isRegularFile(Path file) {
        return Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(file);
    }

    private static boolean isRegularFileWithSize(Path file, long expectedSize) throws IOException {
        return isRegularFile(file) && (expectedSize <= 0 || Files.size(file) == expectedSize);
    }

    private static void writeAtomically(Path target, byte[] contents) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".mylauncher-metadata-", ".tmp");
        try {
            Files.write(temporary, contents, StandardOpenOption.TRUNCATE_EXISTING);
            moveAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void addDownload(List<DownloadJob> jobs, URI uri, Path target,
                                    String sha1, long size, String label, Map<Path, DownloadJob> jobsByTarget)
            throws IOException {
        if (size < 0 || size > MAX_FILE_BYTES) {
            throw new IOException("Манифест Minecraft содержит недопустимый размер файла " + label + ".");
        }
        if (sha1.isBlank() && size == 0) {
            throw new IOException("Манифест Mojang не содержит контрольную сумму или размер файла " + label + ".");
        }
        DownloadJob existing = jobsByTarget.get(target);
        if (existing != null) {
            if (!existing.uri().equals(uri) || !existing.sha1().equals(sha1) || existing.size() != size) {
                throw new IOException("Манифест Minecraft назначил разные файлы в один путь.");
            }
            return;
        }
        DownloadJob job = new DownloadJob(uri, target, sha1, size, label);
        jobs.add(job);
        jobsByTarget.put(target, job);
    }

    private static boolean ruleMatches(JsonNode os, JsonNode features, String osName,
                                       String architecture, String osVersion) throws IOException {
        if (os.isObject()) {
            if (os.has("name") && !osName.equalsIgnoreCase(os.path("name").asText())) {
                return false;
            }
            if (os.has("arch") && !regexMatches(os.path("arch").asText(), architecture)) {
                return false;
            }
            if (os.has("version") && !regexMatches(os.path("version").asText(), osVersion)) {
                return false;
            }
        }
        if (features.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> values = features.fields();
            while (values.hasNext()) {
                Map.Entry<String, JsonNode> feature = values.next();
                if (feature.getValue().asBoolean(false)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean regexMatches(String expression, String value) throws IOException {
        try {
            return Pattern.compile(expression).matcher(value).find();
        } catch (PatternSyntaxException exception) {
            throw new IOException("Манифест Minecraft содержит некорректное правило библиотеки.", exception);
        }
    }

    private static void requireTrustedHttpsUri(URI uri) throws IOException {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !TRUSTED_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IOException("Ссылка на файл Minecraft не прошла проверку HTTPS-адреса.");
        }
    }

    private static URI resolveRedirect(URI current, String location) throws IOException {
        try {
            URI redirect = current.resolve(location);
            requireTrustedHttpsUri(redirect);
            return redirect;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Сервер Mojang вернул некорректный адрес перенаправления.", exception);
        }
    }

    private static byte[] readLimited(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maximumBytes) {
                throw new IOException("Ответ сервера Minecraft превышает допустимый размер.");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static JsonNode parseJson(byte[] contents, String description) throws IOException {
        try {
            JsonNode json = new ObjectMapper().readTree(contents);
            if (json == null || !json.isObject()) {
                throw new IOException(description + " имеет неверный формат.");
            }
            return json;
        } catch (IOException exception) {
            throw new IOException(description + " содержит некорректный JSON.", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IOException("Официальный манифест не содержит обязательное поле " + field + ".");
        }
        return value;
    }

    private static String requiredSafeIdentifier(JsonNode node, String field) throws IOException {
        String value = requiredText(node, field);
        if (!value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("Манифест Minecraft содержит недопустимый идентификатор ресурса.");
        }
        return value;
    }

    private static String requiredSha1(JsonNode node, String field) throws IOException {
        String value = requiredText(node, field).toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{40}")) {
            throw new IOException("Манифест Mojang содержит некорректную SHA-1 контрольную сумму.");
        }
        return value;
    }

    private static String optionalSha1(JsonNode node) throws IOException {
        return node.hasNonNull("sha1") ? requiredSha1(node, "sha1") : "";
    }

    private static long requiredSize(JsonNode node, String field) throws IOException {
        long size = optionalSize(node);
        if (!node.path(field).canConvertToLong() || size < 0) {
            throw new IOException("Манифест Mojang не содержит корректный размер файла " + field + ".");
        }
        return size;
    }

    private static long optionalSize(JsonNode node) throws IOException {
        JsonNode sizeNode = node.path("size");
        if (sizeNode.isMissingNode()) {
            return 0;
        }
        if (!sizeNode.canConvertToLong() || sizeNode.asLong() < 0) {
            throw new IOException("Манифест Mojang содержит некорректный размер файла.");
        }
        return sizeNode.asLong();
    }

    static void verifySha1(byte[] contents, String expectedSha1, String description) throws IOException {
        String actual = HexFormat.of().formatHex(sha1Digest().digest(contents));
        if (!constantTimeEquals(actual, expectedSha1)) {
            throw new IOException("Проверка целостности " + description + " не прошла.");
        }
    }

    private static String sha1File(Path file) throws IOException {
        MessageDigest digest = sha1Digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha1Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("В системе недоступен SHA-1, необходимый для проверки файлов Mojang.",
                    exception);
        }
    }

    private static boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                second.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static String normalizedOsName() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return "windows";
        }
        if (osName.contains("mac")) {
            return "osx";
        }
        if (osName.contains("linux")) {
            return "linux";
        }
        return "unknown";
    }

    private static String nativeArchitecture() {
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return architecture.matches("amd64|x86_64") ? "64" : "32";
    }

    private static void requireWindowsX64() throws IOException {
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!normalizedOsName().equals("windows") || !architecture.matches("amd64|x86_64")) {
            throw new IOException("Установка Minecraft поддерживается только в Windows x64.");
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Загрузка Minecraft отменена.");
        }
    }

    private static void report(ProgressListener listener, String message, long completedBytes, long totalBytes,
                               int completedFiles, int totalFiles, long downloadedBytes) {
        listener.update(new Progress(message, completedBytes, totalBytes,
                completedFiles, totalFiles, downloadedBytes));
    }

    public record Progress(String message, long completedBytes, long totalBytes,
                           int completedFiles, int totalFiles, long downloadedBytes) {
    }

    public record InstallResult(Path gameDirectory, String version, int verifiedFiles, long totalBytes,
                                int downloadedFiles, long downloadedBytes) {
    }

    private record DownloadJob(URI uri, Path target, String sha1, long size, String label) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void update(Progress progress);
    }
}
