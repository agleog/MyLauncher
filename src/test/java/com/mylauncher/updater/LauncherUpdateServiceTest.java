package com.mylauncher.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LauncherUpdateServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsOnlyExpectedLauncherFiles() throws IOException {
        Path archive = createArchive("app/MyLauncher.jar", "runtime/bin/java.exe", "MyLauncher.exe");
        Path destination = temporaryDirectory.resolve("image");

        LauncherUpdateService.extractAndValidate(archive, destination);

        assertEquals("file", Files.readString(destination.resolve("app/MyLauncher.jar")));
        assertEquals("file", Files.readString(destination.resolve("runtime/bin/java.exe")));
        assertEquals("file", Files.readString(destination.resolve("MyLauncher.exe")));
    }

    @Test
    void rejectsPathsThatEscapeOrCrossArchiveRoots() throws IOException {
        Path archive = createArchive("app/MyLauncher.jar", "runtime/bin/java.exe", "MyLauncher.exe",
                "app/../runtime/evil.txt");

        assertThrows(IOException.class,
                () -> LauncherUpdateService.extractAndValidate(archive, temporaryDirectory.resolve("image")));
    }

    private Path createArchive(String... entries) throws IOException {
        Path archive = temporaryDirectory.resolve("update.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String name : entries) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("file".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }
}
