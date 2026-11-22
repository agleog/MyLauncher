package com.mylauncher.javaruntime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemurinRuntimeManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOfficialTemurinZipMetadata() throws IOException {
        String checksum = "a".repeat(64);
        String body = """
                [{
                  "vendor": "eclipse",
                  "binary": {
                    "architecture": "x64",
                    "os": "windows",
                    "image_type": "jre",
                    "jvm_impl": "hotspot",
                    "heap_size": "normal",
                    "package": {
                      "name": "OpenJDK21U-jre_x64_windows_hotspot.zip",
                      "link": "https://github.com/adoptium/temurin21-binaries/releases/download/test/runtime.zip",
                      "checksum": "%s",
                      "size": 12345
                    }
                  }
                }]
                """.formatted(checksum);

        TemurinRuntimeManager.PackageMetadata metadata =
                TemurinRuntimeManager.parsePackageMetadata(body);

        assertEquals("https://github.com/adoptium/temurin21-binaries/releases/download/test/runtime.zip",
                metadata.uri().toString());
        assertEquals(checksum, metadata.sha256());
        assertEquals(12345, metadata.size());
    }

    @Test
    void rejectsUntrustedDownloadHosts() {
        String body = packageMetadata("https://example.com/runtime.zip", "b".repeat(64), 100);

        assertThrows(IOException.class, () -> TemurinRuntimeManager.parsePackageMetadata(body));
    }

    @Test
    void rejectsInvalidChecksums() {
        String body = packageMetadata("https://github.com/adoptium/runtime.zip", "not-a-checksum", 100);

        assertThrows(IOException.class, () -> TemurinRuntimeManager.parsePackageMetadata(body));
    }

    @Test
    void extractsVersionFromJavaVersionLineNotServerArchitecture() throws IOException {
        String output = "openjdk 64-Bit Server VM Temurin\nopenjdk version \"21.0.13\" 2026-10-20";

        assertEquals("21.0.13", TemurinRuntimeManager.parseJavaVersion(output));
    }

    @Test
    void rejectsArchiveEntriesThatEscapeExtractionDirectory() throws IOException {
        Path archive = temporaryDirectory.resolve("malicious.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("../outside.txt"));
            output.write("unexpected".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Path extraction = temporaryDirectory.resolve("runtime");
        Files.createDirectory(extraction);

        assertThrows(IOException.class, () -> TemurinRuntimeManager.extractArchive(
                archive, extraction, () -> false));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(temporaryDirectory.resolve("outside.txt")));
    }

    @Test
    void stopsExtractionWhenCancelled() throws IOException {
        Path archive = temporaryDirectory.resolve("runtime.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("runtime/bin/java.exe"));
            output.write(new byte[64 * 1024]);
            output.closeEntry();
        }
        Path extraction = temporaryDirectory.resolve("runtime");
        Files.createDirectory(extraction);

        assertThrows(CancellationException.class, () -> TemurinRuntimeManager.extractArchive(
                archive, extraction, () -> true));
    }

    @Test
    void detectsFilesChangedAfterRuntimeInstallation() throws IOException {
        Path runtime = temporaryDirectory.resolve("temurin-21");
        Path javaExecutable = runtime.resolve("bin").resolve("java.exe");
        Files.createDirectories(javaExecutable.getParent());
        Files.writeString(javaExecutable, "verified runtime");
        TemurinRuntimeManager.writeIntegrityManifest(runtime);

        TemurinRuntimeManager.verifyIntegrityManifest(runtime);

        Files.writeString(javaExecutable, "modified runtime");
        assertThrows(IOException.class, () -> TemurinRuntimeManager.verifyIntegrityManifest(runtime));
    }

    private static String packageMetadata(String link, String checksum, long size) {
        return """
                [{
                  "vendor": "eclipse",
                  "binary": {
                    "architecture": "x64",
                    "os": "windows",
                    "image_type": "jre",
                    "jvm_impl": "hotspot",
                    "heap_size": "normal",
                    "package": {
                      "name": "temurin-jre.zip",
                      "link": "%s",
                      "checksum": "%s",
                      "size": %d
                    }
                  }
                }]
                """.formatted(link, checksum, size);
    }
}
