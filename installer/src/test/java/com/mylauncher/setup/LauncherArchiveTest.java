package com.mylauncher.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherArchiveTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsOnlyExpectedLauncherImageFiles() throws IOException {
        Path archive = createArchive("app/MyLauncher.cfg", "config data",
                "runtime/release", "runtime data", "MyLauncher.exe", "executable placeholder");
        Path output = temporaryDirectory.resolve("image");

        LauncherArchive.extract(archive, output);

        assertEquals("config data", Files.readString(output.resolve("app/MyLauncher.cfg")));
        assertTrue(Files.isRegularFile(output.resolve("MyLauncher.exe")));
    }

    @Test
    void rejectsZipSlipPaths() throws IOException {
        Path archive = createArchive("../outside.txt", "not allowed");

        assertThrows(IOException.class, () -> LauncherArchive.extract(archive, temporaryDirectory.resolve("image")));
        assertTrue(Files.notExists(temporaryDirectory.resolve("outside.txt")));
    }

    @Test
    void rejectsUnexpectedTopLevelFiles() throws IOException {
        Path archive = createArchive("Game/saves/world.dat", "user data");

        assertThrows(IOException.class, () -> LauncherArchive.extract(archive, temporaryDirectory.resolve("image")));
    }

    @Test
    void computesSha256() throws IOException {
        Path input = temporaryDirectory.resolve("input.txt");
        Files.writeString(input, "abc");

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                LauncherArchive.sha256(input));
    }

    @Test
    void replacesOnlyLauncherFilesAndPreservesGameData() throws IOException {
        Path install = temporaryDirectory.resolve("install");
        Path staged = temporaryDirectory.resolve("staged");
        write(install.resolve("app/old.cfg"), "old application");
        write(install.resolve("runtime/release"), "old runtime");
        write(install.resolve("MyLauncher.exe"), "old executable");
        write(install.resolve("Game/saves/world/level.dat"), "world data");
        write(staged.resolve("app/new.cfg"), "new application");
        write(staged.resolve("runtime/release"), "new runtime");
        write(staged.resolve("MyLauncher.exe"), "new executable");

        LauncherArchive.replaceLauncherFiles(staged, install);

        assertEquals("new application", Files.readString(install.resolve("app/new.cfg")));
        assertEquals("new runtime", Files.readString(install.resolve("runtime/release")));
        assertEquals("new executable", Files.readString(install.resolve("MyLauncher.exe")));
        assertEquals("world data", Files.readString(install.resolve("Game/saves/world/level.dat")));
        assertFalse(Files.exists(install.resolve("app/old.cfg")));
    }

    @Test
    void restoresPreviousLauncherIfReplacementFails() throws IOException {
        Path install = temporaryDirectory.resolve("install");
        Path staged = temporaryDirectory.resolve("staged");
        write(install.resolve("app/original.cfg"), "original application");
        write(install.resolve("runtime/release"), "original runtime");
        write(install.resolve("MyLauncher.exe"), "original executable");
        write(staged.resolve("app/new.cfg"), "new application");

        assertThrows(IOException.class, () -> LauncherArchive.replaceLauncherFiles(staged, install));

        assertEquals("original application", Files.readString(install.resolve("app/original.cfg")));
        assertEquals("original runtime", Files.readString(install.resolve("runtime/release")));
        assertEquals("original executable", Files.readString(install.resolve("MyLauncher.exe")));
    }

    private Path createArchive(String... entries) throws IOException {
        Path archive = temporaryDirectory.resolve("launcher.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int index = 0; index < entries.length; index += 2) {
                zip.putNextEntry(new ZipEntry(entries[index]));
                zip.write(entries[index + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
