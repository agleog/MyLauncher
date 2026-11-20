package com.mylauncher.setup;

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
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GithubReleaseClient {
    static final String ARCHIVE_NAME = "MyLauncher-windows-x64.zip";
    private static final String GITHUB_RELEASE_ASSET_SUFFIX = ".sha256";
    private static final Pattern REPOSITORY = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9_.-]+"
    );
    private static final Pattern SHA256_LINE = Pattern.compile("^([a-fA-F0-9]{64})(?:\\s+.*)?$");
    private static final long MAX_CHECKSUM_BYTES = 4096;
    private static final long MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    GithubReleaseClient() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        objectMapper = new ObjectMapper();
    }

    static String validateRepository(String repository) {
        String normalized = repository == null ? "" : repository.strip();
        if (!REPOSITORY.matcher(normalized).matches() || normalized.contains("..")) {
            throw new IllegalArgumentException("Укажите публичный репозиторий в формате владелец/репозиторий.");
        }
        return normalized;
    }

    GithubRelease fetchLatest(String repository) throws IOException, InterruptedException {
        String normalized = validateRepository(repository);
        URI releaseUri = URI.create("https://api.github.com/repos/" + normalized + "/releases/latest");
        HttpRequest request = HttpRequest.newBuilder(releaseUri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "MyLauncher-Setup")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw apiFailure(response.statusCode(), response.body());
        }

        JsonNode release = objectMapper.readTree(response.body());
        String version = requiredText(release, "tag_name");
        JsonNode assets = release.path("assets");
        JsonNode archive = findAsset(assets, ARCHIVE_NAME);
        if (archive == null) {
            throw new IOException("В последнем релизе отсутствует файл " + ARCHIVE_NAME + ".");
        }

        URI archiveUri = validateDownloadUri(URI.create(requiredText(archive, "browser_download_url")));
        long archiveSize = archive.path("size").asLong(-1);
        if (archiveSize <= 0 || archiveSize > MAX_ARCHIVE_BYTES) {
            throw new IOException("GitHub сообщил некорректный размер установочного архива.");
        }

        String digest = archive.path("digest").asText("");
        String expectedSha256 = digest.isBlank() ? "" : parseDigest(digest);
        JsonNode checksum = findAsset(assets, ARCHIVE_NAME + GITHUB_RELEASE_ASSET_SUFFIX);
        URI checksumUri = checksum == null ? null
                : validateDownloadUri(URI.create(requiredText(checksum, "browser_download_url")));
        if (expectedSha256.isEmpty() && checksumUri == null) {
            throw new IOException("У релиза нет SHA-256 для архива. Добавьте digest или файл "
                    + ARCHIVE_NAME + GITHUB_RELEASE_ASSET_SUFFIX + ".");
        }

        String notes = release.path("body").asText("");
        return new GithubRelease(version, archiveUri, archiveSize, expectedSha256, checksumUri, notes);
    }

    void download(URI uri, Path destination, LongConsumer progress)
            throws IOException, InterruptedException {
        download(uri, destination, MAX_ARCHIVE_BYTES, progress);
    }

    private void download(URI uri, Path destination, long maxBytes, LongConsumer progress)
            throws IOException, InterruptedException {
        URI validatedUri = validateDownloadUri(uri);
        HttpRequest request = HttpRequest.newBuilder(validatedUri)
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "MyLauncher-Setup")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("GitHub не смог отдать файл (HTTP " + response.statusCode() + ").");
            }
            validateDownloadUri(response.uri());
            Files.createDirectories(destination.getParent());
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            try (var output = Files.newOutputStream(destination)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Загрузка отменена пользователем.");
                    }
                    if (total + read > maxBytes) {
                        throw new IOException("Загружаемый файл превышает допустимый размер.");
                    }
                    output.write(buffer, 0, read);
                    total += read;
                    progress.accept(total);
                }
            }
        }
    }

    String downloadChecksum(URI uri, Path destination) throws IOException, InterruptedException {
        download(uri, destination, MAX_CHECKSUM_BYTES, ignored -> { });
        String content = Files.readString(destination).strip();
        Matcher matcher = SHA256_LINE.matcher(content);
        if (!matcher.matches()) {
            throw new IOException("Файл .sha256 имеет неверный формат.");
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    static String parseDigest(String digest) throws IOException {
        if (!digest.startsWith("sha256:")) {
            throw new IOException("GitHub сообщил неподдерживаемый тип контрольной суммы.");
        }
        String value = digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
        if (value.length() != 64 || !HexFormat.of().isHexDigit(value.charAt(0))
                || !value.chars().allMatch(character -> Character.digit(character, 16) >= 0)) {
            throw new IOException("GitHub сообщил неверную SHA-256 контрольную сумму.");
        }
        return value;
    }

    private URI validateDownloadUri(URI uri) throws IOException {
        String host = uri.getHost();
        boolean allowedHost = host != null && (host.equalsIgnoreCase("github.com")
                || host.equalsIgnoreCase("release-assets.githubusercontent.com")
                || host.equalsIgnoreCase("github-releases.githubusercontent.com")
                || host.equalsIgnoreCase("objects.githubusercontent.com"));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHost
                || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) {
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
            throw new IOException("Ответ GitHub Releases не содержит поле " + field + ".");
        }
        return value;
    }

    private static IOException apiFailure(int statusCode, String body) {
        if (statusCode == 404) {
            return new IOException("Не удалось найти публичный репозиторий или опубликованный релиз (HTTP 404). "
                    + "Проверьте владельца/название репозитория и опубликуйте релиз.");
        }
        if (statusCode == 403 || statusCode == 429) {
            return new IOException("GitHub временно ограничил запросы (HTTP " + statusCode
                    + "). Подождите и повторите установку.");
        }
        return new IOException("Ошибка GitHub Releases API (HTTP " + statusCode + "): "
                + body.substring(0, Math.min(body.length(), 300)));
    }
}
