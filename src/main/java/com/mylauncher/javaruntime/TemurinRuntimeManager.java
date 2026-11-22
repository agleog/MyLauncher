package com.mylauncher.javaruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class TemurinRuntimeManager {
    private static final URI RELEASES_API = URI.create(
            "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&heap_size=normal"
                    + "&image_type=jre&os=windows&vendor=eclipse");
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
    private static final String INTEGRITY_MANIFEST = ".mylauncher-runtime.sha256";
    private static final Pattern JAVA_VERSION = Pattern.compile(
            "(?i)(?:openjdk|java)\\s+version\\s+\"?([0-9]+(?:\\.[0-9]+)*)");
    private static final Pattern JAVA_ARCHITECTURE = Pattern.compile(
            "(?im)^\\s*os\\.arch\\s*=\\s*(amd64|x86_64)\\s*$");

    private final HttpClient httpClient;

    public TemurinRuntimeManager() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Path runtimeHome(Path gameDirectory) {
        return gameDirectory.toAbsolutePath().normalize().resolve("runtime").resolve("temurin-21");
    }

    public RuntimeInfo verify(Path gameDirectory) throws IOException, InterruptedException {
        Path javaHome = runtimeHome(gameDirectory);
        verifyIntegrityManifest(javaHome);
        return probeJava(javaHome);
    }

    public RuntimeInfo install(Path gameDirectory, ProgressListener progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Path runtimeDirectory = runtimeHome(gameDirectory);
        Path runtimeParent = runtimeDirectory.getParent();
        Files.createDirectories(runtimeParent);
        if (Files.isSymbolicLink(runtimeParent) || Files.isSymbolicLink(runtimeDirectory)
                || Files.exists(runtimeDirectory) && !Files.isDirectory(runtimeDirectory)) {
            throw new IOException("Папка Java существует, но не является обычной директорией: " + runtimeDirectory);
        }

        checkCancelled(cancelled);
        PackageMetadata metadata = fetchPackageMetadata();
        Path staging = Files.createTempDirectory(runtimeParent, ".temurin-21-");
        Path backup = runtimeParent.resolve(".temurin-21-backup-" + UUID.randomUUID());
        boolean backedUp = false;
        try {
            Path archive = staging.resolve("temurin.zip");
            downloadPackage(metadata, archive, progress, cancelled);

            Path extraction = staging.resolve("extracted");
            Files.createDirectory(extraction);
            extractArchive(archive, extraction, cancelled);
            Path extractedJava = findJavaExecutable(extraction);
            Path extractedHome = extractedJava.getParent().getParent();
            probeJava(extractedHome);
            writeIntegrityManifest(extractedHome);

            checkCancelled(cancelled);
            if (Files.exists(runtimeDirectory)) {
                moveDirectory(runtimeDirectory, backup);
                backedUp = true;
            }
            RuntimeInfo installedRuntime;
            try {
                moveDirectory(extractedHome, runtimeDirectory);
                installedRuntime = verify(gameDirectory);
            } catch (IOException | InterruptedException | RuntimeException exception) {
                if (Files.exists(runtimeDirectory)) {
                    try {
                        deleteTree(runtimeDirectory);
                    } catch (IOException rollbackException) {
                        exception.addSuppressed(rollbackException);
                    }
                }
                if (backedUp && Files.exists(backup) && !Files.exists(runtimeDirectory)) {
                    try {
                        moveDirectory(backup, runtimeDirectory);
                        backedUp = false;
                    } catch (IOException rollbackException) {
                        exception.addSuppressed(rollbackException);
                    }
                }
                throw exception;
            }
            if (backedUp) {
                deleteTree(backup);
                backedUp = false;
            }
            return installedRuntime;
        } finally {
            if (backedUp && Files.exists(backup) && !Files.exists(runtimeDirectory)) {
                moveDirectory(backup, runtimeDirectory);
            }
            deleteTree(staging);
        }
    }

    static PackageMetadata parsePackageMetadata(String responseBody) throws IOException {
        JsonNode assets;
        try {
            assets = new ObjectMapper().readTree(responseBody);
        } catch (IOException exception) {
            throw new IOException("Adoptium вернул некорректный манифест Java.", exception);
        }
        if (assets == null || !assets.isArray() || assets.isEmpty()) {
            throw new IOException("Adoptium не нашёл сборку Temurin Java 21 для Windows x64.");
        }

        for (JsonNode asset : assets) {
            JsonNode binary = asset.path("binary");
            if (!"eclipse".equalsIgnoreCase(asset.path("vendor").asText(""))
                    || !"x64".equalsIgnoreCase(binary.path("architecture").asText(""))
                    || !"windows".equalsIgnoreCase(binary.path("os").asText(""))
                    || !"jre".equalsIgnoreCase(binary.path("image_type").asText(""))
                    || !"hotspot".equalsIgnoreCase(binary.path("jvm_impl").asText(""))
                    || !"normal".equalsIgnoreCase(binary.path("heap_size").asText(""))) {
                continue;
            }
            JsonNode packageNode = binary.path("package");
            String name = packageNode.path("name").asText("");
            String link = packageNode.path("link").asText("");
            String checksum = packageNode.path("checksum").asText("");
            long size = packageNode.path("size").asLong(-1);
            if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")
                    || !checksum.matches("(?i)[0-9a-f]{64}")
                    || size <= 0 || size > MAX_ARCHIVE_BYTES) {
                continue;
            }
            URI packageUri;
            try {
                packageUri = URI.create(link);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Adoptium вернул некорректную ссылку на Java.", exception);
            }
            requireTrustedHttpsUri(packageUri);
            return new PackageMetadata(packageUri, checksum.toLowerCase(Locale.ROOT), size);
        }
        throw new IOException("В манифесте Adoptium нет подходящего ZIP-архива Temurin Java 21.");
    }

    private PackageMetadata fetchPackageMetadata() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(RELEASES_API)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "MyLauncher")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Adoptium не смог предоставить сведения о Java (HTTP "
                    + response.statusCode() + ").");
        }
        if (response.body().length() > 2_000_000) {
            throw new IOException("Манифест Adoptium превышает допустимый размер.");
        }
        return parsePackageMetadata(response.body());
    }

    private void downloadPackage(PackageMetadata metadata, Path archive, ProgressListener progress,
                                 BooleanSupplier cancelled) throws IOException, InterruptedException {
        URI uri = metadata.uri();
        HttpResponse<InputStream> response = null;
        for (int redirectCount = 0; redirectCount <= 5; redirectCount++) {
            requireTrustedHttpsUri(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(15))
                    .header("User-Agent", "MyLauncher")
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("Сервер Java вернул перенаправление без адреса."));
                response.body().close();
                if (redirectCount == 5) {
                    throw new IOException("Слишком много перенаправлений при загрузке Java.");
                }
                try {
                    uri = uri.resolve(location);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Сервер Java вернул некорректное перенаправление.", exception);
                }
                continue;
            }
            break;
        }

        if (response == null) {
            throw new IOException("Не удалось начать загрузку Java.");
        }
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Не удалось загрузить Temurin Java (HTTP "
                        + response.statusCode() + ").");
            }
            requireTrustedHttpsUri(response.uri());
            long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(metadata.size());
            if (totalBytes <= 0 || totalBytes > MAX_ARCHIVE_BYTES) {
                totalBytes = metadata.size();
            }
            MessageDigest digest = sha256();
            long downloadedBytes = 0;
            byte[] buffer = new byte[64 * 1024];
            try (OutputStream output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    downloadedBytes += count;
                    if (downloadedBytes > MAX_ARCHIVE_BYTES || downloadedBytes > metadata.size()) {
                        throw new IOException("Размер архива Java превышает значение в манифесте Adoptium.");
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    progress.update("Загрузка Temurin Java 21", downloadedBytes, totalBytes);
                }
            }
            if (downloadedBytes != metadata.size()) {
                throw new IOException("Размер архива Java не совпадает с манифестом Adoptium.");
            }
            String actualChecksum = HexFormat.of().formatHex(digest.digest());
            if (!MessageDigest.isEqual(actualChecksum.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    metadata.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new IOException("Проверка SHA-256 архива Temurin Java не прошла.");
            }
        }
    }

    private static void requireTrustedHttpsUri(URI uri) throws IOException {
        String host = uri.getHost();
        boolean trustedHost = host != null && (host.equalsIgnoreCase("github.com")
                || host.toLowerCase(Locale.ROOT).endsWith(".githubusercontent.com")
                || host.equalsIgnoreCase("api.adoptium.net"));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !trustedHost
                || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IOException("Ссылка на Java не прошла проверку HTTPS-адреса.");
        }
    }

    static void extractArchive(Path archive, Path extraction, BooleanSupplier cancelled) throws IOException {
        Path normalizedRoot = extraction.toAbsolutePath().normalize();
        long expandedBytes = 0;
        try (InputStream fileInput = Files.newInputStream(archive);
             ZipInputStream zipInput = new ZipInputStream(fileInput)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                checkCancelled(cancelled);
                String name = entry.getName();
                if (name.isBlank() || name.contains("\\") || name.contains(":") || name.startsWith("/")
                        || name.matches("(?i)^[a-z]:.*")) {
                    throw new IOException("В архиве Java найден недопустимый путь.");
                }
                Path destination = normalizedRoot.resolve(name).normalize();
                if (!destination.startsWith(normalizedRoot)) {
                    throw new IOException("В архиве Java найден путь за пределами папки установки.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = zipInput.read(buffer)) != -1) {
                            checkCancelled(cancelled);
                            expandedBytes += count;
                            if (expandedBytes > MAX_EXPANDED_BYTES) {
                                throw new IOException("Распакованный архив Java превышает допустимый размер.");
                            }
                            output.write(buffer, 0, count);
                        }
                    }
                }
                zipInput.closeEntry();
            }
        }
    }

    private static Path findJavaExecutable(Path extraction) throws IOException {
        try (Stream<Path> paths = Files.walk(extraction)) {
            return paths.filter(path -> path.getFileName() != null
                            && path.getFileName().toString().equalsIgnoreCase("java.exe")
                            && path.getParent() != null
                            && path.getParent().getFileName() != null
                            && path.getParent().getFileName().toString().equalsIgnoreCase("bin"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(() -> new IOException("В архиве Temurin не найден bin\\java.exe."));
        }
    }

    static void writeIntegrityManifest(Path javaHome) throws IOException {
        Path normalizedHome = javaHome.toAbsolutePath().normalize();
        Properties hashes = new Properties();
        try (Stream<Path> paths = Files.walk(normalizedHome)) {
            for (Path path : paths.toList()) {
                if (path.equals(normalizedHome) || path.equals(normalizedHome.resolve(INTEGRITY_MANIFEST))) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Java Runtime содержит символическую ссылку: " + path);
                }
                if (Files.isRegularFile(path)) {
                    String relativePath = normalizedHome.relativize(path).toString().replace('\\', '/');
                    hashes.setProperty(relativePath, sha256File(path));
                } else if (!Files.isDirectory(path)) {
                    throw new IOException("Java Runtime содержит неподдерживаемый тип файла: " + path);
                }
            }
        }
        if (hashes.isEmpty()) {
            throw new IOException("Не удалось создать манифест целостности Java Runtime.");
        }
        Path temporaryFile = Files.createTempFile(normalizedHome, ".runtime-manifest-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                hashes.store(output, "MyLauncher verified Temurin runtime files");
            }
            try {
                Files.move(temporaryFile, normalizedHome.resolve(INTEGRITY_MANIFEST),
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, normalizedHome.resolve(INTEGRITY_MANIFEST));
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    static void verifyIntegrityManifest(Path javaHome) throws IOException {
        Path normalizedHome = javaHome.toAbsolutePath().normalize();
        Path manifest = normalizedHome.resolve(INTEGRITY_MANIFEST);
        if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest)) {
            throw new IOException("Не найден манифест целостности установленной Java. Переустановите Temurin 21.");
        }

        Properties hashes = new Properties();
        try (InputStream input = Files.newInputStream(manifest)) {
            hashes.load(input);
        }
        int actualFiles = 0;
        try (Stream<Path> paths = Files.walk(normalizedHome)) {
            for (Path path : paths.toList()) {
                if (path.equals(normalizedHome) || path.equals(manifest)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Java Runtime содержит символическую ссылку: " + path);
                }
                if (Files.isRegularFile(path)) {
                    actualFiles++;
                } else if (!Files.isDirectory(path)) {
                    throw new IOException("Java Runtime содержит неподдерживаемый тип файла: " + path);
                }
            }
        }
        if (actualFiles != hashes.size()) {
            throw new IOException("Состав Java Runtime изменился. Переустановите Temurin 21.");
        }

        for (String relativeName : hashes.stringPropertyNames()) {
            final Path relativePath;
            try {
                relativePath = Path.of(relativeName);
            } catch (RuntimeException exception) {
                throw new IOException("Манифест целостности Java содержит некорректный путь.", exception);
            }
            Path file = normalizedHome.resolve(relativePath).normalize();
            if (relativePath.isAbsolute() || !file.startsWith(normalizedHome) || file.equals(manifest)
                    || Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                throw new IOException("Манифест целостности Java содержит недопустимый путь.");
            }
            String expectedHash = hashes.getProperty(relativeName);
            if (expectedHash == null || !expectedHash.matches("(?i)[0-9a-f]{64}")
                    || !MessageDigest.isEqual(sha256File(file).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    expectedHash.toLowerCase(Locale.ROOT)
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new IOException("Файл Java Runtime повреждён: " + relativeName + ". Переустановите Temurin 21.");
            }
        }
    }

    private static String sha256File(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static RuntimeInfo probeJava(Path javaHome) throws IOException, InterruptedException {
        Path executable = javaHome.resolve("bin").resolve("java.exe");
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Java Runtime не найдена: " + executable);
        }
        Path outputFile = Files.createTempFile("mylauncher-java-version-", ".txt");
        Process process = null;
        try {
            process = new ProcessBuilder(executable.toString(), "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                throw new IOException("Java не ответила на проверку версии за 20 секунд.");
            }
            String output = Files.readString(outputFile);
            if (process.exitValue() != 0) {
                throw new IOException("Java Runtime завершилась с ошибкой при проверке версии.");
            }
            String detectedVersion = parseJavaVersion(output);
            Matcher architectureMatcher = JAVA_ARCHITECTURE.matcher(output);
            String detectedArchitecture = architectureMatcher.find() ? architectureMatcher.group(1) : "не определена";
            if (!detectedVersion.startsWith("21.") || !output.contains("64-Bit")
                    || detectedArchitecture.equals("не определена")
                    || !output.toLowerCase(Locale.ROOT).contains("temurin")) {
                throw new IOException("Требуется Eclipse Temurin Java 21 x64; обнаружено: версия "
                        + detectedVersion + ", архитектура " + detectedArchitecture + ", "
                        + (output.toLowerCase(Locale.ROOT).contains("temurin") ? "Temurin" : "другой поставщик") + ".");
            }
            return new RuntimeInfo(javaHome.toAbsolutePath().normalize(), detectedVersion, "x64");
        } catch (InterruptedException exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    static String parseJavaVersion(String output) throws IOException {
        Matcher matcher = JAVA_VERSION.matcher(output);
        if (!matcher.find()) {
            throw new IOException("Java не сообщила свою версию.");
        }
        return matcher.group(1);
    }

    private static void moveDirectory(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("Установка Java отменена.");
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("В системе недоступен SHA-256.", exception);
        }
    }

    public record RuntimeInfo(Path javaHome, String version, String architecture) {
    }

    record PackageMetadata(URI uri, String sha256, long size) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void update(String message, long completedBytes, long totalBytes);
    }
}
