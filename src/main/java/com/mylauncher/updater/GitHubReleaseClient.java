package com.mylauncher.updater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GitHubReleaseClient {
    static final String ARCHIVE_NAME = "MyLauncher-windows-x64.zip";
    private static final Pattern REPOSITORY = Pattern.compile(
            "[A-Za-z0-9]([A-Za-z0-9-]{0,38})/[A-Za-z0-9_.-]+"
    );
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern CHECKSUM = Pattern.compile("^([a-fA-F0-9]{64})(?:\\s+.*)?$");
    private static final long MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_CHECKSUM_BYTES = 4096;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    ReleaseInfo fetchLatest(String repository) throws IOException, InterruptedException {
        String normalizedRepository = validateRepository(repository);
        URI releaseUri = URI.create("https://api.github.com/repos/" + normalizedRepository + "/releases/latest");
        HttpRequest request = HttpRequest.newBuilder(releaseUri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "MyLauncher")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw apiFailure(response.statusCode());
        }

        JsonNode release = objectMapper.readTree(response.body());
        String version = requiredText(release, "tag_name");
        JsonNode archive = findAsset(release.path("assets"), ARCHIVE_NAME);
        if (archive == null) {
            throw new IOException("В последнем релизе отсутствует " + ARCHIVE_NAME + ".");
        }
        URI archiveUri = validateDownloadUri(URI.create(requiredText(archive, "browser_download_url")));
        long archiveSize = archive.path("size").asLong(-1);
        if (archiveSize <= 0 || archiveSize > MAX_DOWNLOAD_BYTES) {
            throw new IOException("GitHub сообщил некорректный размер обновления.");
        }

        String digest = archive.path("digest").asText("");
        String sha256 = digest.isBlank() ? "" : parseDigest(digest);
        JsonNode checksum = findAsset(release.path("assets"), ARCHIVE_NAME + ".sha256");
        URI checksumUri = checksum == null ? null
                : validateDownloadUri(URI.create(requiredText(checksum, "browser_download_url")));
        if (sha256.isEmpty() && checksumUri == null) {
            throw new IOException("У обновления отсутствует контрольная сумма SHA-256.");
        }
        return new ReleaseInfo(version, archiveUri, archiveSize, sha256, checksumUri);
    }

    void download(URI uri, Path destination, long limit) throws IOException, InterruptedException {
        validateDownloadUri(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "MyLauncher")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Не удалось скачать обновление (HTTP " + response.statusCode() + ").");
            }
            validateDownloadUri(response.uri());
            Files.createDirectories(destination.getParent());
            try (var output = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Загрузка обновления отменена.");
                    }
                    total += read;
                    if (total > limit) {
                        throw new IOException("Размер загружаемого файла превышает допустимый предел.");
                    }
                    output.write(buffer, 0, read);
                }
            }
        }
    }

    String downloadChecksum(URI uri, Path destination) throws IOException, InterruptedException {
        download(uri, destination, MAX_CHECKSUM_BYTES);
        Matcher matcher = CHECKSUM.matcher(Files.readString(destination).strip());
        if (!matcher.matches()) {
            throw new IOException("Файл SHA-256 имеет неверный формат.");
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    static String validateRepository(String repository) {
        String normalized = repository == null ? "" : repository.strip();
        if (!REPOSITORY.matcher(normalized).matches() || normalized.contains("..")) {
            throw new IllegalArgumentException("Неверно настроен GitHub-репозиторий лаунчера.");
        }
        return normalized;
    }

    static boolean isNewerVersion(String candidate, String current) throws IOException {
        long[] candidateParts = parseVersion(candidate);
        long[] currentParts = parseVersion(current);
        for (int index = 0; index < candidateParts.length; index++) {
            int comparison = Long.compare(candidateParts[index], currentParts[index]);
            if (comparison != 0) {
                return comparison > 0;
            }
        }
        return false;
    }

    static String parseDigest(String digest) throws IOException {
        if (!digest.startsWith("sha256:")) {
            throw new IOException("GitHub сообщил неподдерживаемый тип контрольной суммы.");
        }
        String value = digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
        if (value.length() != 64 || !value.chars().allMatch(character -> Character.digit(character, 16) >= 0)) {
            throw new IOException("GitHub сообщил неверную SHA-256 контрольную сумму.");
        }
        return value;
    }

    private static long[] parseVersion(String version) throws IOException {
        Matcher matcher = VERSION.matcher(version == null ? "" : version);
        if (!matcher.matches()) {
            throw new IOException("Неподдерживаемый формат версии лаунчера: " + version);
        }
        try {
            return new long[]{Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3))};
        } catch (NumberFormatException exception) {
            throw new IOException("Неподдерживаемый формат версии лаунчера: " + version, exception);
        }
    }

    private URI validateDownloadUri(URI uri) throws IOException {
        String host = uri.getHost();
        boolean allowedHost = host != null && (host.equalsIgnoreCase("github.com")
                || host.equalsIgnoreCase("release-assets.githubusercontent.com")
                || host.equalsIgnoreCase("github-releases.githubusercontent.com")
                || host.equalsIgnoreCase("objects.githubusercontent.com"));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHost || uri.getUserInfo() != null
                || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IOException("GitHub вернул небезопасный адрес загрузки.");
        }
        return uri;
    }

    private static JsonNode findAsset(JsonNode assets, String name) {
        for (JsonNode asset : assets) {
            if (name.equals(asset.path("name").asText())) {
                return asset;
            }
        }
        return null;
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IOException("GitHub Releases не вернул поле " + field + ".");
        }
        return value;
    }

    private static IOException apiFailure(int statusCode) {
        if (statusCode == 404) {
            return new IOException("Не найден публичный GitHub-репозиторий или опубликованный релиз.");
        }
        if (statusCode == 403 || statusCode == 429) {
            return new IOException("GitHub временно ограничил запросы (HTTP " + statusCode + "). Повторите позже.");
        }
        return new IOException("Ошибка GitHub Releases API (HTTP " + statusCode + ").");
    }
}
